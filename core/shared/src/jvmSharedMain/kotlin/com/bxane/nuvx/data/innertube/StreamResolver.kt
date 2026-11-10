package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.MonotonicClock
import com.bxane.nuvx.data.TrackLog
import com.bxane.nuvx.data.Http
import com.bxane.nuvx.data.NerdStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object StreamResolver {
    @Volatile
    var maxKbps: () -> Int = { Int.MAX_VALUE }

    interface FallbackEngine {
        suspend fun stream(videoId: String, maxKbps: Int): Stream?

        fun headersFor(url: String): Map<String, String>?
    }

    @Volatile
    var fallbackEngine: () -> FallbackEngine? = { null }


    private const val TAG = "nuvx"

    private const val SLOW_FETCH_MS = 2000L

    private const val NEXT_ENDPOINT = "/youtubei/v1/next"

    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""

    private class OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            if (NEXT_ENDPOINT in request.url()) {
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, request.url())
            }
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }
            if (!hasUserAgent) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                )
            }

            val requestStart = MonotonicClock.nowMs()
            val response = try {
                extractorClient.newCall(builder.build()).execute()
            } catch (e: Exception) {
                TrackLog.w(
                    TAG,
                    "extractor fetch FAILED after ${MonotonicClock.nowMs() - requestStart}ms " +
                        "${request.httpMethod()} ${request.url()}: ${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
            val took = MonotonicClock.nowMs() - requestStart
            if (took > SLOW_FETCH_MS) {
                TrackLog.w(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${request.url()}")
            } else {
                TrackLog.d(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${request.url()}")
            }
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    private val init by lazy { NewPipe.init(OkHttpDownloader()) }

    private val extractorClient by lazy {
        Http.client.newBuilder()
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .callTimeout(EXTRACTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val PING_INTERVAL_SECONDS = 5L

    private const val EXTRACTOR_TIMEOUT_SECONDS = 5L

    suspend fun resolve(videoId: String): String {
        init

        recent[videoId]
            ?.takeIf { MonotonicClock.nowMs() - it.at < URL_TTL_MS }
            ?.let { return it.url }

        unplayableReason(videoId)?.let { throw PermanentlyUnplayableException(it) }

        val stream = coalescedResolve(videoId)

        NerdStats.onStreamPicked(videoId, stream.kbps)
        stream.loudnessDb?.let { loudness[videoId] = it }
        remember(videoId, stream.url)
        return stream.url
    }

    private val loudness = ConcurrentHashMap<String, Double>()

    fun loudnessDbFor(videoId: String): Double? = loudness[videoId]

    class PermanentlyUnplayableException(reason: String) : IOException(reason)

    private val unplayable = ConcurrentHashMap<String, Verdict>()

    private class Verdict(val reason: String, val at: Long)

    private fun unplayableReason(videoId: String): String? {
        val entry = unplayable[videoId] ?: return null
        if (MonotonicClock.nowMs() - entry.at < UNPLAYABLE_TTL_MS) return entry.reason
        unplayable.remove(videoId)
        return null
    }

    private fun rememberUnplayable(videoId: String, reason: String) {
        if (unplayable.size > MAX_REMEMBERED) unplayable.clear()
        unplayable[videoId] = Verdict(reason, MonotonicClock.nowMs())
    }

    fun onSessionChanged() {
        InnerTubeXResolver.onSessionChanged()
        unplayable.clear()
        UploadedTracks.clear()
    }

    private const val UNPLAYABLE_TTL_MS = 10 * 60 * 1000L

    private suspend fun coalescedResolve(videoId: String): Stream {
        inFlight[videoId]?.let { return it.await() }
        val walk = resolverScope.async(TrackLog.about(videoId), start = CoroutineStart.LAZY) {
            resolveUncached(videoId)
        }
        val running = inFlight.putIfAbsent(videoId, walk)
        if (running != null) {
            walk.cancel()
            return running.await()
        }
        walk.invokeOnCompletion { inFlight.remove(videoId, walk) }
        walk.start()
        return walk.await()
    }

    private val inFlight = ConcurrentHashMap<String, Deferred<Stream>>()

    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun resolveUncached(videoId: String): Stream {
        val resolveStart = MonotonicClock.nowMs()
        val stream = try {
            timed("$videoId InnerTubeX") { innerTubeXStreamAllowingUploads(videoId) }
                ?.let { found ->
                    if (!isLiveUrl(found.url)) found
                    else timed("$videoId live HLS") { liveHlsStream(videoId) } ?: found
                }
                ?: fallbackEngine()?.let { engine -> timed("$videoId fallback engine") { fallbackStream(engine, videoId) } }
                ?: run {
                    TrackLog.w(TAG, "InnerTubeX found no usable stream for $videoId; falling back to extraction")
                    timed("$videoId newPipeStream") { newPipeStream(videoId, ::pickForQuality) }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            TrackLog.w(
                TAG,
                "resolve hit a linkage failure for $videoId after " +
                    "${MonotonicClock.nowMs() - resolveStart}ms: ${e.javaClass.name}: ${e.message}",
                e,
            )
            throw IOException("Stream resolution cannot run on this device: $e", e)
        } catch (e: Exception) {
            TrackLog.w(
                TAG,
                "resolve failed for $videoId after ${MonotonicClock.nowMs() - resolveStart}ms: " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
            permanentReason(e)?.let { reason ->
                rememberUnplayable(videoId, reason)
                TrackLog.w(TAG, "$videoId is not playable: $reason; not asking again for 10 minutes")
                throw PermanentlyUnplayableException(reason)
            }
            throw e
        }
        TrackLog.d(TAG, "TIMING $videoId total resolve: ${MonotonicClock.nowMs() - resolveStart}ms")
        return stream
    }

    private suspend fun innerTubeXStream(
        videoId: String,
        maxKbps: Int = maxKbps(),
        requireM4a: Boolean = false,
        uploaded: Boolean = false,
        escalateToUpload: Boolean = true,
        playRefusedUpload: Boolean = false,
    ): Stream? {
        val skip = mutableSetOf<String>()
        repeat(INNERTUBEX_ATTEMPTS) { attempt ->
            val found = try {
                InnerTubeXResolver.extract(videoId, maxKbps, skip, requireM4a, uploaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "InnerTubeX failed for $videoId: ${e.javaClass.simpleName}: ${e.message}")
                return null
            } ?: return null
            val verdict = timed("$videoId InnerTubeX ${found.profileId} probe") { probe(found.url) }
            if (verdict == Probe.OK) {
                TrackLog.d(TAG, "resolved $videoId via InnerTubeX ${found.profileId} @ ${found.kbps}kbps")
                return Stream(found.url, found.kbps, found.mimeType, found.loudnessDb)
            }
            TrackLog.w(TAG, "InnerTubeX ${found.profileId} minted an unusable URL for $videoId: $verdict")

            if (playRefusedUpload && uploaded && verdict == Probe.REFUSED) {
                TrackLog.w(TAG, "playing $videoId's upload stream despite the probe: it is the only copy")
                return Stream(found.url, found.kbps, found.mimeType, found.loudnessDb)
            }
            skip += found.profileId

            if (escalateToUpload && attempt == 0 && !uploaded &&
                verdict == Probe.REFUSED && !Innertube.cookie.isNullOrBlank()
            ) {
                askAsUpload(videoId, maxKbps, requireM4a)?.let { return it }
            }
        }
        return null
    }

    private suspend fun askAsUpload(videoId: String, maxKbps: Int, requireM4a: Boolean): Stream? {
        TrackLog.d(TAG, "first stream for $videoId was refused; asking as an upload")
        val found = try {
            InnerTubeXResolver.extract(videoId, maxKbps, emptySet(), requireM4a, uploaded = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "asking for $videoId as an upload failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (found == null) {
            TrackLog.w(TAG, "no stream for $videoId when asked as an upload")
            return null
        }
        val verdict = timed("$videoId InnerTubeX ${found.profileId} upload probe") { probe(found.url) }
        if (verdict != Probe.OK) {
            TrackLog.w(TAG, "InnerTubeX ${found.profileId} as an upload for $videoId: $verdict")
            return null
        }
        TrackLog.d(TAG, "resolved $videoId as an upload via InnerTubeX ${found.profileId} @ ${found.kbps}kbps")
        UploadedTracks.mark(videoId)
        return Stream(found.url, found.kbps, found.mimeType, found.loudnessDb)
    }

    private suspend fun innerTubeXStreamAllowingUploads(
        videoId: String,
        maxKbps: Int = maxKbps(),
        requireM4a: Boolean = false,
    ): Stream? {
        val known = UploadedTracks.contains(videoId)
        if (known) {
            innerTubeXStream(videoId, maxKbps, requireM4a, uploaded = true, playRefusedUpload = true)
                ?.let { return it }
            TrackLog.w(TAG, "$videoId is marked as an upload but did not resolve as one; trying an ordinary ask")
            return innerTubeXStream(videoId, maxKbps, requireM4a, uploaded = false, escalateToUpload = false)
        }
        return innerTubeXStream(videoId, maxKbps, requireM4a)
    }

    fun mediaHeadersFor(url: String): Map<String, String> =
        fallbackEngine()?.headersFor(url) ?: InnerTubeXResolver.headersFor(url) ?: PlayerClient.forStreamUrl(url).mediaHeaders()

    private suspend fun fallbackStream(engine: FallbackEngine, videoId: String): Stream? {
        val found = try {
            engine.stream(videoId, maxKbps())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "fallback engine failed for $videoId: ${e.javaClass.name}: ${e.message}")
            null
        } ?: return null
        val verdict = timed("$videoId fallback engine probe") { probe(found.url) }
        if (verdict != Probe.OK) {
            TrackLog.w(TAG, "fallback engine's stream for $videoId failed its probe: $verdict")
            return null
        }
        TrackLog.d(TAG, "resolved $videoId via the fallback engine @ ${found.kbps}kbps")
        return found
    }

    private const val INNERTUBEX_ATTEMPTS = 3

    private suspend inline fun <T> timed(label: String, block: suspend () -> T): T {
        val start = MonotonicClock.nowMs()
        return block().also { TrackLog.d(TAG, "TIMING $label: ${MonotonicClock.nowMs() - start}ms") }
    }

    class Stream(val url: String, val kbps: Int, val mimeType: String, val loudnessDb: Double? = null) {

        val downloadExtension: String
            get() = when {
                "mp4" in mimeType || "m4a" in mimeType -> "m4a"
                else -> "webm"
            }

        val downloadMimeType: String
            get() = if (downloadExtension == "m4a") "audio/mp4" else "audio/webm"
    }

    suspend fun resolveForDownload(
        videoId: String,
        maxKbps: Int,
        requireM4a: Boolean = false,
    ): Stream {
        val stream = downloadStream(videoId, maxKbps, requireM4a)
        val allowed = if (requireM4a) setOf("m4a") else setOf("m4a", "webm")
        check(stream.downloadExtension in allowed) {
            "Can't save ${stream.mimeType} — try again"
        }
        return stream
    }

    private suspend fun downloadStream(videoId: String, maxKbps: Int, requireM4a: Boolean): Stream =
        withContext(TrackLog.about(videoId)) {
            init

            var offered = false

            val found = innerTubeXStreamAllowingUploads(videoId, maxKbps, requireM4a)
                ?.takeIf { if (requireM4a) it.downloadExtension == "m4a" else it.downloadExtension == "webm" }
            if (found != null) {
                offered = true
                if (found.kbps <= maxKbps) return@withContext found
                TrackLog.d(TAG, "InnerTubeX's ${found.kbps}kbps is over the ${maxKbps}kbps download ceiling for $videoId")
            }

            val format = if (requireM4a) "MP4" else "Opus"
            if (found == null) TrackLog.w(TAG, "InnerTubeX found no usable $format for $videoId; extracting")
            runCatching {
                newPipeStream(videoId) { candidates ->
                    val matching = candidates.filter {
                        if (requireM4a) it.second.isM4a else it.second.isWebmOpus
                    }
                    underCeiling(matching, maxKbps)
                        ?.also { offered = true }
                }
            }.onSuccess { return@withContext it }
                .onFailure { TrackLog.w(TAG, "extraction found no $format for $videoId: ${it.message}") }

            found?.let { return@withContext it }
            if (offered) error("Couldn't reach a downloadable copy just now — try again")
            error("No downloadable audio for this track")
        }

    private fun <T> pickForQuality(candidates: List<Pair<Int, T>>): T? =
        underCeiling(candidates, maxKbps())

    private fun <T> underCeiling(candidates: List<Pair<Int, T>>, maxKbps: Int): T? {
        if (candidates.isEmpty()) return null
        val withinBudget = candidates.filter { it.first <= maxKbps }
        return (withinBudget.maxByOrNull { it.first } ?: candidates.minByOrNull { it.first })
            ?.second
    }

    private val jsPlayerMutex = Mutex()

    private suspend fun <T> jsPlayerManager(block: () -> T): T = jsPlayerMutex.withLock { block() }

    suspend fun signatureTimestamp(videoId: String): Int? {
        cachedSignatureTimestamp?.let { return it }
        return runCatching {
            jsPlayerManager { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }
        }
            .onFailure { TrackLog.w(TAG, "no signature timestamp: ${it.message}") }
            .getOrNull()
            ?.also { cachedSignatureTimestamp = it }
    }

    @Volatile
    private var cachedSignatureTimestamp: Int? = null

    private enum class Probe {
        OK,

        REFUSED,

        UNREACHABLE,
    }

    private fun probe(url: String): Probe {
        val length = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
        val start = if (length != null && length > AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) AUTH_BOUNDARY_BYTES else 0L
        val window = PlayerClient.rangeBytesFor(url)
        val range = when {
            window != Long.MAX_VALUE -> "bytes=$start-${minOf(start + window, length ?: Long.MAX_VALUE) - 1}"
            length != null -> "bytes=$start-${length - 1}"
            else -> "bytes=$start-"
        }
        val builder = okhttp3.Request.Builder().url(url)
            .header("Range", range)
        val sentHeaders = mediaHeadersFor(url)
        sentHeaders.forEach { (name, value) -> builder.header(name, value) }
        return try {
            prober.newCall(builder.build()).execute().use { response ->
                val contentType = response.header("Content-Type")
                val verdict = when {
                    response.code in REFUSAL_CODES -> Probe.REFUSED
                    response.code !in 200..299 && response.code != 416 -> Probe.UNREACHABLE
                    contentType?.startsWith("audio/") != true -> Probe.REFUSED
                    response.body?.source()?.request(PROBE_READ_BYTES) != true -> Probe.UNREACHABLE
                    else -> Probe.OK
                }
                if (verdict != Probe.OK) {
                    TrackLog.w(
                        TAG,
                        "probe $verdict: HTTP ${response.code} type=$contentType " +
                            "range=$range ${describeMediaUrl(url)} " +
                            "sent=${sentHeaders.keys.sorted()}",
                    )
                }
                verdict
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "probe failed: ${e.message}")
            Probe.UNREACHABLE
        }
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)

    private fun describeMediaUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return "url=unparseable"
        return "host=${parsed.host} path=${parsed.encodedPath} " +
            "c=${parsed.queryParameter("c")} itag=${parsed.queryParameter("itag")} " +
            "mime=${parsed.queryParameter("mime")} clen=${parsed.queryParameter("clen")} " +
            "pot=${parsed.queryParameter("pot") != null}"
    }

    private val prober by lazy {
        Http.client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val PROBE_TIMEOUT_SECONDS = 6L

    private const val AUTH_BOUNDARY_BYTES = 1024L * 1024

    private const val PROBE_READ_BYTES = 16L * 1024

    fun onPlaybackRefused(url: String, responseCode: Int) {
        if (responseCode !in REFUSAL_CODES) return
        if (url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") != true) return
        val videoId = InnerTubeXResolver.onRefused(url)
            ?: recent.entries.firstOrNull { it.value.url == url }?.key
            ?: return
        recent.remove(videoId)
    }

    private suspend fun newPipeStream(
        videoId: String,
        select: (List<Pair<Int, AudioStream>>) -> AudioStream?,
    ): Stream {
        var failure: Exception? = null
        repeat(EXTRACTION_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(EXTRACTION_RETRY_MS * attempt)
            try {
                return extractStream(videoId, select)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                throw IOException("Extractor cannot run on this device: $e", e)
            } catch (e: Exception) {
                permanentReason(e)?.let { reason ->
                    TrackLog.w(TAG, "extraction will not succeed for $videoId: $reason")
                    throw e
                }
                TrackLog.w(
                    TAG,
                    "extraction attempt ${attempt + 1} of $EXTRACTION_ATTEMPTS failed for $videoId: ${e.message}",
                )
                failure = e
            }
        }
        throw failure ?: IllegalStateException("Track unavailable: no audio streams")
    }

    fun isLiveUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.queryParameter("live") == "1" ||
            parsed.queryParameter("source") == "yt_live_broadcast"
    }

    private suspend fun liveHlsStream(videoId: String): Stream? = try {
        extractionGate.withLock {
            withContext(Dispatchers.IO) {
                val extractor = ServiceList.YouTube.getStreamExtractor(
                    "https://www.youtube.com/watch?v=$videoId",
                )
                extractor.fetchPage()
                val live = extractor.streamType == StreamType.LIVE_STREAM ||
                    extractor.streamType == StreamType.AUDIO_LIVE_STREAM
                extractor.hlsUrl.takeIf { live && it.isNotBlank() }?.let {
                    TrackLog.d(TAG, "live stream $videoId served from its HLS playlist")
                    Stream(url = it, kbps = 0, mimeType = "application/x-mpegURL")
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "live HLS lookup failed for $videoId: ${e.message}")
        null
    }

    private fun permanentReason(e: Throwable): String? = when (e) {
        is PermanentlyUnplayableException -> e.message ?: "This track cannot be played"
        is AgeRestrictedContentException ->
            if (Innertube.cookie == null) {
                "This track is age-restricted. Sign in to YouTube to play it."
            } else {
                "YouTube will not serve this age-restricted track to this app."
            }
        is GeographicRestrictionException -> "This track isn't available in your country"
        is UnsupportedContentInCountryException -> "This track isn't available in your country"
        is PaidContentException -> "This track is paid content"
        is YoutubeMusicPremiumContentException -> "This track needs YouTube Music Premium"
        is PrivateContentException -> "This track is private"
        is AccountTerminatedException -> "The channel behind this track was terminated"
        is SoundCloudGoPlusContentException -> "This track needs SoundCloud Go+"
        else -> e.cause?.takeIf { it !== e }?.let(::permanentReason)
    }

    private const val EXTRACTION_ATTEMPTS = 3

    private const val EXTRACTION_RETRY_MS = 1000L

    private val extractionGate = Mutex()

    private suspend fun extractStream(
        videoId: String,
        select: (List<Pair<Int, AudioStream>>) -> AudioStream?,
    ): Stream = extractionGate.withLock {
        withContext(Dispatchers.IO) {
            val waited = MonotonicClock.nowMs()
            val extractor = ServiceList.YouTube.getStreamExtractor(
                "https://www.youtube.com/watch?v=$videoId",
            )
            extractor.fetchPage()
            val candidates = extractor.audioStreams
                .filter {
                    !it.content.isNullOrBlank() &&
                        it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
                }
            val stream = select(candidates.map { it.averageBitrate to it })
                ?: error("Track unavailable: no audio streams")
            TrackLog.d(
                TAG,
                "NewPipe picked ${stream.format?.name} @ ${stream.averageBitrate}kbps " +
                    "(extraction held the gate ${MonotonicClock.nowMs() - waited}ms)",
            )
            Stream(
                url = stream.content,
                kbps = stream.averageBitrate,
                mimeType = stream.mime,
            )
        }
    }

    private val AudioStream.mime: String get() = format?.mimeType.orEmpty()

    private val AudioStream.isM4a: Boolean
        get() = format == MediaFormat.M4A

    private val AudioStream.isWebmOpus: Boolean
        get() = format == MediaFormat.WEBMA_OPUS

    suspend fun contentLength(videoId: String): Long? =
        resolve(videoId).toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()

    private class Resolved(val url: String, val at: Long)

    private val recent = ConcurrentHashMap<String, Resolved>()

    private const val URL_TTL_MS = 20 * 60 * 1000L

    private const val MAX_REMEMBERED = 32

    private fun remember(videoId: String, url: String) {
        if (recent.size >= MAX_REMEMBERED) {
            val cutoff = MonotonicClock.nowMs() - URL_TTL_MS
            recent.entries.removeAll { it.value.at < cutoff }
            if (recent.size >= MAX_REMEMBERED) recent.clear()
        }
        recent[videoId] = Resolved(url, MonotonicClock.nowMs())
    }
}
