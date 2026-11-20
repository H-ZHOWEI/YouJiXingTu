package com.youjixingtu.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.youjixingtu.app.model.CameraPhoto
import com.youjixingtu.app.model.FuelRecord
import com.youjixingtu.app.util.CryptoUtil

/**
 * 本地仓库：沿用旧版 localStorage 的键名，方便数据迁移。
 * v1.7：数据透明加密（Keystore AES-GCM）、同步落盘、内存缓存、备份恢复不再递归。
 */
object Repository {

    private const val PREF_NAME = "youjixingtu_prefs"
    const val KEY_RECORDS = "fuelRecords"
    const val KEY_BACKUP = "fuelRecords_backup"
    const val KEY_TANK_CAPACITY = "tankCapacity"
    const val KEY_LAST_FUEL_TYPE = "lastFuelType"
    const val KEY_LAST_GAS_STATION = "lastGasStation"
    const val KEY_CAMERA_PHOTOS = "cameraPhotos"
    const val KEY_AMAP_KEY = "amapApiKey"
    const val KEY_LOC_LAT = "lastLocLat"
    const val KEY_LOC_LNG = "lastLocLng"
    const val KEY_LOC_TIME = "lastLocTime"
    const val KEY_LOW_FUEL_NOTICE_DAY = "lowFuelNoticeDay"
    const val KEY_GAUGE_TOTAL = "gaugeTotal"
    const val KEY_BT_AUTO_ENABLED = "btAutoStartEnabled"
    const val KEY_BT_DEVICE_NAME = "btAutoStartDeviceName"
    const val KEY_GAUGE_CURRENT = "gaugeCurrent"
    const val KEY_GAUGE_CURRENT_DATE = "gaugeCurrentDate"

    private var appContext: Context? = null
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    // 内存缓存：避免每次切页都重新解密 + Gson 全量解析（D3）
    private var cachedRecordsRaw: String? = null
    private var cachedRecords: MutableList<FuelRecord>? = null
    private var cachedPhotosRaw: String? = null
    private var cachedPhotos: MutableList<CameraPhoto>? = null

    private val prefs: SharedPreferences
        get() = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?: error("Repository 未初始化，请先在 Application.onCreate 调用 Repository.init(this)")

    fun init(context: Context) {
        appContext = context.applicationContext
        CryptoUtil.init(context.applicationContext)
    }

    private fun tryParseRecords(stored: String): List<FuelRecord>? = try {
        val type = object : TypeToken<List<FuelRecord>>() {}.type
        gson.fromJson<List<FuelRecord>>(CryptoUtil.decrypt(stored), type)
    } catch (e: Exception) {
        null
    }

    fun loadRecords(): MutableList<FuelRecord> {
        val stored = prefs.getString(KEY_RECORDS, null)
        if (stored.isNullOrBlank()) return mutableListOf()
        if (stored == cachedRecordsRaw && cachedRecords != null) return cachedRecords!!.toMutableList()

        var parsed = tryParseRecords(stored)
        var usedRaw = stored
        // 主数据损坏：一次性尝试备份，校验通过才写回，绝不递归（B2）
        if (parsed == null) {
            val backup = prefs.getString(KEY_BACKUP, null)
            if (!backup.isNullOrBlank() && backup != stored) {
                val restored = tryParseRecords(backup)
                if (restored != null) {
                    usedRaw = backup
                    prefs.edit().putString(KEY_RECORDS, backup).commit()
                    parsed = restored
                }
            }
        }
        if (parsed == null) return mutableListOf()

        val valid = parsed.filter { it.id.isNotBlank() && it.date.isNotBlank() }.toMutableList()
        if (valid.size != parsed.size) {
            saveRecords(valid) // 顺手清理脏记录（saveRecords 内部会刷新缓存）
            return valid
        }
        cachedRecordsRaw = usedRaw
        cachedRecords = valid
        return valid.toMutableList()
    }

    fun saveRecords(records: List<FuelRecord>): Boolean {
        return try {
            val encrypted = CryptoUtil.encrypt(gson.toJson(records))
            val editor = prefs.edit()
            prefs.getString(KEY_RECORDS, null)?.takeIf { it.isNotBlank() }?.let {
                editor.putString(KEY_BACKUP, it)
            }
            editor.putString(KEY_RECORDS, encrypted)
            if (!editor.commit()) return false // 同步落盘，让失败提示真正有效（B1）
            cachedRecordsRaw = encrypted
            cachedRecords = records.toMutableList()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 显式备份当前数据到 KEY_BACKUP */
    fun backupData() {
        val current = prefs.getString(KEY_RECORDS, null)
        if (!current.isNullOrBlank()) prefs.edit().putString(KEY_BACKUP, current).commit()
    }

    /** 从备份恢复；返回是否成功恢复（不再递归、不再返回原始字符串） */
    fun restoreBackup(): Boolean {
        val backup = prefs.getString(KEY_BACKUP, null) ?: return false
        if (tryParseRecords(backup) == null) return false
        val ok = prefs.edit().putString(KEY_RECORDS, backup).commit()
        if (ok) {
            cachedRecordsRaw = backup
            cachedRecords = null
        }
        return ok
    }

    fun recordsJson(): String = gson.toJson(loadRecords())

    fun parseRecordsJson(json: String): List<FuelRecord> {
        val type = object : TypeToken<List<FuelRecord>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    var tankCapacity: Int
        get() = prefs.getInt(KEY_TANK_CAPACITY, 50)
        set(value) { prefs.edit().putInt(KEY_TANK_CAPACITY, value).apply() }

    var lastFuelType: String
        get() = prefs.getString(KEY_LAST_FUEL_TYPE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_FUEL_TYPE, value).apply() }

    /** 最近一次加油的加油站名称；未记录时从最新有站的记录派生 */
    var lastGasStation: String
        get() {
            val saved = prefs.getString(KEY_LAST_GAS_STATION, "") ?: ""
            if (saved.isNotBlank()) return saved
            return loadRecords()
                .sortedByDescending { it.timestamp }
                .firstOrNull { it.refuelData?.gasStation?.isNotBlank() == true }
                ?.refuelData?.gasStation ?: ""
        }
        set(value) {
            if (value.isNotBlank()) prefs.edit().putString(KEY_LAST_GAS_STATION, value).apply()
        }

    /** 用户自行配置的高德 Key（B5），为空则使用 Manifest 占位符 */
    var amapKey: String
        get() = prefs.getString(KEY_AMAP_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_AMAP_KEY, value.trim()).apply() }

    /** 上次成功定位的坐标与时间，用于定位失败时兜底搜索（C3） */
    var lastLocation: Triple<Double, Double, Long>?
        get() {
            val lat = prefs.getFloat(KEY_LOC_LAT, 0f).toDouble()
            val lng = prefs.getFloat(KEY_LOC_LNG, 0f).toDouble()
            val time = prefs.getLong(KEY_LOC_TIME, 0L)
            return if (lat != 0.0 && lng != 0.0 && time > 0) Triple(lat, lng, time) else null
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_LOC_LAT).remove(KEY_LOC_LNG).remove(KEY_LOC_TIME).apply()
            } else {
                editor.putFloat(KEY_LOC_LAT, value.first.toFloat())
                    .putFloat(KEY_LOC_LNG, value.second.toFloat())
                    .putLong(KEY_LOC_TIME, value.third).apply()
            }
        }

    /** I8：低油量当天提醒去重，避免每次进入页面都弹 */
    var lowFuelNoticeDay: String
        get() = prefs.getString(KEY_LOW_FUEL_NOTICE_DAY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOW_FUEL_NOTICE_DAY, value).apply() }

    /** v1.14：油表总格数（默认 12），用于格数法估算 */
    var gaugeTotal: Int
        get() = prefs.getInt(KEY_GAUGE_TOTAL, 12).coerceIn(2, 30)
        set(value) { prefs.edit().putInt(KEY_GAUGE_TOTAL, value.coerceIn(2, 30)).apply() }

    /** v1.14：用户最近一次单独更新的「当前油表格数」；null 表示用加油记录里的 gaugeAfter 推算 */
    var gaugeCurrent: Double?
        get() {
            val v = prefs.getFloat(KEY_GAUGE_CURRENT, -1f)
            return if (v >= 0f) v.toDouble() else null
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove(KEY_GAUGE_CURRENT).remove(KEY_GAUGE_CURRENT_DATE)
            else e.putFloat(KEY_GAUGE_CURRENT, value.toFloat())
                .putString(KEY_GAUGE_CURRENT_DATE, com.youjixingtu.app.core.Analytics.formatDateTime(java.util.Date()))
            e.apply()
        }

    /** 当前格数更新时间（yyyy-MM-dd'T'HH:mm），用于判断是否需要按里程继续扣减 */
    var gaugeCurrentDate: String
        get() = prefs.getString(KEY_GAUGE_CURRENT_DATE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_GAUGE_CURRENT_DATE, value).apply() }

    /** v1.15：车载蓝牙自动启动开关 */
    var btAutoStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_BT_AUTO_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_BT_AUTO_ENABLED, value).apply() }

    /** v1.15：要匹配的车载蓝牙名称（如「蓝牙A」），空表示未配置 */
    var btDeviceName: String
        get() = prefs.getString(KEY_BT_DEVICE_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_BT_DEVICE_NAME, value.trim()).apply() }

    fun loadPhotos(): MutableList<CameraPhoto> {
        val stored = prefs.getString(KEY_CAMERA_PHOTOS, null)
        if (stored.isNullOrBlank()) return mutableListOf()
        if (stored == cachedPhotosRaw && cachedPhotos != null) return cachedPhotos!!.toMutableList()
        return try {
            val type = object : TypeToken<List<CameraPhoto>>() {}.type
            val parsed: MutableList<CameraPhoto> =
                gson.fromJson(CryptoUtil.decrypt(stored), type) ?: mutableListOf()
            cachedPhotosRaw = stored
            cachedPhotos = parsed
            parsed
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePhotos(photos: List<CameraPhoto>) {
        val encrypted = CryptoUtil.encrypt(gson.toJson(photos))
        if (prefs.edit().putString(KEY_CAMERA_PHOTOS, encrypted).commit()) {
            cachedPhotosRaw = encrypted
            cachedPhotos = photos.toMutableList()
        }
    }

    /** 清除所有缓存（导入后调用，确保下次读取落盘数据） */
    fun invalidateCache() {
        cachedRecordsRaw = null
        cachedRecords = null
        cachedPhotosRaw = null
        cachedPhotos = null
    }
}
