package com.youjixingtu.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：GitHub Release 检查 + 多镜像下载 + 安装。
 * 发布仓库：https://github.com/H-ZHOWEI/YouJiXingTu
 */
object UpdateManager {

    private const val REPO = "H-ZHOWEI/YouJiXingTu"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    const val PAGE_URL = "https://github.com/$REPO/releases/latest"

    private val CHECK_URLS = listOf(
        API_LATEST,
        "https://ghproxy.net/$API_LATEST",
        "https://gh-proxy.com/$API_LATEST",
        "https://gh.api.99988866.xyz/$API_LATEST"
    )

    data class Info(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val pageUrl: String
    )

    private data class ReleaseDto(
        @SerializedName("tag_name") val tagName: String? = null,
        @SerializedName("body") val body: String? = null,
        @SerializedName("html_url") val htmlUrl: String? = null,
        @SerializedName("assets") val assets: List<AssetDto>? = null
    )

    private data class AssetDto(
        @SerializedName("name") val name: String? = null,
        @SerializedName("browser_download_url") val url: String? = null,
        @SerializedName("size") val size: Long = 0L
    )

    private val gson = Gson()
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val checkClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** 检查结果：区分「有新版本」「已是最新」「检查失败」，避免失败被误报成已是最新 */
    sealed class CheckResult {
        data class NewVersion(val info: Info) : CheckResult()
        object UpToDate : CheckResult()
        data class Failed(val reason: String) : CheckResult()
    }

    /** 启动检查 3 次求稳，手动检查 1 次求快；绝不写冷却时间 */
    suspend fun checkUpdateDetailed(context: Context, attempts: Int = 3): CheckResult = withContext(Dispatchers.IO) {
        val current = currentVersion(context)
        val n = attempts.coerceIn(1, CHECK_URLS.size * 2)
        var lastError = "网络连接失败"
        var reachedServer = false
        for (i in 0 until n) {
            try {
                val info = fetchRelease(CHECK_URLS[i % CHECK_URLS.size], current)
                // 能解析到 Release（含已是最新）说明服务器可达
                reachedServer = true
                return@withContext if (info != null) CheckResult.NewVersion(info) else CheckResult.UpToDate
            } catch (e: Exception) {
                lastError = when {
                    e.message?.contains("HTTP 404") == true -> "更新仓库不存在"
                    e.message?.contains("HTTP") == true -> "服务器返回错误（${e.message}）"
                    e.message?.contains("not json") == true || e.message?.contains("bad json") == true -> "更新服务返回异常"
                    else -> "网络连接失败"
                }
                if (i < n - 1) delay(800L)
            }
        }
        CheckResult.Failed(if (reachedServer) "检查更新失败，请稍后重试" else lastError)
    }

    private fun fetchRelease(url: String, current: String): Info? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YouJiXingTu-Updater")
            .get()
            .build()
        checkClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            if (!body.trimStart().startsWith("{")) throw IllegalStateException("not json")
            val dto = gson.fromJson(body, ReleaseDto::class.java) ?: throw IllegalStateException("bad json")
            val latest = (dto.tagName ?: "").trim().removePrefix("v").removePrefix("V")
            if (latest.isBlank() || compareVersion(latest, current) <= 0) return null
            val asset = dto.assets?.firstOrNull {
                it.name?.endsWith(".apk", ignoreCase = true) == true && !it.url.isNullOrBlank()
            } ?: return null
            return Info(latest, dto.body?.trim().orEmpty(), asset.url!!, asset.size, dto.htmlUrl ?: PAGE_URL)
        }
    }

    /** 数字分段比较：1.10 > 1.9 */
    fun compareVersion(a: String, b: String): Int {
        fun parts(s: String) = s.split('.', '-', '_')
            .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun candidateUrls(original: String): List<String> = listOf(
        original,
        "https://ghproxy.net/$original",
        "https://mirror.ghproxy.com/$original",
        "https://gh.api.99988866.xyz/$original"
    ).distinct()

    suspend fun download(context: Context, info: Info): File? = withContext(Dispatchers.IO) {
        try {
            _progress.value = 0f
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val target = File(dir, "YouJiXingTu-v${info.version}-release.apk")
            if (target.exists() && info.sizeBytes > 0 && target.length() == info.sizeBytes) {
                _progress.value = 1f
                return@withContext target
            }
            for (url in candidateUrls(info.apkUrl)) {
                repeat(2) {
                    try {
                        if (doDownload(url, target, info.sizeBytes)) {
                            _progress.value = 1f
                            return@withContext target
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            _progress.value = -1f
            null
        } catch (_: Exception) {
            _progress.value = -1f
            null
        }
    }

    private fun doDownload(url: String, target: File, expectedSize: Long): Boolean {
        val request = Request.Builder().url(url).get().build()
        val response = downloadClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return false
        }
        val total = response.body?.contentLength()?.takeIf { it > 0 } ?: expectedSize
        var ok = false
        try {
            response.body?.byteStream()?.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } > 0) {
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) _progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                    out.flush()
                }
            }
            ok = target.length() >= 1024L && (expectedSize <= 0L || target.length() == expectedSize)
        } catch (_: Exception) {
            ok = false
        } finally {
            if (!ok) try { target.delete() } catch (_: Exception) { }
        }
        return ok
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    fun install(context: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    private const val PREF_SKIP_VERSION = "update_skipped_version"

    fun isSkipped(context: Context, version: String): Boolean {
        val prefs = context.getSharedPreferences("youjixingtu_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_SKIP_VERSION, "") == version
    }

    fun skipVersion(context: Context, version: String) {
        context.getSharedPreferences("youjixingtu_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREF_SKIP_VERSION, version).apply()
    }
}
