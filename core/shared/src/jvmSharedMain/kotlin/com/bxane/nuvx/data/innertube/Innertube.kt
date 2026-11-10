package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.auth.normalizeDataSyncId
import com.bxane.nuvx.data.DebugLog as Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.PlaylistMove
import com.bxane.nuvx.data.model.PlaylistPrivacy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

object Innertube {
    @Volatile
    var appLanguage: () -> String? = { null }

    val currentLocaleTag: String
        get() {
            val raw = appLanguage()?.ifEmpty { null }
                ?: Locale.getDefault().toLanguageTag().ifEmpty { "en" }
            return com.bxane.nuvx.data.LocaleTags.normalizeAppTag(raw)
        }

    val currentLanguage: String
        get() = com.bxane.nuvx.data.LocaleTags.ytHl(currentLocaleTag)

    val currentRegion: String
        get() = com.bxane.nuvx.data.LocaleTags.ytGl(currentLocaleTag)

    private val searchRegion: String?
        get() = com.bxane.nuvx.data.LocaleTags.ytSearchGl(currentLocaleTag)

    private val acceptLanguageHeader: String
        get() = com.bxane.nuvx.data.LocaleTags.acceptLanguage(currentLocaleTag)

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    private const val WEB_REMIX_VERSION = "1.20250101.01.00"
    private const val WEB_REMIX_CLIENT_ID = "67"

    private const val TAG = "nuvx"

    var cookie: String? = null
        set(value) {
            if (field != value) {
                scope = null
                visitorData = null
                visitorDataIsSessionBound = false
                channelOverride = null
            }
            field = value
        }

    @Volatile
    private var visitorData: String? = null

    @Volatile
    private var visitorDataIsSessionBound = false

    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) return visitorData
        runCatching { fetchVisitorData() }
            .onFailure { Log.w(TAG, "could not mint a visitor id: ${it.message}") }
            .getOrNull()
            ?.let {
                if (refresh || !visitorDataIsSessionBound) {
                    visitorData = it
                    visitorDataIsSessionBound = false
                }
            }
        return visitorData
    }

    private suspend fun fetchVisitorData(): String? {
        val body = client.get("https://www.youtube.com/sw.js_data") {
            header("User-Agent", WEB_USER_AGENT)
        }.bodyAsText()
        val payload = Json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
        return findVisitorData(payload)
    }

    private fun findVisitorData(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findVisitorData(it) }
        is JsonPrimitive -> element.contentOrNull?.takeIf { VISITOR_DATA.matches(it) }
        else -> null
    }

    private val VISITOR_DATA = Regex("""Cg[A-Za-z0-9_%-]{40,}""")

    private class SessionScope(
        val dataSyncId: String?,
        val pageId: String?,
        val authUser: String,
        val clientVersion: String?,
    )

    @Volatile
    private var scope: SessionScope? = null

    private val scopeLock = Mutex()

    class ChannelSelection(
        val pageId: String?,
        val dataSyncId: String?,
        val authUser: String? = null,
    )

    @Volatile
    private var channelOverride: ChannelSelection? = null

    fun selectChannel(pageId: String?, dataSyncId: String?, authUser: String? = null) {
        channelOverride = if (pageId == null && dataSyncId == null) {
            null
        } else {
            ChannelSelection(pageId, dataSyncId, authUser)
        }
        Log.d(
            TAG,
            "acting as channel pageId=${pageId ?: "none"} authUser=${authUser ?: "as-is"} " +
                "(override=${channelOverride != null})",
        )
    }

    fun adoptSessionScope(
        pageId: String?,
        dataSyncId: String?,
        authUser: String?,
        visitorData: String?,
        clientVersion: String?,
        loggedIn: Boolean,
    ) {
        val version = clientVersion ?: scope?.clientVersion
        if (!loggedIn) {
            Log.w(TAG, "captured page was signed out; not scoping requests to it")
            scope = version?.let { SessionScope(null, null, "0", it) }
            return
        }
        scope = SessionScope(
            dataSyncId = dataSyncId?.takeIf { it.isNotBlank() },
            pageId = pageId?.takeIf { it.isNotBlank() },
            authUser = authUser?.takeIf { it.isNotBlank() } ?: "0",
            clientVersion = version,
        )
        visitorData?.takeIf { it.isNotBlank() }?.let {
            this.visitorData = it
            visitorDataIsSessionBound = true
        }
        Log.d(TAG, "adopted page scope: pageId=${pageId ?: "none"} authUser=${authUser ?: "0"}")
    }

    private fun pageIdFor(session: SessionScope?): String? =
        (channelOverride ?: return session?.pageId).pageId

    private fun dataSyncIdFor(session: SessionScope?): String? =
        (channelOverride ?: return session?.dataSyncId).dataSyncId

    private fun authUserFor(session: SessionScope?): String =
        channelOverride?.authUser ?: session?.authUser ?: "0"

    private val webRemixVersion: String
        get() = scope?.clientVersion ?: WEB_REMIX_VERSION

    suspend fun ensureSessionScope() {
        val session = cookie ?: return
        if (scope != null) return
        scopeLock.withLock {
            if (scope != null || cookie != session) return
            runCatching { fetchSessionScope(session) }
                .onFailure { Log.w(TAG, "could not read the session scope: ${it.message}") }
                .getOrNull()
                ?.let { fresh ->
                    if (cookie != session) {
                        Log.d(TAG, "discarding a session scope from an account that is no longer active")
                        return@let
                    }
                    scope = fresh
                    channelOverride?.let { selected ->
                        if (selected.pageId != fresh.pageId || selected.dataSyncId != fresh.dataSyncId) {
                            Log.w(TAG, "server shell identity differs from selected profile; retaining override")
                        }
                    }
                    Log.d(
                        TAG,
                        "session scope: authUser=${fresh.authUser} " +
                            "pageId=${fresh.pageId ?: "none"} " +
                            "dataSyncId=${if (fresh.dataSyncId != null) "present" else "none"} " +
                            "cver=${fresh.clientVersion ?: WEB_REMIX_VERSION}",
                    )
                }
        }
    }

    suspend fun refreshSessionScope() {
        if (cookie == null) return
        scopeLock.withLock { scope = null }
        ensureSessionScope()
    }

    private suspend fun fetchSessionScope(session: String): SessionScope? {
        val html = client.get("$MUSIC_ORIGIN/") {
            header("User-Agent", WEB_USER_AGENT)
            header("Accept-Language", acceptLanguageHeader)
            header("Cookie", session)
            sapisidFrom(session)?.let { header("Authorization", sapisidHash(it)) }
        }.bodyAsText()

        val signedIn = CONFIG_LOGGED_IN.find(html)?.groupValues?.get(1) == "true"
        val clientVersion = CONFIG_CLIENT_VERSION.find(html)?.groupValues?.get(1)
        if (!signedIn) {
            Log.w(TAG, "music.youtube.com served a signed-out shell; not scoping requests")
            return clientVersion?.let { SessionScope(null, null, "0", it) }
        }

        val pageId = CONFIG_PAGE_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val dataSyncId = pageId ?: normalizeDataSyncId(
            CONFIG_DATASYNC_ID.find(html)?.groupValues?.get(1),
        )
        val authUser = CONFIG_SESSION_INDEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        CONFIG_VISITOR_DATA.find(html)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                visitorData = it
                visitorDataIsSessionBound = true
            }

        return SessionScope(dataSyncId, pageId, authUser ?: "0", clientVersion)
    }

    private val CONFIG_LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
    private val CONFIG_DATASYNC_ID = Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_PAGE_ID = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")
    private val CONFIG_VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
    private val CONFIG_CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        engine { preconfigured = com.bxane.nuvx.data.Http.client }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = true
    }

    private suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> T): T {
        var backoff = 500L
        repeat(attempts - 1) {
            try {
                return block()
            } catch (e: HttpRequestTimeoutException) {
                Log.d(TAG, "not retrying, request timed out: ${e.message}")
                throw e
            } catch (e: IOException) {
                Log.d(TAG, "retrying: ${e.message}")
            }
            delay(backoff)
            backoff *= 2
        }
        return block()
    }

    suspend fun browse(browseId: String, params: String? = null): JsonObject =
        postMusic("browse") {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    suspend fun browseContinuation(token: String): JsonObject = postMusic(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }

    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu") {}

    suspend fun accountsList(): JsonObject = postMusic("account/accounts_list") {}

    suspend fun accountSwitcher(): JsonObject {
        requireSession()
        ensureSessionScope()
        val session = scope
        val text = withRetry {
            client.get("$YOUTUBE_ORIGIN/getAccountSwitcherEndpoint") {
                header("User-Agent", WEB_USER_AGENT)
                header("Accept-Language", acceptLanguageHeader)
                header("X-Origin", YOUTUBE_ORIGIN)
                header("Referer", "$YOUTUBE_ORIGIN/")
                cookie?.let { c ->
                    header("Cookie", c)
                    header("X-Goog-AuthUser", authUserFor(session))
                    sapisidFrom(c)?.let {
                        header("Authorization", sapisidHash(it, YOUTUBE_ORIGIN))
                    }
                }
            }.bodyAsText()
        }
        val body = text.substringAfter(")]}'", text).trim()
        return json.parseToJsonElement(body).jsonObject
    }

    suspend fun next(videoId: String): JsonObject = postMusic("next") {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
    }

    suspend fun nextPlaylist(playlistId: String, videoId: String? = null, params: String? = null): JsonObject =
        postMusic("next") {
            videoId?.let { put("videoId", it) }
            put("playlistId", playlistId)
            params?.let { put("params", it) }
            put("isAudioOnly", true)
        }

    suspend fun transcript(videoId: String): JsonObject = postMusic("get_transcript") {
        val bytes = byteArrayOf(10, videoId.toByteArray().size.toByte()) + videoId.toByteArray()
        put("params", Base64.getEncoder().encodeToString(bytes))
    }

    suspend fun search(query: String, params: String? = null, anonymous: Boolean = true): JsonObject =
        if (anonymous) {
            postMusicAnonymous("search", region = searchRegion) {
                put("query", query)
                params?.let { put("params", it) }
            }
        } else {
            postMusic("search", region = searchRegion) {
                put("query", query)
                params?.let { put("params", it) }
            }
        }

    suspend fun searchContinuation(token: String): JsonObject = postMusic(
        endpoint = "search",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        region = searchRegion,
    ) {
        put("continuation", token)
    }

    suspend fun searchSuggestions(input: String): JsonObject =
        postMusic("music/get_search_suggestions", region = searchRegion) {
            put("input", input)
        }

    suspend fun searchTypeahead(query: String): JsonObject =
        postMusicAnonymous("search", region = searchRegion) {
            put("query", query)
        }

    data class PlaybackTracking(
        val playbackUrl: String,
        val watchtimeUrl: String?,
        val atrUrl: String?,
        val atrAfterSeconds: Long,
    )

    suspend fun playbackTracking(videoId: String, signatureTimestamp: Int?): PlaybackTracking? {
        if (cookie == null) return null
        ensureSessionScope()
        val response = postMusic("player") {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("referer", "$MUSIC_ORIGIN/watch?v=$videoId")
                    signatureTimestamp?.let { put("signatureTimestamp", it) }
                }
            }
        }
        val tracking = response["playbackTracking"]?.jsonObject
        if (tracking == null) {
            val playability = response["playabilityStatus"]?.jsonObject
            Log.w(
                TAG,
                "player response has no playbackTracking for $videoId " +
                    "(status=${playability?.get("status")?.jsonPrimitive?.content}, " +
                    "reason=${playability?.get("reason")?.jsonPrimitive?.content}, " +
                    "sts=${signatureTimestamp ?: "none"})",
            )
            return null
        }
        val playbackUrl = tracking.trackingUrl("videostatsPlaybackUrl") ?: return null
        return PlaybackTracking(
            playbackUrl = playbackUrl,
            watchtimeUrl = tracking.trackingUrl("videostatsWatchtimeUrl"),
            atrUrl = tracking.trackingUrl("atrUrl"),
            atrAfterSeconds = tracking["atrUrl"]?.jsonObject
                ?.get("elapsedMediaTimeSeconds")?.jsonPrimitive?.contentOrNull
                ?.toLongOrNull() ?: DEFAULT_ATR_SECONDS,
        )
    }

    private const val DEFAULT_ATR_SECONDS = 5L

    private fun JsonObject.trackingUrl(key: String): String? =
        this[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content

    suspend fun pingPlayback(baseUrl: String, cpn: String) = pingStats(baseUrl, cpn) {}

    suspend fun pingWatchtime(baseUrl: String, cpn: String, seconds: Long, final: Boolean = false) =
        pingStats(baseUrl, cpn) {
            parameter("st", "0")
            parameter("et", seconds.toString())
            parameter("cmt", seconds.toString())
            parameter("state", if (final) "paused" else "playing")
            if (final) parameter("final", "1")
        }

    suspend fun pingAtr(baseUrl: String, cpn: String): Int {
        if (cookie != null) ensureSessionScope()
        val session = scope
        return client.get(baseUrl) {
            parameter("cpn", cpn)
            statsHeaders(session)
        }.status.value
    }

    private suspend fun pingStats(
        baseUrl: String,
        cpn: String,
        extras: HttpRequestBuilder.() -> Unit,
    ): Int {
        if (cookie != null) ensureSessionScope()
        val session = scope
        return client.get(baseUrl) {
            parameter("ver", "2")
            parameter("c", "WEB_REMIX")
            parameter("cver", webRemixVersion)
            parameter("cpn", cpn)
            parameter("cplayer", "UNIPLAYER")
            parameter("cbr", "Chrome")
            parameter("cbrver", "141.0.0.0")
            parameter("cos", "Windows")
            parameter("cosver", "10.0")
            parameter("hl", currentLanguage.replace('-', '_'))
            parameter("cr", currentRegion)
            extras()
            statsHeaders(session)
        }.status.value
    }

    private fun HttpRequestBuilder.statsHeaders(session: SessionScope?) {
        header("X-Origin", MUSIC_ORIGIN)
        header("Origin", MUSIC_ORIGIN)
        header("Referer", "$MUSIC_ORIGIN/")
        header("User-Agent", WEB_USER_AGENT)
        visitorData?.let { header("X-Goog-Visitor-Id", it) }
        cookie?.let { c ->
            header("Cookie", c)
            header("X-Goog-AuthUser", authUserFor(session))
            pageIdFor(session)?.let { header("X-Goog-PageId", it) }
            sapisidFrom(c)?.let { header("Authorization", sapisidHash(it)) }
        }
    }

    class NotSignedInException : IllegalStateException("Sign in to YouTube Music to do that")

    private fun requireSession() {
        if (cookie == null) throw NotSignedInException()
    }

    suspend fun rate(videoId: String, status: LikeStatus) {
        requireSession()
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("videoId", videoId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the rating: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $videoId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    suspend fun ratePlaylist(playlistId: String, saved: Boolean) {
        requireSession()
        val endpoint = if (saved) "like/like" else "like/removelike"
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("playlistId", playlistId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the change: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $playlistId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    suspend fun setSubscribed(channelId: String, subscribed: Boolean) {
        requireSession()
        val endpoint = if (subscribed) "subscription/subscribe" else "subscription/unsubscribe"
        val response = postMusic(endpoint) {
            putJsonArray("channelIds") { add(channelId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the change: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $channelId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    suspend fun sendFeedback(token: String) {
        requireSession()
        postMusic("feedback") {
            putJsonArray("feedbackTokens") { add(token) }
        }
    }

    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        description: String? = null,
        videoIds: List<String> = emptyList(),
    ): String {
        requireSession()
        val response = postMusic("playlist/create") {
            put("title", title)
            put("description", description.orEmpty())
            put("privacyStatus", privacy.apiValue)
            if (videoIds.isNotEmpty()) {
                putJsonArray("videoIds") { videoIds.forEach { add(it) } }
            }
        }
        return response["playlistId"]?.jsonPrimitive?.contentOrNull
            ?: findString(response, "playlistId")
            ?: error("playlist created but no id came back")
    }

    suspend fun deletePlaylist(playlistId: String) {
        requireSession()
        postMusic("playlist/delete") { put("playlistId", playlistId.removePrefix("VL")) }
    }

    private suspend fun editPlaylist(
        playlistId: String,
        actions: JsonArrayBuilder.() -> Unit,
    ): JsonObject {
        requireSession()
        val response = postMusic("browse/edit_playlist") {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions", actions)
        }
        val status = response["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && status != "STATUS_SUCCEEDED") {
            error("YouTube Music refused the edit ($status)")
        }
        return response
    }

    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Map<String, String> {
        val response = editPlaylist(playlistId) {
            videoIds.forEach { videoId ->
                addJsonObject {
                    put("action", "ACTION_ADD_VIDEO")
                    put("addedVideoId", videoId)
                }
            }
        }
        return (response["playlistEditResults"] as? JsonArray)
            .orEmpty()
            .mapNotNull { result ->
                val added = (result as? JsonObject)
                    ?.get("playlistEditVideoAddedResultData") as? JsonObject
                    ?: return@mapNotNull null
                val videoId = (added["videoId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val setVideoId = (added["setVideoId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                videoId to setVideoId
            }
            .toMap()
    }

    suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) {
        editPlaylist(playlistId) {
            entries.forEach { (setVideoId, videoId) ->
                addJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("setVideoId", setVideoId)
                    put("removedVideoId", videoId)
                }
            }
        }
    }

    suspend fun movePlaylistItems(playlistId: String, moves: List<PlaylistMove>) {
        if (moves.isEmpty()) return
        editPlaylist(playlistId) {
            moves.forEach { move ->
                addJsonObject {
                    put("action", "ACTION_MOVE_VIDEO_BEFORE")
                    put("setVideoId", move.setVideoId)
                    move.before?.let { put("movedSetVideoIdSuccessor", it) }
                }
            }
        }
    }

    suspend fun renamePlaylist(playlistId: String, title: String) {
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title)
            }
        }
    }

    fun newCpn(): String = (1..16).map { CPN_ALPHABET.random() }.joinToString("")

    private const val CPN_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    private suspend fun postMusic(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        region: String? = currentRegion,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        if (cookie != null) ensureSessionScope()
        val session = scope
        val clientVersion = webRemixVersion
        val response = withRetry {
            client.post("$MUSIC_BASE/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                parameter("hl", currentLanguage)
                header("Accept-Language", acceptLanguageHeader)
                query.forEach { (key, value) -> parameter(key, value) }
                header("X-Origin", MUSIC_ORIGIN)
                header("Origin", MUSIC_ORIGIN)
                header("Referer", "$MUSIC_ORIGIN/")
                header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                header("X-YouTube-Client-Version", clientVersion)
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                cookie?.let { c ->
                    header("Cookie", c)
                    header("X-Goog-AuthUser", authUserFor(session))
                    pageIdFor(session)?.let { header("X-Goog-PageId", it) }
                    sapisidFrom(c)?.let { header("Authorization", sapisidHash(it)) }
                }
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", clientVersion)
                                put("hl", currentLanguage)
                                region?.let { put("gl", it) }
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                dataSyncIdFor(session)?.let { put("onBehalfOfUser", it) }
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.body<JsonObject>()
        }

        if (visitorData == null) {
            visitorData = response["responseContext"]?.jsonObject
                ?.get("visitorData")?.jsonPrimitive?.content
        }
        return response
    }

    suspend fun youtubeSearch(query: String): JsonObject = withRetry {
        client.post("https://www.youtube.com/youtubei/v1/search") {
            contentType(ContentType.Application.Json)
            parameter("prettyPrint", "false")
            header("Origin", "https://www.youtube.com")
            header("X-YouTube-Client-Name", "1")
            header("X-YouTube-Client-Version", YOUTUBE_WEB_VERSION)
            header("Accept-Language", acceptLanguageHeader)
            visitorData?.let { header("X-Goog-Visitor-Id", it) }
            setBody(
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "WEB")
                            put("clientVersion", YOUTUBE_WEB_VERSION)
                            put("hl", currentLanguage)
                            currentRegion?.let { put("gl", it) }
                            visitorData?.let { put("visitorData", it) }
                        }
                    }
                    put("query", query)
                    put("params", "EgIQAQ%3D%3D")
                },
            )
        }.body<JsonObject>()
    }

    private const val YOUTUBE_WEB_VERSION = "2.20251105.01.00"

    private suspend fun postMusicAnonymous(
        endpoint: String,
        region: String? = currentRegion,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val clientVersion = webRemixVersion
        return withRetry {
            client.post("$MUSIC_BASE/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                header("X-Origin", MUSIC_ORIGIN)
                header("Origin", MUSIC_ORIGIN)
                header("Referer", "$MUSIC_ORIGIN/")
                header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                header("X-YouTube-Client-Version", clientVersion)
                header("Accept-Language", acceptLanguageHeader)
                parameter("hl", currentLanguage)
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", clientVersion)
                                put("hl", currentLanguage)
                                region?.let { put("gl", it) }
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.body<JsonObject>()
        }
    }

    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> (element[key] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    private fun sapisidFrom(cookieHeader: String): String? {
        val jar = cookieHeader.split(';')
            .mapNotNull { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
        return SAPISID_NAMES.firstNotNullOfOrNull { jar[it] }
    }

    private val SAPISID_NAMES =
        listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    private fun sapisidHash(sapisid: String, origin: String = MUSIC_ORIGIN): String {
        val timestamp = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "SAPISIDHASH ${timestamp}_$digest"
    }
}
