package com.youjixingtu.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.youjixingtu.app.databinding.ActivityNearbyStationBinding

/**
 * 选择附近加油站：定位 → 5 公里内「加油站」POI 搜索 → 列表点选。
 * 定位/搜索失败或空结果时，降级展示历史加油站；仍无则引导手动输入。
 * 结果通过 Intent extra "station" 返回给调用方。
 */
class NearbyStationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STATION = "station"
    }

    private lateinit var binding: ActivityNearbyStationBinding
    private var locationClient: AMapLocationClient? = null
    private var search: PoiSearch? = null
    private val stationList = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private var settled = false // 是否已确定结果，避免重复回调

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLocate() else fallbackToHistory("需要定位权限才能搜索附近加油站，可从下方手动输入")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNearbyStationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, stationList)
        binding.nearbyListView.adapter = adapter
        binding.nearbyListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position in stationList.indices) returnResult(stationList[position])
        }
        binding.nearbyConfirmBtn.setOnClickListener {
            val text = binding.nearbyManualInput.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                toast("请输入加油站名称，或从上方列表选择")
            } else {
                returnResult(text)
            }
        }
        binding.nearbyCancelBtn.setOnClickListener { finish() }

        // 预置历史加油站，定位期间也可见，失败时作为兜底
        loadHistoryIntoList()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startLocate()
        } else {
            binding.nearbyStatusText.text = "请求定位权限中..."
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun loadHistoryIntoList() {
        val history = com.youjixingtu.app.data.Repository.loadRecords()
            .mapNotNull { it.refuelData?.gasStation }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
        stationList.clear()
        stationList.addAll(history)
        adapter.notifyDataSetChanged()
    }

    private fun startLocate() {
        binding.nearbyStatusText.text = "正在获取当前位置..."
        try {
            if (locationClient == null) {
                locationClient = AMapLocationClient(applicationContext).apply {
                    setLocationListener(AMapLocationListener { location ->
                        if (location != null && location.errorCode == 0) {
                            searchNearby(location.latitude, location.longitude)
                        } else {
                            val reason = location?.errorInfo ?: "定位失败"
                            fallbackToHistory("定位失败：$reason，可手动输入或从历史中选择")
                        }
                    })
                }
            }
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isOnceLocationLatest = true
                httpTimeOut = 10000
            }
            locationClient?.setLocationOption(option)
            locationClient?.startLocation()
        } catch (e: Exception) {
            fallbackToHistory("定位不可用：${e.message ?: "未知错误"}，可手动输入")
        }
    }

    private fun searchNearby(lat: Double, lng: Double) {
        binding.nearbyStatusText.text = "正在搜索附近加油站（5 公里）..."
        binding.nearbyProgress.visibility = View.VISIBLE
        try {
            val query = PoiSearch.Query("加油站", "汽车服务", "").apply {
                pageSize = 20
                pageNum = 0
            }
            search = PoiSearch(this, query).apply {
                bound = PoiSearch.SearchBound(LatLonPoint(lat, lng), 5000, true)
                setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                    override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                        binding.nearbyProgress.visibility = View.GONE
                        if (rCode == 1000 && result != null) {
                            val pois = result.pois.orEmpty()
                            if (pois.isNotEmpty()) {
                                val names = pois.mapNotNull { poi ->
                                    val name = poi.title?.trim().orEmpty()
                                    if (name.isBlank()) null else {
                                        val distance = poi.distance
                                        if (distance > 0) "$name（${distance}米）" else name
                                    }
                                }.distinct().take(20)
                                if (names.isNotEmpty()) {
                                    stationList.clear()
                                    stationList.addAll(names)
                                    adapter.notifyDataSetChanged()
                                    binding.nearbyStatusText.text = "找到 ${names.size} 个附近加油站，点击即可填入「去了哪里」"
                                    return
                                }
                            }
                            fallbackToHistory("附近没有搜到加油站，可手动输入或从历史中选择")
                        } else {
                            fallbackToHistory("加油站搜索失败（代码 $rCode），可手动输入或从历史中选择")
                        }
                    }

                    override fun onPoiItemSearched(poiItem: PoiItem?, rCode: Int) {
                        // 不需要单个 POI 详情
                    }
                })
            }
            search?.searchPOIAsyn()
        } catch (e: Exception) {
            binding.nearbyProgress.visibility = View.GONE
            fallbackToHistory("搜索不可用：${e.message ?: "未知错误"}，可手动输入")
        }
    }

    private fun fallbackToHistory(message: String) {
        binding.nearbyProgress.visibility = View.GONE
        binding.nearbyStatusText.text = message
        // 列表里已是历史加油站；无历史时引导手动输入
        if (stationList.isEmpty()) {
            binding.nearbyStatusText.text = "$message（暂无历史加油站，请在下方手动输入）"
        }
    }

    private fun returnResult(rawName: String) {
        if (settled) return
        settled = true
        // 去掉距离后缀，只保留加油站名称写入记录
        val clean = rawName.replace(Regex("（[0-9]+米）$"), "").trim()
        intent.putExtra(EXTRA_STATION, clean)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun onDestroy() {
        try {
            locationClient?.stopLocation()
            locationClient?.onDestroy()
        } catch (_: Exception) {
        }
        try {
            search?.setOnPoiSearchListener(null)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
