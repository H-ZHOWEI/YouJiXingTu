package com.youjixingtu.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.core.ServiceSettings
import com.youjixingtu.app.data.Repository
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
        if (granted) {
            startLocate()
        } else {
            // C2：被永久拒绝（不再询问）时引导去系统设置开启
            val permanent = !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            if (permanent) {
                AlertDialog.Builder(this)
                    .setTitle("定位权限已被拒绝")
                    .setMessage("定位权限已被设为「不再询问」。请到 系统设置 → 应用 → 油迹星图 → 权限 中允许「位置信息」，回来后即可搜索附近加油站；或直接在下方手动输入。")
                    .setPositiveButton("去设置") { _, _ ->
                        runCatching {
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:$packageName")
                            })
                        }
                    }
                    .setNegativeButton("手动输入") { _, _ ->
                        fallbackToHistory("未开启定位权限，可从下方手动输入或从历史中选择")
                    }
                    .show()
            } else {
                fallbackToHistory("需要定位权限才能搜索附近加油站，可从下方手动输入")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNearbyStationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // B5：App 内配置的 Key 优先，运行时注入定位/搜索 SDK，免重新打包
        applyAmapKey(Repository.amapKey)

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
        binding.nearbySetKeyBtn.setOnClickListener { showKeyDialog() }

        // 预置历史加油站，定位期间也可见，失败时作为兜底
        loadHistoryIntoList()
        checkPermissionAndLocate()
    }

    /** B5：App 内配置高德 Key，保存后即时生效（重新走定位流程） */
    private fun showKeyDialog() {
        val input = EditText(this).apply {
            hint = "粘贴高德 Android 平台 Key"
            setText(Repository.amapKey)
            setSingleLine(true)
            val p = resources.getDimensionPixelSize(android.R.dimen.app_icon_size) / 4
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this)
            .setTitle("设置高德地图 Key")
            .setMessage("到 console.amap.com 免费申请 Android 平台 Key（包名 com.youjixingtu.app），填入即可启用附近加油站，无需重新打包。")
            .setView(input)
            .setPositiveButton("保存并重试") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                Repository.amapKey = key
                applyAmapKey(key)
                // 关键：必须销毁并重建 SDK 客户端实例，否则旧实例仍持有旧 key（鉴权一直失败）
                resetSdkClients()
                if (key.isBlank()) {
                    toast("已清除 Key")
                } else {
                    toast("Key 已保存，已用新 Key 重新初始化，正在重试…", long = true)
                }
                // 重置列表与状态后重走定位
                settled = false
                loadHistoryIntoList()
                checkPermissionAndLocate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 统一注入高德 Key：定位 SDK（静态）与搜索 SDK（单例）都要设，且要在创建客户端前调用 */
    private fun applyAmapKey(key: String) {
        if (key.isBlank()) return
        runCatching { AMapLocationClient.setApiKey(key) }
        runCatching { ServiceSettings.getInstance().setApiKey(key) }
    }

    /** 销毁并清空 SDK 客户端，确保下次用当前 key 重新构造实例 */
    private fun resetSdkClients() {
        try {
            locationClient?.stopLocation()
            locationClient?.onDestroy()
        } catch (_: Exception) {
        }
        locationClient = null
        try {
            search?.setOnPoiSearchListener(null)
        } catch (_: Exception) {
        }
        search = null
    }

    private fun checkPermissionAndLocate() {
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
        val keyReady = Repository.amapKey.isNotBlank()
        binding.nearbyStatusText.text = if (keyReady) "正在获取当前位置…（Key 已配置）" else "正在获取当前位置…（未配置 Key，点右下角「设置Key」可启用附近搜索）"
        try {
            if (locationClient == null) {
                // 每次构造客户端前再注入一次 key，确保实例拿到的是最新值
                applyAmapKey(Repository.amapKey)
                locationClient = AMapLocationClient(applicationContext).apply {
                    setLocationListener(AMapLocationListener { location ->
                        if (location != null && location.errorCode == 0) {
                            // C3：缓存成功定位，供后续失败时兜底
                            Repository.lastLocation = Triple(location.latitude, location.longitude, System.currentTimeMillis())
                            searchNearby(location.latitude, location.longitude)
                        } else {
                            // 诊断：把错误码带出来，1001/1002 多为 Key 或签名问题
                            val code = location?.errorCode ?: -1
                            val reason = location?.errorInfo ?: "定位失败"
                            val keyHint = if (code == 1001 || code == 1002) "（多半是 Key 无效或 SHA1/包名不匹配）" else ""
                            // C3：定位失败则用上次成功坐标兜底搜索
                            val cached = Repository.lastLocation
                            if (cached != null) {
                                binding.nearbyStatusText.text = "定位失败[$code]$keyHint：$reason，试用上次位置搜索…"
                                searchNearby(cached.first, cached.second)
                            } else {
                                fallbackToHistory("定位失败[$code]$keyHint：$reason，可手动输入或从历史中选择")
                            }
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
            // 搜索 SDK 走独立单例，构造 PoiSearch 前再注入一次 key
            applyAmapKey(Repository.amapKey)
            if (Repository.amapKey.isBlank()) {
                fallbackToHistory("未配置高德 Key，无法搜索附近加油站；可从历史中选择或手动输入")
                return
            }
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
                            val keyHint = if (rCode == 1002 || rCode == 100) "（多半是 Key 无效或未开通搜索服务）" else ""
                            fallbackToHistory("加油站搜索失败[$rCode]$keyHint，可手动输入或从历史中选择")
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
