package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.http.Http
import com.bxane.nuvx.data.http.ProbeResult
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.PlaylistPrivacy
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonArrayBuilder
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import com.bxane.nuvx.data.http.HttpStatusException
import kotlin.time.Clock
import kotlinx.io.IOException





















@OptIn(ExperimentalAtomicApi::class)
object Innertube {

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val YT_BASE = "https://www.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"



    private const val WEB_REMIX_VERSION = "1.20250101.01.00"
    private const val WEB_REMIX_CLIENT_ID = "67"

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }


    private data class SessionState(
        val generation: Long = 0,
        val cookie: String? = null,
        val scope: SessionScope? = null,
        val channel: ChannelSelection? = null,
        val visitor: String? = null,
        val visitorBound: Boolean = false,
    )
    private val sessionState = AtomicReference(SessionState())
    private fun updateSession(change: (SessionState) -> SessionState) {
        while (true) {
            val before = sessionState.load()
            if (sessionState.compareAndSet(before, change(before))) return
        }
    }
    val sessionGeneration: Long get() = sessionState.load().generation
    var cookie: String?
        get() = sessionState.load().cookie
        set(value) {
            if (value == cookie) return
            updateSession { SessionState(generation = it.generation + 1, cookie = value) }
            sessionChanged()
        }
    private var visitorData: String?
        get() = sessionState.load().visitor
        set(value) { updateSession { it.copy(visitor = value) } }
    private val visitorDataIsSessionBound: Boolean get() = sessionState.load().visitorBound
    private val scope: SessionScope? get() = sessionState.load().scope
    private val channelOverride: ChannelSelection? get() = sessionState.load().channel

    private fun sessionChanged() {
        com.bxane.nuvx.data.LikeState.clear()
        StreamResolver.onSessionChanged()
        PlaybackTracker.onSessionChanged()
    }
    class SessionUnavailableException : IllegalStateException("Your saved session could not be verified. Try again when connected.")
    class AuthenticationException : IllegalStateException(SESSION_EXPIRED_MESSAGE)
    class PlayerAuthenticationException : IllegalStateException("This playback client rejected authentication")
    class SessionChangedException : CancellationException("Account changed during request")





    internal const val SESSION_EXPIRED_MESSAGE = "Your YouTube Music session has expired. Sign in again."

    internal class RequestSession(
        val generation: Long,
        val cookie: String?,
        val authUser: String?,
        val pageId: String?,
        val dataSyncId: String?,
        val visitor: String?,
        val clientVersion: String,
    )
    internal fun requestSession(): RequestSession {
        val state = sessionState.load()
        if (state.cookie != null && state.scope == null) throw SessionUnavailableException()
        val chosen = state.channel
        return RequestSession(state.generation, state.cookie,
            chosen?.authUser ?: state.scope?.authUser,
            if (chosen != null) chosen.pageId else state.scope?.pageId,
            if (chosen != null) chosen.dataSyncId else state.scope?.dataSyncId,
            state.visitor, state.scope?.clientVersion ?: WEB_REMIX_VERSION)
    }
    internal fun checkSession(generation: Long) {
        if (sessionGeneration != generation) throw SessionChangedException()
    }






    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) return visitorData
        val generation = sessionGeneration
        runCatching { fetchVisitorData() }
            .getOrNull()
            ?.let {
                updateSession { state ->
                    if (state.generation == generation && !state.visitorBound) state.copy(visitor = it)
                    else state
                }
            }
        return visitorData
    }








    internal suspend fun mintAnonymousVisitor(): String? = fetchVisitorData()

    private suspend fun fetchVisitorData(): String? {
        val body = Http.getText(
            "https://www.youtube.com/sw.js_data",
            headers = mapOf("User-Agent" to WEB_USER_AGENT),
        )
        val payload = json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
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

    private val scopeLock = Mutex()











    class ChannelSelection(
        val pageId: String?,
        val dataSyncId: String?,






        val authUser: String? = null,
    )


    fun adoptPageScope(pageId: String?, dataSyncId: String?, authUser: String?) {
        val chosen = if (pageId == null && dataSyncId == null && authUser == null) null
            else ChannelSelection(pageId, dataSyncId, authUser)
        val before = channelOverride
        if (before?.pageId == chosen?.pageId && before?.dataSyncId == chosen?.dataSyncId && before?.authUser == chosen?.authUser) return
        updateSession { it.copy(generation = it.generation + 1, channel = chosen) }
        sessionChanged()
    }


















    fun adoptSessionScope(
        pageId: String?,
        dataSyncId: String?,
        authUser: String?,
        visitorData: String?,
        clientVersion: String?,
        loggedIn: Boolean,
    ) {
        updateSession { state ->
            val version = clientVersion?.takeIf { it.isNotBlank() } ?: state.scope?.clientVersion
            state.copy(generation = state.generation + 1,
                scope = if (loggedIn && !authUser.isNullOrBlank()) SessionScope(dataSyncId?.takeIf { it.isNotBlank() },
                    pageId?.takeIf { it.isNotBlank() }, authUser, version) else null,
                visitor = visitorData?.takeIf { it.isNotBlank() } ?: state.visitor,
                visitorBound = loggedIn && !visitorData.isNullOrBlank())
        }
        sessionChanged()
    }







    fun normalizeDataSyncId(raw: String?): String? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        if (!value.contains("||")) return value
        return value.substringAfter("||").takeIf { it.isNotBlank() }
            ?: value.substringBefore("||").takeIf { it.isNotBlank() }
    }
















    fun currentIdentity(): ChannelSelection? {
        val session = scope ?: return null
        val pageId = pageIdFor(session)
        val dataSyncId = dataSyncIdFor(session)
        if (pageId == null && dataSyncId == null) return null
        return ChannelSelection(pageId, dataSyncId, authUserFor(session))
    }











    private fun pageIdFor(session: SessionScope?): String? =
        channelOverride.let { if (it != null) it.pageId else session?.pageId }


    private fun dataSyncIdFor(session: SessionScope?): String? =
        channelOverride.let { if (it != null) it.dataSyncId else session?.dataSyncId }








    private fun authUserFor(session: SessionScope?): String =
        channelOverride?.authUser ?: session?.authUser ?: "0"

    private val webRemixVersion: String
        get() = scope?.clientVersion ?: WEB_REMIX_VERSION


    suspend fun ensureSessionScope() {
        val expected = sessionState.load()
        val session = expected.cookie ?: return
        if (expected.scope != null) return
        scopeLock.withLock {
            checkSession(expected.generation)
            if (scope != null) return
            val fresh = try { fetchSessionScope(session, expected.generation) }
            catch (error: CancellationException) { throw error }
            catch (error: AuthenticationException) { throw error }
            catch (error: HttpStatusException) {
                if (error.status == 401) throw AuthenticationException()
                throw SessionUnavailableException()
            }
            catch (error: Throwable) { throw SessionUnavailableException() }
            checkSession(expected.generation)
            updateSession { state ->
                if (state.generation == expected.generation) state.copy(scope = fresh) else state
            }
            checkSession(expected.generation)
        }
    }





    internal var scopeTransport: suspend (String) -> String = { session ->
        Http.getText(
            "$MUSIC_ORIGIN/",
            headers = buildMap {
                put("User-Agent", WEB_USER_AGENT)
                put("Accept-Language", "en-US,en;q=0.9")
                put("Cookie", session)
                sapisidFrom(session)?.let { put("Authorization", sapisidHash(it)) }
            },
        )
    }

    private suspend fun fetchSessionScope(session: String, generation: Long): SessionScope {
        val html = scopeTransport(session)
        val signedIn = CONFIG_LOGGED_IN.find(html)?.groupValues?.get(1) == "true"
        val clientVersion = CONFIG_CLIENT_VERSION.find(html)?.groupValues?.get(1)
        if (!signedIn) {
            throw AuthenticationException()
        }
        val dataSyncId = CONFIG_DATASYNC_ID.find(html)?.groupValues?.get(1)
            ?.let { normalizeDataSyncId(it) }
        val pageId = CONFIG_PAGE_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val authUser = CONFIG_SESSION_INDEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        CONFIG_VISITOR_DATA.find(html)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                updateSession { state ->
                    if (state.generation == generation) state.copy(visitor = it, visitorBound = true) else state
                }
            }
        return SessionScope(dataSyncId, pageId, authUser ?: channelOverride?.authUser ?: throw SessionUnavailableException(), clientVersion)
    }

    private val CONFIG_LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
    private val CONFIG_DATASYNC_ID = Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_PAGE_ID = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")
    private val CONFIG_VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
    private val CONFIG_CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")















    @OptIn(ExperimentalEncodingApi::class)
    suspend fun transcript(videoId: String): JsonObject = postMusic("get_transcript") {
        val id = videoId.encodeToByteArray()
        val bytes = ByteArray(2 + id.size)
        bytes[0] = 10
        bytes[1] = id.size.toByte()
        id.copyInto(bytes, destinationOffset = 2)
        put("params", Base64.Default.encode(bytes))
    }

    suspend fun search(query: String, params: String? = null): JsonObject =
        postMusic("search") {
            put("query", query)
            params?.let { put("params", it) }
        }






    suspend fun searchContinuation(token: String): JsonObject = postMusic(
        endpoint = "search",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }


    suspend fun searchSuggestions(input: String): JsonObject =
        postMusic("music/get_search_suggestions") {
            put("input", input)
        }







    suspend fun searchTypeahead(query: String): JsonObject = postMusic(
        endpoint = "search",
        anonymous = true,
    ) {
        put("query", query)
    }







    suspend fun browse(browseId: String, params: String? = null): JsonObject =
        postMusic("browse", requireIdentity = browseId in PERSONAL_BROWSE) {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }






    suspend fun browseContinuation(token: String): JsonObject = postMusic(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }


    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu", requireIdentity = true) {}





    suspend fun next(videoId: String): JsonObject = postMusic("next") {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
    }



















    suspend fun player(
        videoId: String,
        client: PlayerClient,
        signatureTimestamp: Int? = null,
        authenticated: Boolean = false,
    ): JsonObject {
        val response = postPlayer(videoId, client, signatureTimestamp, authenticated)
        val playability = response["playabilityStatus"] as? JsonObject
        val status = (playability?.get("status") as? JsonPrimitive)?.contentOrNull
        if (status != null && status != "OK") {
            throw UnplayableException(playabilityReason(playability) ?: status)
        }
        return response
    }






    private fun playabilityReason(playability: JsonObject?): String? {
        if (playability == null) return null
        val reason = (playability["reason"] as? JsonPrimitive)?.contentOrNull
        val sub = playability.subreason()
        return when {
            !sub.isNullOrBlank() && !reason.isNullOrBlank() &&
                !reason.contains(sub, ignoreCase = true) -> "$reason — $sub"
            !sub.isNullOrBlank() -> sub
            else -> reason
        }
    }

    private fun JsonObject.subreason(): String? {
        (get("messages") as? JsonArray)
            ?.filterIsInstance<JsonPrimitive>()
            ?.firstNotNullOfOrNull { it.contentOrNull }
            ?.let { return it }
        val errorScreen = get("errorScreen") as? JsonObject ?: return null
        fun dig(obj: JsonObject): String? {
            (obj["subreason"] as? JsonObject)?.let { sub ->
                (sub["simpleText"] as? JsonPrimitive)?.contentOrNull?.let { return it }
                (sub["runs"] as? JsonArray)
                    ?.filterIsInstance<JsonObject>()
                    ?.mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
                    ?.joinToString("")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            obj.values.forEach { v ->
                if (v is JsonObject) dig(v)?.let { return it }
            }
            return null
        }
        return dig(errorScreen)
    }

    class UnplayableException(private val reason: String) :
        IllegalStateException("Track unavailable: $reason") {








        val looksLikeBotCheck: Boolean
            get() = !isAgeGate && (
                reason.contains("bot", ignoreCase = true) ||
                    reason.contains("unusual traffic", ignoreCase = true) ||
                    reason.contains("sign in", ignoreCase = true) ||
                    reason.contains("login_required", ignoreCase = true) ||


                    reason.contains("page needs to be reloaded", ignoreCase = true)
                )

        val isAgeGate: Boolean
            get() = reason.contains("confirm your age", ignoreCase = true) ||
                reason.contains("age-restricted", ignoreCase = true) ||
                reason.contains("age restricted", ignoreCase = true) ||
                reason.contains("inappropriate for some users", ignoreCase = true)






        val isPermanent: Boolean
            get() = PERMANENT_REASONS.any { reason.contains(it, ignoreCase = true) }

        val displayReason: String get() = reason

        private companion object {
            private val PERMANENT_REASONS = listOf(
                "not available in your country",
                "who has blocked it in your country",
                "removed by the uploader",
                "account associated with this video has been terminated",
                "private video",
                "members-only",
            )
        }
    }



    class PlaybackTracking(
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
        val tracking = response["playbackTracking"] as? JsonObject ?: return null
        val playbackUrl = tracking.trackingUrl("videostatsPlaybackUrl") ?: return null
        return PlaybackTracking(
            playbackUrl = playbackUrl,
            watchtimeUrl = tracking.trackingUrl("videostatsWatchtimeUrl"),
            atrUrl = tracking.trackingUrl("atrUrl"),
            atrAfterSeconds = ((tracking["atrUrl"] as? JsonObject)
                ?.get("elapsedMediaTimeSeconds") as? JsonPrimitive)
                ?.contentOrNull?.toLongOrNull() ?: 5L,
        )
    }

    private fun JsonObject.trackingUrl(key: String): String? =
        ((this[key] as? JsonObject)?.get("baseUrl") as? JsonPrimitive)?.contentOrNull

    suspend fun pingPlayback(baseUrl: String, cpn: String, generation: Long = sessionGeneration): Int =
        pingStats(baseUrl, cpn, generation = generation)

    suspend fun pingWatchtime(baseUrl: String, cpn: String, seconds: Long, final: Boolean = false, generation: Long = sessionGeneration): Int =
        pingStats(
            baseUrl,
            cpn,
            generation = generation,
            extra = buildMap {
                put("st", "0")
                put("et", seconds.toString())
                put("cmt", seconds.toString())
                put("state", if (final) "paused" else "playing")
                if (final) put("final", "1")
            },
        )

    suspend fun pingAtr(baseUrl: String, cpn: String, generation: Long = sessionGeneration): Int {
        val headers = statsHeaders(baseUrl, generation)
        return Http.getStatus(
            url = baseUrl,
            headers = headers,
            query = mapOf("cpn" to cpn),
        ).also { checkSession(generation) }
    }

    private suspend fun pingStats(
        baseUrl: String,
        cpn: String,
        extra: Map<String, String> = emptyMap(),
        generation: Long = sessionGeneration,
    ): Int {
        checkSession(generation)
        val session = requestSession()
        val headers = statsHeaders(baseUrl, generation, session)
        return Http.getStatus(
        url = baseUrl,
        headers = headers,
        query = mapOf(
            "ver" to "2",
            "c" to "WEB_REMIX",
            "cver" to session.clientVersion,
            "cpn" to cpn,
            "cplayer" to "UNIPLAYER",
            "cbr" to "Chrome",
            "cbrver" to "141.0.0.0",
            "cos" to "Windows",
            "cosver" to "10.0",
            "hl" to "en_US",
            "cr" to "US",
        ) + extra,
    ).also { checkSession(generation) }
    }

    private fun statsHeaders(url: String, generation: Long, session: RequestSession = requestSession()): Map<String, String> = buildMap {
        checkSession(generation)
        val target = io.ktor.http.Url(url)
        require(target.protocol.name == "https" && target.port == 443 && target.user.isNullOrEmpty() && target.password.isNullOrEmpty() && target.host in setOf("music.youtube.com", "www.youtube.com", "s.youtube.com")) { "Untrusted playback tracking destination" }
        checkSession(session.generation)
        put("X-Origin", MUSIC_ORIGIN)
        put("Origin", MUSIC_ORIGIN)
        put("Referer", "$MUSIC_ORIGIN/")
        put("User-Agent", WEB_USER_AGENT)
        session.visitor?.let { put("X-Goog-Visitor-Id", it) }
        putAll(authHeaders(session, MUSIC_ORIGIN))
    }

    fun newCpn(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        return CharArray(16) { alphabet.random() }.concatToString()
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
    }


    suspend fun setSubscribed(channelId: String, subscribed: Boolean) {
        requireSession()
        val endpoint = if (subscribed) "subscription/subscribe" else "subscription/unsubscribe"
        val response = postMusic(endpoint) {
            putJsonArray("channelIds") { add(channelId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the subscription change: ${message ?: error}")
        }
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

    suspend fun renamePlaylist(playlistId: String, title: String) {
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title)
            }
        }
    }

    suspend fun setPlaylistPrivacy(playlistId: String, privacy: String) {
        val p = PlaylistPrivacy.entries.firstOrNull { it.name.equals(privacy, true) }
            ?: PlaylistPrivacy.PRIVATE
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_SET_PLAYLIST_PRIVACY")
                put("playlistPrivacyStatus", p.apiValue)
            }
        }
    }


    suspend fun movePlaylistItem(
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) {
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_MOVE_VIDEO_AFTER")
                put("setVideoId", setVideoId)
                if (!successorSetVideoId.isNullOrBlank()) {
                    put("movedSetVideoIdSuccessor", successorSetVideoId)
                }
            }
        }
    }


    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> (element[key] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

























    private suspend fun <T> withRetry(what: String, attempts: Int = 3, block: suspend () -> T): T {
        var backoff = 500L
        repeat(attempts - 1) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!e.isTransport()) throw e
                DebugLog.d("retrying $what after transport failure")
            }
            delay(backoff)
            backoff *= 2
        }
        return block()
    }











    private fun Throwable.isTransport(): Boolean {
        if (this is HttpRequestTimeoutException) return false
        var current: Throwable? = this
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is HttpRequestTimeoutException) return false
            if (current is UnplayableException || current is NotSignedInException) return false
            if (current is IOException) return true
            val text = current.message?.lowercase().orEmpty()
            if (TRANSPORT_MARKERS.any { it in text }) return true
            current = current.cause
        }
        return false
    }






    private val TRANSPORT_MARKERS = listOf(
        "connection reset", "connection lost", "software caused connection abort",
        "connection refused", "network connection lost", "timed out", "broken pipe",
        "nodata", "cannot connect to host", "the network connection was lost",
        "unsatisfiable constraints", "could not connect",
    )

    internal class MusicRequest(val url: String, val body: String, val headers: Map<String, String>, val query: Map<String, String>)
    internal var musicTransport: suspend (MusicRequest) -> String = { request ->
        Http.postJson(request.url, request.body, request.headers, request.query)
    }

    internal var playerTransport: suspend (MusicRequest) -> String = { request ->
        Http.postJson(request.url, request.body, request.headers, request.query, timeoutMillis = PLAYER_TIMEOUT_MS)
    }

    private val PERSONAL_BROWSE = setOf("FEmusic_history", "FEmusic_liked_videos", "FEmusic_liked_playlists",
        "FEmusic_liked_albums", "FEmusic_library_corpus_track_artists", "FEmusic_library_corpus_artists", "FEmusic_library_non_music_audio_list")

    private val SAFE_MUSIC_READS = setOf("browse", "search", "music/get_search_suggestions", "account/account_menu", "next", "player", "get_transcript")

    private suspend fun postMusic(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        refreshAllowed: Boolean = true,
        requireIdentity: Boolean = false,
        anonymous: Boolean = false,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val generation = sessionGeneration
        ensureSessionScope()
        checkSession(generation)
        val session = requestSession()
        val clientVersion = session.clientVersion
        val safeRead = endpoint in SAFE_MUSIC_READS
        if ((!safeRead || requireIdentity) && session.cookie == null) throw NotSignedInException()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    session.visitor?.let { put("visitorData", it) }
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)




                    if (!anonymous) session.dataSyncId?.let { put("onBehalfOfUser", it) }
                }
                putJsonObject("request") { put("useSsl", true) }
            }
            bodyExtras()
        }
        val headers = buildMap {
            put("User-Agent", WEB_USER_AGENT)
            put("X-Origin", MUSIC_ORIGIN)
            put("Origin", MUSIC_ORIGIN)
            put("Referer", "$MUSIC_ORIGIN/")
            put("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
            put("X-YouTube-Client-Version", clientVersion)
            session.visitor?.let { put("X-Goog-Visitor-Id", it) }
            if (!anonymous) putAll(authHeaders(session, MUSIC_ORIGIN))
        }
        val text = try { withRetry("postMusic/$endpoint", attempts = if (safeRead) 3 else 1) {
            checkSession(generation)
            musicTransport(MusicRequest("$MUSIC_BASE/$endpoint",
                json.encodeToString(JsonObject.serializer(), body), headers,
                query + ("prettyPrint" to "false")))
        }
        } catch (error: HttpStatusException) {
            if (error.status == 401 && session.cookie != null) {
                if (safeRead && refreshAllowed) {
                    checkSession(generation)
                    updateSession { if (it.generation == generation) it.copy(scope = null) else it }
                    return postMusic(endpoint, query, refreshAllowed = false, requireIdentity = requireIdentity, anonymous = anonymous, bodyExtras = bodyExtras)
                }
                throw AuthenticationException()
            }
            throw error
        }
        checkSession(generation)
        val response = json.parseToJsonElement(text) as? JsonObject
            ?: error("innertube $endpoint: unexpected response shape")


            if (visitorData == null && !visitorDataIsSessionBound) {
                visitorData = ((response["responseContext"] as? JsonObject)
                    ?.get("visitorData") as? JsonPrimitive)?.contentOrNull
            }
        return response
    }













    private suspend fun postPlayer(
        videoId: String,
        playerClient: PlayerClient,
        signatureTimestamp: Int? = null,
        authenticated: Boolean = false,
        refreshAllowed: Boolean = true,
    ): JsonObject {
        val generation = sessionGeneration
        if (authenticated) ensureSessionScope()
        checkSession(generation)
        val session = if (authenticated) requestSession() else {
            val state = sessionState.load()
            RequestSession(state.generation, null, null, null, null, state.visitor, WEB_REMIX_VERSION)
        }
        if (authenticated && session.cookie == null) throw NotSignedInException()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", playerClient.clientName)
                    put("clientVersion", playerClient.clientVersion)
                    playerClient.osName?.let { put("osName", it) }
                    playerClient.osVersion?.let { put("osVersion", it) }
                    playerClient.deviceMake?.let { put("deviceMake", it) }
                    playerClient.deviceModel?.let { put("deviceModel", it) }
                    playerClient.androidSdkVersion?.let { put("androidSdkVersion", it.toInt()) }
                    put("hl", "en")
                    put("gl", "US")
                    session.visitor?.let { put("visitorData", it) }
                }
                if (authenticated) putJsonObject("user") {
                    session.dataSyncId?.let { put("onBehalfOfUser", it) }
                    put("lockedSafetyMode", false)
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (playerClient.needsSignatureTimestamp && signatureTimestamp != null) {
                putJsonObject("playbackContext") {
                    putJsonObject("contentPlaybackContext") {
                        put("signatureTimestamp", signatureTimestamp)
                    }
                }
            }
        }
        val headers = buildMap {
            put("User-Agent", playerClient.userAgent)
            put("X-YouTube-Client-Name", playerClient.clientId)
            put("X-YouTube-Client-Version", playerClient.clientVersion)
            playerClient.origin?.let { put("Origin", it) }
            playerClient.referer?.let { put("Referer", it) }
            session.visitor?.let { put("X-Goog-Visitor-Id", it) }
            if (authenticated) {
                put("Origin", playerClient.apiOrigin)
                put("X-Origin", playerClient.apiOrigin)
                putAll(authHeaders(session, playerClient.apiOrigin))
            }
        }
        val text = try { withRetry("player/${playerClient.clientName}") {
            checkSession(generation)
            playerTransport(MusicRequest("${playerClient.apiBase}/player",
                json.encodeToString(JsonObject.serializer(), body), headers, mapOf("prettyPrint" to "false")))
        } } catch (error: HttpStatusException) {
            if (authenticated && error.status == 401) {
                checkSession(generation)
                if (refreshAllowed) {
                    updateSession { if (it.generation == generation) it.copy(scope = null) else it }
                    return postPlayer(videoId, playerClient, signatureTimestamp, authenticated, refreshAllowed = false)
                }
                throw PlayerAuthenticationException()
            }
            throw error
        }
        checkSession(generation)
        return json.parseToJsonElement(text) as? JsonObject
            ?: error("innertube player: unexpected response shape")
    }











    private val PlayerClient.apiOrigin: String
        get() = origin ?: if (usesMusicHost) MUSIC_ORIGIN else YOUTUBE_ORIGIN


    private const val PLAYER_TIMEOUT_MS = 6_000L







    internal fun authHeaders(origin: String = MUSIC_ORIGIN): Map<String, String> =
        authHeaders(requestSession(), origin)

    internal fun authHeaders(session: RequestSession, origin: String): Map<String, String> = buildMap {
        require(origin == MUSIC_ORIGIN || origin == YOUTUBE_ORIGIN) { "Untrusted authentication origin" }
        val cookie = session.cookie ?: return@buildMap
        put("Cookie", cookie)
        session.authUser?.let { put("X-Goog-AuthUser", it) }
        session.pageId?.let { put("X-Goog-PageId", it) }
        sapisidFrom(cookie)?.let { put("Authorization", sapisidHash(it, origin)) }
    }






    fun hasApiSid(cookieHeader: String): Boolean =
        cookieHeader.split(';').any { entry ->
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            name in SAPISID_NAMES && value.isNotEmpty()
        }







    fun sapisidFrom(cookieHeader: String): String? {
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


    fun sapisidHash(sapisid: String, origin: String = MUSIC_ORIGIN): String {
        val timestamp = Clock.System.now().toEpochMilliseconds() / 1000
        val digest = Sha1.hex("$timestamp $sapisid $origin".encodeToByteArray())
        return "SAPISIDHASH ${timestamp}_$digest"
    }
}














internal fun ProbeResult.classify(expected: String? = null): ProbeVerdict = when {
    status in REFUSAL_STATUSES -> ProbeVerdict.REFUSED
    status !in 200..299 && status != 416 -> ProbeVerdict.UNREACHABLE


    status == 416 -> ProbeVerdict.UNREACHABLE
    !contentType.isMediaFamilyOf(expected) -> ProbeVerdict.REFUSED
    !bodyArrived -> ProbeVerdict.UNREACHABLE
    else -> ProbeVerdict.OK
}


private val REFUSAL_STATUSES = setOf(403, 404, 410)

















private fun String?.isMediaFamilyOf(expected: String?): Boolean {
    val answer = this?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    if (answer == "application/octet-stream") return true



    val family = answer.substringBefore('/')
    if (family != "audio" && family != "video") return false
    val wanted = expected?.substringBefore(';')?.trim()?.lowercase()?.substringBefore('/')
        ?.takeIf { it == "audio" || it == "video" }
    return wanted == null || wanted == family
}

internal enum class ProbeVerdict {

    OK,


    REFUSED,


    UNREACHABLE,
}
