package com.youjixingtu.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.youjixingtu.app.model.CameraPhoto
import com.youjixingtu.app.model.FuelRecord

/**
 * 本地仓库：沿用旧版 localStorage 的键名，方便数据迁移。
 */
object Repository {

    private const val PREF_NAME = "youjixingtu_prefs"
    const val KEY_RECORDS = "fuelRecords"
    const val KEY_BACKUP = "fuelRecords_backup"
    const val KEY_TANK_CAPACITY = "tankCapacity"
    const val KEY_LAST_FUEL_TYPE = "lastFuelType"
    const val KEY_LAST_GAS_STATION = "lastGasStation"
    const val KEY_CAMERA_PHOTOS = "cameraPhotos"

    private var appContext: Context? = null
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    private val prefs: SharedPreferences
        get() = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?: error("Repository 未初始化，请先在 Application.onCreate 调用 Repository.init(this)")

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun loadRecords(): MutableList<FuelRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<List<FuelRecord>>() {}.type
            val parsed: List<FuelRecord>? = gson.fromJson(raw, type)
            if (parsed == null) {
                restoreBackup()?.let { return loadRecords() }
                mutableListOf()
            } else {
                val valid = parsed.filter { it.id.isNotBlank() && it.date.isNotBlank() }.toMutableList()
                if (valid.size != parsed.size) saveRecords(valid)
                valid
            }
        } catch (e: Exception) {
            restoreBackup()?.let { return loadRecords() }
            mutableListOf()
        }
    }

    fun saveRecords(records: List<FuelRecord>): Boolean {
        return try {
            val current = prefs.getString(KEY_RECORDS, null)
            if (!current.isNullOrBlank()) prefs.edit().putString(KEY_BACKUP, current).apply()
            prefs.edit().putString(KEY_RECORDS, gson.toJson(records)).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun backupData() {
        val current = prefs.getString(KEY_RECORDS, null)
        if (!current.isNullOrBlank()) prefs.edit().putString(KEY_BACKUP, current).apply()
    }

    fun restoreBackup(): String? {
        val backup = prefs.getString(KEY_BACKUP, null) ?: return null
        prefs.edit().putString(KEY_RECORDS, backup).apply()
        return backup
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

    fun loadPhotos(): MutableList<CameraPhoto> {
        val raw = prefs.getString(KEY_CAMERA_PHOTOS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<List<CameraPhoto>>() {}.type
            gson.fromJson(raw, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePhotos(photos: List<CameraPhoto>) {
        prefs.edit().putString(KEY_CAMERA_PHOTOS, gson.toJson(photos)).apply()
    }
}
