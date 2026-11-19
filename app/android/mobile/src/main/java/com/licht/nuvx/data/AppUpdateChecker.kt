package com.licht.nuvx.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.licht.nuvx.BuildConfig
import com.licht.nuvx.data.settings.AppSettings
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
        "https://api.github.com/repos/lylicht/nuvx/releases?per_page=100"

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
            _available.value = selectUpdate(
                releases.mapNotNull { it as? JsonObject }.mapNotNull(::releaseCandidate),
                BuildConfig.VERSION_NAME,
                BuildConfig.NUVX_RELEASE_TAG,
                BuildConfig.NUVX_BUILD_TIME,
                allowNightly = AppSettings.nightlyUpdates.value,
            )?.let { UpdateInfo(it.version, it.releaseUrl, it.apkUrl, it.notes) }
        }
    }

    internal data class ReleaseCandidate(
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
        if (isOldBuild(title, tag, release["prerelease"]?.jsonPrimitive?.booleanOrNull == true)) return null
        val apk = apkAsset(release) ?: return null
        val version = if (tag.startsWith("nightly.")) {
            versionFromApkAsset(apk.first, BuildConfig.UPDATE_APK_PREFIX)
                ?: title?.removePrefix("nuvx ")?.takeIf { it != title }
        } else {
            tag.removePrefix("v")
        } ?: return null
        if (!version.matches(Regex("\\d+\\.\\d+\\.\\d+(-nightly\\.\\d+\\.\\d+)?"))) return null
        val publishedAt = release["published_at"]?.jsonPrimitive?.contentOrNull ?: return null
        return ReleaseCandidate(version, tag, publishedAt, url, apk.second, release["body"]?.jsonPrimitive?.contentOrNull)
    }

    private fun apkAsset(release: JsonObject): Pair<String, String>? = runCatching {
        val assets = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (name.startsWith(BuildConfig.UPDATE_APK_PREFIX, ignoreCase = true) &&
                    name.endsWith(".apk", ignoreCase = true) &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
                ) name.removeSuffix(".apk").removeSuffix(".APK") to url else null
            }
            .orEmpty()
        val selected = selectApkAssetUrl(assets, BuildConfig.NUVX_DISTRIBUTION, Build.SUPPORTED_ABIS.toList())
        assets.firstOrNull { it.second == selected }
    }.getOrNull()

    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

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

// Releases from before the licht line stay on GitHub as pre-releases named "old". They are other apps
// with other package IDs and higher version numbers, so no updater may ever offer them.
internal fun isOldBuild(name: String?, tag: String, prerelease: Boolean): Boolean =
    name?.contains(Regex("\\bold\\b", RegexOption.IGNORE_CASE)) == true ||
        prerelease && !tag.startsWith("nightly.")

// The best release to offer: a stable release beats any nightly, then the higher version, then the later nightly build.
internal fun selectUpdate(
    candidates: List<AppUpdateChecker.ReleaseCandidate>,
    currentVersion: String,
    currentTag: String,
    currentBuildTime: String,
    allowNightly: Boolean,
): AppUpdateChecker.ReleaseCandidate? = candidates
    .filter { isNewerRelease(it.version, it.tag, it.publishedAt, currentVersion, currentTag, currentBuildTime, allowNightly) }
    .maxWithOrNull(
        compareBy<AppUpdateChecker.ReleaseCandidate> { it.tag.startsWith("v") }
            .thenComparator { a, b -> compareCore(a.version, b.version) }
            .thenBy { nightlyBuild(it.tag)?.first ?: 0L }
            .thenBy { nightlyBuild(it.tag)?.second ?: 0L }
            .thenBy { it.publishedAt },
    )

private fun compareCore(a: String, b: String): Int {
    val left = a.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
    val right = b.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
    for (index in 0 until maxOf(left.size, right.size)) {
        val order = left.getOrElse(index) { 0L }.compareTo(right.getOrElse(index) { 0L })
        if (order != 0) return order
    }
    return 0
}

internal fun isNewerRelease(
    candidateVersion: String,
    candidateTag: String,
    candidatePublishedAt: String,
    currentVersion: String,
    currentTag: String,
    currentBuildTime: String,
    allowNightly: Boolean = currentTag.startsWith("nightly."),
): Boolean {
    if (candidateTag.startsWith("nightly.") && !allowNightly) return false
    val candidateParts = candidateVersion.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
    val currentParts = currentVersion.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
    for (index in 0 until maxOf(candidateParts.size, currentParts.size)) {
        val candidate = candidateParts.getOrElse(index) { 0L }
        val current = currentParts.getOrElse(index) { 0L }
        if (candidate != current) return candidate > current
    }
    if (currentTag.startsWith("nightly.")) {
        // Stable -> nightly -> nightly 2 -> stable 2: the stable build of the version these nightlies led up to
        // comes next, with nightlies on or off.
        if (!candidateTag.startsWith("nightly.")) return currentBuildTime.isNotEmpty() && candidatePublishedAt > currentBuildTime
        val candidateBuild = nightlyBuild(candidateTag) ?: return false
        val currentBuild = nightlyBuild(currentTag) ?: return false
        return candidateBuild.first > currentBuild.first ||
            candidateBuild.first == currentBuild.first && candidateBuild.second > currentBuild.second
    }
    return candidateTag != currentTag && (currentBuildTime.isEmpty() || candidatePublishedAt > currentBuildTime)
}

internal fun versionFromApkAsset(name: String, prefix: String): String? {
    if (!name.startsWith(prefix, ignoreCase = true)) return null
    val suffix = name.drop(prefix.length).removeSuffix(".apk").removeSuffix(".APK")
    return Regex("^\\d+\\.\\d+\\.\\d+(?:-nightly\\.\\d+\\.\\d+)?(?=-|$)")
        .find(suffix)?.value
}

private fun nightlyBuild(tag: String): Pair<Long, Long>? {
    val parts = tag.removePrefix("nightly.").split('.')
    if (parts.size != 2) return null
    return (parts[0].toLongOrNull() ?: return null) to (parts[1].toLongOrNull() ?: return null)
}

internal fun selectApkAssetUrl(
    assets: List<Pair<String, String>>,
    distribution: String,
    supportedAbis: List<String>,
): String? {
    if (distribution == "single") {
        return assets.firstOrNull { (name, _) ->
            !name.endsWith("-gms") && !name.endsWith("-foss") &&
                !Regex("_(armeabi-v7a|arm64-v8a|x86_64)$").containsMatchIn(name)
        }?.second
    }
    return assets.firstOrNull { (name, _) -> name.endsWith("-$distribution") }?.second
        ?: if (distribution == "gms") {
        supportedAbis.firstNotNullOfOrNull { abi ->
            assets.firstOrNull { (name, _) -> name.endsWith("_$abi") }?.second
        } ?: assets.firstOrNull { (name, _) -> '_' !in name }?.second
    } else {
        null
    }
}
