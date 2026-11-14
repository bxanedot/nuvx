package com.bxane.nuvx.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bxane.nuvx.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File

object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        val notes: String?,
    )

    private const val CACHE_SUBDIR = "updates"

    private const val RELEASES_URL =
        "https://api.github.com/repos/bxanedot/nuvx/releases?per_page=100"

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    @Volatile
    private var downloadCancelled = false

    suspend fun check() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(RELEASES_URL).build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            } ?: return@runCatching
            val releases = json.parseToJsonElement(body) as? JsonArray ?: return@runCatching
            _available.value = releases.mapNotNull { it as? JsonObject }
                .mapNotNull(::releaseCandidate)
                .filter {
                    isNewerRelease(
                        it.version,
                        it.tag,
                        it.publishedAt,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.NUVX_RELEASE_TAG,
                        BuildConfig.NUVX_BUILD_TIME,
                    )
                }
                .maxByOrNull { it.publishedAt }
                ?.let { UpdateInfo(it.version, it.releaseUrl, it.apkUrl, it.notes) }
        }
    }

    private data class ReleaseCandidate(
        val version: String,
        val tag: String,
        val publishedAt: String,
        val releaseUrl: String,
        val apkUrl: String,
        val notes: String?,
    )

    private fun releaseCandidate(release: JsonObject): ReleaseCandidate? {
        if (release["draft"]?.jsonPrimitive?.booleanOrNull != false) return null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = release["name"]?.jsonPrimitive?.contentOrNull
        val version = if (tag.startsWith("nightly.")) {
            title?.removePrefix("nuvx ")?.takeIf { it != title }
        } else {
            tag.removePrefix("v")
        } ?: return null
        if (!version.matches(Regex("\\d+\\.\\d+\\.\\d+(-nightly\\.\\d+\\.\\d+)?"))) return null
        val publishedAt = release["published_at"]?.jsonPrimitive?.contentOrNull ?: return null
        val apkUrl = apkAssetUrl(release) ?: return null
        return ReleaseCandidate(version, tag, publishedAt, url, apkUrl, release["body"]?.jsonPrimitive?.contentOrNull)
    }

    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

    private fun apkAssetUrl(release: JsonObject): String? = runCatching {
        val apks = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val ours = name.startsWith(BuildConfig.UPDATE_APK_PREFIX, ignoreCase = true) &&
                    name.endsWith(".apk", ignoreCase = true) &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
                if (ours) name.removeSuffix(".apk").removeSuffix(".APK") to url else null
            }
            .orEmpty()
        selectApkAssetUrl(apks, BuildConfig.NUVX_DISTRIBUTION, Build.SUPPORTED_ABIS.toList())
    }.getOrNull()

    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _available.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        downloadCancelled = false
        _download.value = DownloadState.Downloading(0f)

        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "nuvx-${info.version}.apk")

            val request = Request.Builder().url(url).build()
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            if (downloadCancelled) {
                                _download.value = DownloadState.Idle
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            total?.let {
                                _download.value =
                                    DownloadState.Downloading((readTotal.toFloat() / it).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        downloadCancelled = true
    }

    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (opened) return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
        )
    }

}

internal fun isNewerRelease(
    candidateVersion: String,
    candidateTag: String,
    candidatePublishedAt: String,
    currentVersion: String,
    currentTag: String,
    currentBuildTime: String,
): Boolean {
    val candidateParts = candidateVersion.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
    val currentParts = currentVersion.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
    for (index in 0 until maxOf(candidateParts.size, currentParts.size)) {
        val candidate = candidateParts.getOrElse(index) { 0L }
        val current = currentParts.getOrElse(index) { 0L }
        if (candidate != current) return candidate > current
    }
    if (currentTag.startsWith("nightly.") && candidateTag.startsWith("v")) return true
    return candidateTag != currentTag && (currentBuildTime.isEmpty() || candidatePublishedAt > currentBuildTime)
}

internal fun selectApkAssetUrl(
    assets: List<Pair<String, String>>,
    distribution: String,
    supportedAbis: List<String>,
): String? = assets.firstOrNull { (name, _) -> name.endsWith("-$distribution") }?.second
    ?: if (distribution == "gms") {
        supportedAbis.firstNotNullOfOrNull { abi ->
            assets.firstOrNull { (name, _) -> name.endsWith("_$abi") }?.second
        } ?: assets.firstOrNull { (name, _) -> '_' !in name }?.second
    } else {
        null
    }
