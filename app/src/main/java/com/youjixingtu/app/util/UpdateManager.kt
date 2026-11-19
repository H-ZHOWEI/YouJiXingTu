package com.youjixingtu.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import java.io.FileOutputStream
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
    /** 下载进度 0f..1f；-1f 表示失败。单调不回退。 */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress
    /** 下载阶段文字（线路 x/4、正在校验等），供 UI 显示，避免进度回跳时用户困惑 */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

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
            // 下载走长读超时，但不用 120s（卡住时要能尽快切线路）
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // 关闭 OkHttp 内部重试，改用外层可控的断点续传
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

    /**
     * I14：从整篇 CHANGELOG 中只截取指定版本那一段（## vX.Y 到下一个 ## 之间），
     * 避免把整份更新记录塞进更新横幅。
     */
    fun extractVersionNotes(notes: String, version: String): String {
        if (notes.isBlank()) return ""
        val lines = notes.lineSequence().map { it.trimEnd() }.toList()
        val headerRegex = Regex("^#{1,4}\\s*v?${Regex.escape(version)}(?:\\s|$)", RegexOption.IGNORE_CASE)
        val start = lines.indexOfFirst { headerRegex.matches(it) }
        if (start < 0) return ""
        val body = mutableListOf<String>()
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (Regex("^#{1,2}\\s").matches(line.trimStart())) break
            body.add(line)
        }
        return body.joinToString("\n").trim()
            .lines().map { it.trim() }.filter { it.isNotBlank() }
            .take(12).joinToString(" ")
    }

    private fun candidateUrls(original: String): List<String> = listOf(
        // 国内可达性更好的镜像优先，直连放后面，减少卡在 GitHub 直连的概率
        "https://ghproxy.net/$original",
        "https://gh-proxy.com/$original",
        "https://mirror.ghproxy.com/$original",
        original,
        "https://gh.api.99988866.xyz/$original"
    ).distinct()

    private enum class DlResult { SUCCESS, PARTIAL, NO_PROGRESS, FAIL }

    /** APK 是 ZIP 包，文件头必须是 PK 魔数，用来挡住代理返回的 HTML 错误页 */
    private fun isApk(file: File): Boolean = try {
        file.inputStream().use { s -> s.read() == 'P'.code && s.read() == 'K'.code }
    } catch (_: Exception) {
        false
    }

    /**
     * 断点续传下载：每条线路失败后从已下载字节继续，进度单调不回退。
     * 相比旧实现（每条线路从头下、分母取 contentLength）解决了：
     *  1) 切线路时百分比回跳（2%→23%→0%）；
     *  2) 代理/gzip 导致 contentLength 与实际字节不符、进度卡死；
     *  3) 单线路卡住时 8 次尝试 × 120s 读超时，长时间无响应。
     */
    suspend fun download(context: Context, info: Info): File? = withContext(Dispatchers.IO) {
        _progress.value = 0f
        _status.value = "准备下载…"
        try {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val target = File(dir, "YouJiXingTu-v${info.version}-release.apk")

            // 已完整下载且校验通过 → 直接复用，避免重复下载
            if (target.exists() && info.sizeBytes > 0 &&
                target.length() == info.sizeBytes && isApk(target)
            ) {
                _progress.value = 1f
                _status.value = "下载完成"
                return@withContext target
            }

            val urls = candidateUrls(info.apkUrl)
            val maxAttempts = urls.size * 2              // 每条线路最多 2 次
            val deadline = SystemClock.elapsedRealtime() + 5 * 60 * 1000L // 整体 5 分钟硬上限
            var attempt = 0

            while (attempt < maxAttempts && SystemClock.elapsedRealtime() < deadline) {
                val url = urls[attempt % urls.size]
                val lineNo = (attempt % urls.size) + 1
                val isRetry = attempt >= urls.size
                attempt++
                _status.value = "线路 $lineNo/${urls.size}${if (isRetry) " · 重试" else ""}"
                val result = try {
                    doDownload(url, target, info.sizeBytes)
                } catch (_: Exception) {
                    DlResult.FAIL
                }
                if (result == DlResult.SUCCESS) {
                    _progress.value = 1f
                    _status.value = "下载完成"
                    return@withContext target
                }
            }

            _progress.value = -1f
            _status.value = "下载失败"
            null
        } catch (_: Exception) {
            _progress.value = -1f
            _status.value = "下载失败"
            null
        }
    }

    /** 单次线路下载，支持 HTTP Range 断点续传；进度分母用 Release 的可信 size */
    private fun doDownload(url: String, target: File, expectedSize: Long): DlResult {
        // 已有长度不小于期望大小却没通过校验 → 残次/错误内容，删掉从头来
        if (expectedSize > 0 && target.exists() && target.length() >= expectedSize) {
            try { target.delete() } catch (_: Exception) { }
        }

        val existing = if (target.exists()) target.length() else 0L
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity") // 禁 gzip：保证读到的字节长度与 contentLength 一致
            .header("User-Agent", "YouJiXingTu-Updater")
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .get()
            .build()

        val response = downloadClient.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            return DlResult.FAIL
        }
        val body = response.body ?: run { response.close(); return DlResult.FAIL }

        // 206 = 服务器接受 Range，续传；200 = 忽略 Range，从头写
        val append = response.code == 206 && existing > 0
        val base = if (append) existing else 0L
        if (!append && target.exists()) {
            try { target.delete() } catch (_: Exception) { }
        }

        val total = if (expectedSize > 0) expectedSize else base + body.contentLength()
        var done = base
        var readThisRound = 0L

        try {
            body.byteStream().use { input ->
                FileOutputStream(target, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        out.write(buffer, 0, read)
                        done += read
                        readThisRound += read
                        if (total > 0) {
                            val p = (done.toFloat() / total).coerceIn(0f, 1f)
                            // 单调钳制：切线路/重试时进度绝不回退，避免百分比乱跳
                            if (p > _progress.value) _progress.value = p
                        }
                    }
                    out.flush()
                }
            }
        } catch (_: Exception) {
            return if (readThisRound > 0L) DlResult.PARTIAL else DlResult.NO_PROGRESS
        } finally {
            response.close()
        }

        if (readThisRound <= 0L && base <= 0L) return DlResult.NO_PROGRESS

        val finalSize = target.length()
        // 没下满 → 记为部分进度，下一条线路从断点续传
        if (expectedSize > 0 && finalSize < expectedSize) return DlResult.PARTIAL
        if (finalSize < 1024L || !isApk(target)) {
            try { target.delete() } catch (_: Exception) { }
            return DlResult.FAIL
        }
        return DlResult.SUCCESS
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
