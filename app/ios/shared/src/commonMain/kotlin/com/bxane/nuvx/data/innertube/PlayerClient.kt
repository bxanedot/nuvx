package com.bxane.nuvx.data.innertube























data class PlayerClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,

    val origin: String? = null,





    val needsSignatureTimestamp: Boolean = false,
) {
    val referer: String? get() = origin?.let { "$it/" }






    val usesMusicHost: Boolean get() = origin == MUSIC_ORIGIN


    val apiBase: String get() = if (usesMusicHost) MUSIC_BASE else YT_BASE







    fun mediaHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        origin?.let { put("Origin", it) }
        referer?.let { put("Referer", it) }
    }

    companion object {
        private const val MUSIC_ORIGIN = "https://music.youtube.com"
        private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
        private const val YT_BASE = "https://www.youtube.com/youtubei/v1"

        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"







        val ANDROID = PlayerClient(
            clientName = "ANDROID",
            clientVersion = "21.26.364",
            clientId = "3",
            userAgent = "com.google.android.youtube/21.26.364 " +
                "(Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip",
            osName = "Android",
            osVersion = "15",
            deviceMake = "Google",
            deviceModel = "Pixel 9 Pro",
            androidSdkVersion = "35",
            needsSignatureTimestamp = true,
        )








        val IOS = PlayerClient(
            clientName = "IOS",
            clientVersion = "21.26.4",
            clientId = "5",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
        )


        val IOS_RECENT = IOS.copy(
            clientVersion = "21.29.1",
            userAgent = "com.google.ios.youtube/21.29.1 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)",
            osVersion = "18.5.22F70",
        )







        val ANDROID_MUSIC = PlayerClient(
            clientName = "ANDROID_MUSIC",
            clientVersion = "8.39.42",
            clientId = "21",
            userAgent = "com.google.android.apps.youtube.music/8.39.42 " +
                "(Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002) gzip",
            osName = "Android",
            osVersion = "15",
            deviceMake = "Google",
            deviceModel = "Pixel 9 Pro",
            androidSdkVersion = "35",
        )






        val ANDROID_VR = PlayerClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
        )


        val ANDROID_VR_LEGACY = ANDROID_VR.copy(
            clientVersion = "1.43.32",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
        )















        val WEB_REMIX = PlayerClient(
            clientName = "WEB_REMIX",
            clientVersion = "1.20260707.12.00",
            clientId = "67",
            userAgent = WEB_USER_AGENT,
            origin = MUSIC_ORIGIN,
            needsSignatureTimestamp = true,
        )





        val WEB = PlayerClient(
            clientName = "WEB",
            clientVersion = "2.20260708.00.00",
            clientId = "1",
            userAgent = WEB_USER_AGENT,
            origin = YOUTUBE_ORIGIN,
        )






        val TVHTML5 = PlayerClient(
            clientName = "TVHTML5",
            clientVersion = "7.20260707.07.00",
            clientId = "7",
            userAgent = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 " +
                "(KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            origin = YOUTUBE_ORIGIN,
        )









        fun forStreamUrl(url: String): PlayerClient {
            val name = url.queryParam("c")?.uppercase() ?: return IOS
            val version = url.queryParam("cver")
            return when {
                name.startsWith("IOS") ->
                    if (version == IOS_RECENT.clientVersion) IOS_RECENT else IOS
                name == "ANDROID_VR" ->
                    if (version == ANDROID_VR_LEGACY.clientVersion) ANDROID_VR_LEGACY else ANDROID_VR
                name == "ANDROID_MUSIC" -> ANDROID_MUSIC
                name.startsWith("ANDROID") -> ANDROID
                name.startsWith("TVHTML5") -> TVHTML5
                name == "WEB_REMIX" -> WEB_REMIX
                name.startsWith("WEB") || name == "MWEB" -> WEB
                else -> IOS
            }
        }
















        fun rangeBytesFor(url: String): Long {
            if (!url.contains("googlevideo.com")) return Long.MAX_VALUE
            val name = url.queryParam("c")?.uppercase() ?: return RANGE_BYTES
            return if (name == "ANDROID_VR" || name.startsWith("TVHTML5_SIMPLY")) {
                NARROW_RANGE_BYTES
            } else {
                RANGE_BYTES
            }
        }









        fun lengthFromUrl(url: String): Long? = url.queryParamAsLong("clen")

        private fun String.queryParamAsLong(key: String): Long? {
            val query = substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return null
            return query.split('&').firstNotNullOfOrNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0 || part.substring(0, eq) != key) return@firstNotNullOfOrNull null
                part.substring(eq + 1).toLongOrNull()
            }
        }









        private fun String.queryParam(key: String): String? {
            val query = substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return null
            return query.split('&').firstNotNullOfOrNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0 || part.substring(0, eq) != key) return@firstNotNullOfOrNull null
                decode(part.substring(eq + 1))
            }
        }

        private fun decode(value: String): String {
            if ('%' !in value) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%' && i + 2 < value.length) {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.append(hex.toChar())
                        i += 3
                        continue
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }


        const val RANGE_BYTES = 1024L * 1024


        const val NARROW_RANGE_BYTES = 512L * 1024
    }
}
