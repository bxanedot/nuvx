package com.bxane.nuvx.data.webdav

import com.bxane.nuvx.data.http.Http
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.remote.EmbeddedArt
import com.bxane.nuvx.data.remote.RemoteArtworkStore
import com.bxane.nuvx.data.remote.WebDavConfig
import com.bxane.nuvx.data.remote.WebDavException
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSData


























object WebDavBridge {




    fun url(): String = AppSettings.webDavUrl.value

    fun username(): String = AppSettings.webDavUsername.value









    fun hasPassword(): Boolean = AppSettings.webDavPassword.value.isNotEmpty()


    fun isConfigured(): Boolean = WebDavRepository.isConfigured()












    fun save(url: String, username: String, password: String?) {
        val before = AppSettings.webDavUrl.value
        AppSettings.setWebDavUrl(url)
        AppSettings.setWebDavUsername(username)
        if (password != null) AppSettings.setWebDavPassword(password)
        if (AppSettings.webDavUrl.value != before) {
            scope.launch { RemoteArtworkStore.clear() }
        }
    }


    fun forget() {
        AppSettings.clearWebDav()
        scope.launch { RemoteArtworkStore.clear() }
    }









    @Throws(WebDavException::class, CancellationException::class)
    suspend fun test(url: String, username: String, password: String?): String {
        val failure = WebDavRepository.testConnection(
            url = url,
            username = username,
            password = password ?: storedPassword(),
        ) ?: return url
        throw WebDavException(failure.message())
    }










    @Throws(WebDavException::class, CancellationException::class)
    suspend fun library(): List<Song> = WebDavRepository.getSongs()


















    @Throws(WebDavException::class, CancellationException::class)
    suspend fun coverImage(fileUrl: String): CoverImage? {
        val header = WebDavAuth.headerFor(fileUrl)
        val response = try {
            Http.getBytesRaw(
                fileUrl,
                if (header == null) emptyMap() else mapOf("Authorization" to header),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw WebDavException(e.message ?: "Could not fetch the cover")
        }
        if (response.status !in 200..299) return null
        val bytes = response.body ?: return null
        if (bytes.isEmpty()) return null
        return CoverImage(bytes = bytes.asNSData(), mime = imageMime(fileUrl, bytes))
    }








    @Throws(WebDavException::class, CancellationException::class)
    suspend fun embeddedCover(fileUrl: String): CoverImage? {
        val picture = RemoteArtworkStore.resolveArt(fileUrl, WebDavAuth.headerFor(fileUrl)) ?: return null
        return CoverImage(bytes = picture.bytes.asNSData(), mime = picture.mime)
    }







    fun playbackHeaders(fileUrl: String): Map<String, String> {
        val header = WebDavAuth.headerFor(fileUrl) ?: return emptyMap()
        return mapOf("Authorization" to header)
    }


    fun isRemoteId(videoId: String): Boolean = WebDavConfig.isWebDavId(videoId)


    fun supportsUploadExtension(fileExtension: String): Boolean =
        fileExtension.lowercase().trimStart('.') in WebDavConfig.audioExtensions


    fun uploadFileName(title: String, artist: String, fileExtension: String): String =
        WebDavConfig.uploadFileName(title, artist, fileExtension)


    fun uploadFileUrl(fileName: String): String = WebDavConfig.joinUrl(url(), fileName)


    fun uploadAuthorizationHeader(fileUrl: String): String? = WebDavAuth.headerFor(fileUrl)


    fun fileUrlOf(videoId: String): String? = WebDavConfig.fileUrlOf(videoId)











    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun storedPassword(): String = AppSettings.webDavPassword.value










    private fun imageMime(fileUrl: String, bytes: ByteArray): String =
        EmbeddedArt.sniff(bytes) ?: EXTENSION_MIME[extensionOf(fileUrl)] ?: "image/jpeg"

    private fun extensionOf(fileUrl: String): String {
        val name = fileUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return name.substringAfterLast('.', "").lowercase()
    }

    private val EXTENSION_MIME = mapOf(
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "tiff" to "image/tiff",
        "tif" to "image/tiff",
    )
}














class CoverImage(val bytes: NSData, val mime: String) {
    override fun toString(): String = "CoverImage($mime, ${bytes.length} bytes)"
}
