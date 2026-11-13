package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.DebugLog
import com.bxane.nuvx.data.http.Http
import com.bxane.nuvx.data.http.HttpStatusException
import com.bxane.nuvx.data.http.RawHttpBytes
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSMutableData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite






















object StreamDownloadBridge {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobsLock = NSLock()
    private val downloadJobs = mutableMapOf<String, Job>()

    fun interface DownloadCallback {
        fun onResult(path: String?, message: String?)
    }
    fun interface ProgressCallback {
        fun onProgress(received: Long, total: Long)
    }

    fun downloadWithProgress(downloadId: String, url: String, headers: Map<String, String>, progress: ProgressCallback, callback: DownloadCallback) {
        startStream(downloadId, url, headers, ready = DownloadCallback { _, _ -> }, done = callback, progress = progress)
    }


    fun download(downloadId: String, url: String, headers: Map<String, String>, callback: DownloadCallback) {
        startStream(downloadId, url, headers, ready = DownloadCallback { _, _ -> }, done = callback)
    }


    fun cancel(downloadId: String) {
        jobsLock.lock()
        val job = downloadJobs.remove(downloadId)
        jobsLock.unlock()
        job?.cancel()
    }
























    fun streamToFile(
        url: String,
        headers: Map<String, String>,
        ready: DownloadCallback,
        done: DownloadCallback,
    ) {
        DirectStreamDownload.create(bridgeScope, url, headers, ready, done).start()
    }

    fun streamSourceRequest(requestId: String, url: String, headers: Map<String, String>, ready: DownloadCallback, done: DownloadCallback) {
        val job = DirectStreamDownload.create(bridgeScope, url, headers, ready, done)
        register(requestId, job)
        job.start()
    }

    fun streamTrackRequest(requestId: String, videoId: String, maxKbps: Int, url: String, headers: Map<String, String>,
                           expectedMimeType: String?, firstRangeRetry: Int, ready: DownloadCallback, done: DownloadCallback) {
        startStream(requestId, url, headers, ready, done, videoId, maxKbps, expectedMimeType, firstRangeRetry)
    }

    private fun register(id: String, job: Job) {
        jobsLock.lock(); downloadJobs[id] = job; jobsLock.unlock()
        job.invokeOnCompletion {
            jobsLock.lock()
            if (downloadJobs[id] === job) downloadJobs.remove(id)
            jobsLock.unlock()
        }
    }







    fun streamTrackToFile(
        videoId: String,
        maxKbps: Int,
        url: String,
        headers: Map<String, String>,
        expectedMimeType: String?,
        firstRangeRetry: Int,
        ready: DownloadCallback,
        done: DownloadCallback,
    ) {
        startStream(null, url, headers, ready, done, videoId = videoId, maxKbps = maxKbps,
            expectedMimeType = expectedMimeType, firstRangeRetry = firstRangeRetry)
    }







    fun networkChanged() {
        networkGeneration.value = networkGeneration.value + 1
        DebugLog.d("network path changed; active streams will re-request at their offsets")
    }

    private val networkGeneration = MutableStateFlow(0L)
    private val networkAvailable = MutableStateFlow(true)
    internal suspend fun awaitConnectivity() { networkAvailable.first { it } }

    fun networkStatus(available: Boolean) {
        if (networkAvailable.value != available) {
            networkAvailable.value = available
            networkChanged()
        }
    }

    private class NetworkChanged : Exception("network path changed")





    private suspend fun fetchRange(url: String, headers: Map<String, String>, maxBytes: Int): RawHttpBytes? {
        val startGeneration = networkGeneration.value
        return coroutineScope {
            val fetch = async { Http.getBytesRaw(url, headers, maxBytes, CHUNK_TIMEOUT_MS) }
            val watcher = launch {
                networkGeneration.first { it != startGeneration }
                fetch.cancel(CancellationException("network changed"))
            }
            try {
                fetch.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                watcher.cancel()
            }
        }
    }


    private suspend fun fetchFirstRange(url: String, headers: Map<String, String>): RawHttpBytes? {
        val startGeneration = networkGeneration.value
        return coroutineScope {
            val fetch = async { Http.getBytesRaw(url, headers, FIRST_RANGE_BYTES.toInt(), CHUNK_TIMEOUT_MS) }
            val watcher = launch {
                networkGeneration.first { it != startGeneration }
                fetch.cancel(CancellationException("network changed"))
            }
            try {
                fetch.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                watcher.cancel()
            }
        }
    }


    private suspend fun backoff(millis: Long) {
        val startGeneration = networkGeneration.value
        withTimeoutOrNull(millis) { networkGeneration.first { it != startGeneration } }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun startStream(
        downloadId: String?,
        url: String,
        headers: Map<String, String>,
        ready: DownloadCallback,
        done: DownloadCallback,
        videoId: String? = null,
        maxKbps: Int = Int.MAX_VALUE,
        expectedMimeType: String? = null,
        firstRangeRetry: Int = 0,
        progress: ProgressCallback? = null,
    ) {
        val job = bridgeScope.launch(start = CoroutineStart.LAZY) {
            var tmpPath: String? = null
            var file: CPointer<FILE>? = null
            try {
                DebugLog.d("stream starting: ${url.take(120)}")

                var currentUrl = url
                var currentHeaders = headers
                val chunkSize = minOf(PlayerClient.rangeBytesFor(url), 1024L * 1024)
                var declaredLength = PlayerClient.lengthFromUrl(url)
                check(declaredLength == null || declaredLength in 1..MAX_BYTES) { "Invalid audio download size" }
                val tmpDir = NSTemporaryDirectory()
                val ext = when {
                    url.contains("mime=video", ignoreCase = true) ||
                        url.contains("itag=18") -> "mp4"
                    else -> "m4a"
                }
                val path = "$tmpDir/nuvx-${NSUUID().UUIDString}.$ext"
                tmpPath = path



                declaredLength?.let { clen ->
                    val lenPath = "$path.len"
                    val digits = clen.toString().encodeToByteArray()
                    val lenFile = fopen(lenPath, "wb")
                    if (lenFile != null) {
                        digits.usePinned { pinned ->
                            fwrite(
                                pinned.addressOf(0),
                                1.convert(),
                                digits.size.convert(),
                                lenFile,
                            )
                        }
                        fclose(lenFile)
                    }
                }
                NSMutableData().writeToFile("$path.grow", atomically = true)

                var offset = 0L
                var consecutiveEmpty = 0
                var readyFired = false
                var failures = 0
                var remints = 0

                while (true) {
                    networkAvailable.first { it }
                    currentCoroutineContext().ensureActive()



                    val want = minOf(if (offset == 0L) minOf(FIRST_RANGE_BYTES, chunkSize) else chunkSize,
                        declaredLength?.let { it - offset } ?: chunkSize)
                    check(want > 0) { "Invalid audio range offset" }
                    val rangeEnd = offset + want - 1
                    val rangeHeaders = currentHeaders + ("Range" to "bytes=$offset-$rangeEnd")

                    val data = try {
                        if (offset == 0L) {
                            val response = fetchFirstRange(currentUrl, rangeHeaders) ?: throw NetworkChanged()
                            if (response.status in REFUSAL_CODES) throw HttpStatusException(response.status)
                            if (response.status !in 200..299) throw HttpStatusException(response.status)
                            val bytes = validateFirstMediaRange(response, expectedMimeType)
                            declaredLength = validateAppendedRange(response, offset, want, declaredLength)
                            bytes
                        } else {
                            val response = fetchRange(currentUrl, rangeHeaders, want.toInt() + 1) ?: throw NetworkChanged()
                            declaredLength = validateAppendedRange(response, offset, want, declaredLength)
                            response.body!!
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val code = httpCodeOf(e)
                        if (offset == 0L && !readyFired && videoId != null &&
                            (e is InvalidFirstMediaRangeException || (code != null && code in REFUSAL_CODES))) {
                            val status = (e as? InvalidFirstMediaRangeException)?.responseCode ?: code ?: 0



                            StreamResolver.onFirstRangeRejected(currentUrl, status, excludeClient = true)
                            throw e
                        }
                        if (e is InvalidFirstMediaRangeException) throw e
                        if (e is InvalidMediaRangeException) throw e
                        if (!networkAvailable.value || e is NetworkChanged) continue
                        if (code != null && code in REFUSAL_CODES) {
                            DebugLog.w("stream refused with $code at offset $offset")
                            StreamResolver.onPlaybackRefused(currentUrl, code)



                            val fresh = if (readyFired && videoId != null && remints < MAX_REMINTS) {
                                remints++
                                runCatching { StreamResolver.resolve(videoId, maxKbps) }.getOrNull()
                                    ?.takeIf { sameRendition(currentUrl, it.url) }
                            } else null
                            if (fresh == null) throw e
                            DebugLog.d("stream re-minted at offset $offset; resuming on a fresh URL")
                            currentUrl = fresh.url
                            currentHeaders = fresh.headers
                            continue
                        }


                        failures++
                        if (failures > MAX_TRANSPORT_RETRIES) {
                            DebugLog.w("stream chunk failed at offset $offset after $failures tries: ${e.message}")
                            throw e
                        }
                        val wait = BACKOFF_MS[minOf(failures - 1, BACKOFF_MS.lastIndex)]
                        DebugLog.d("stream chunk failed at offset $offset (${e.message}); retry $failures in ${wait}ms")
                        if (e !is NetworkChanged) backoff(wait)
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    failures = 0

                    if (data.isEmpty()) {
                        consecutiveEmpty++
                        DebugLog.d("stream returned an empty chunk ($consecutiveEmpty), stopping")
                        if (consecutiveEmpty >= 2) error("Audio ended before its declared length")
                        continue
                    }
                    consecutiveEmpty = 0

                    if (file == null) {
                        file = fopen(path, "wb")
                            ?: throw IllegalStateException("open $path failed")
                    }
                    data.usePinned { pinned ->
                        val written = fwrite(
                            pinned.addressOf(0),
                            1.convert(),
                            data.size.convert(),
                            file,
                        )
                        if (written.toLong() != data.size.toLong()) {
                            throw IllegalStateException("short write at offset $offset")
                        }
                    }
                    fflush(file)
                    offset += data.size
                    progress?.onProgress(offset, declaredLength ?: 0)

                    if (!readyFired) {
                        currentCoroutineContext().ensureActive()
                        readyFired = true
                        DebugLog.d("first chunk ready: $path ($offset bytes)")
                        ready.onResult(path, null)
                    }



                    if (declaredLength != null) {
                        if (offset >= declaredLength) break
                    } else if (data.size < want) break
                    check(offset <= MAX_BYTES) { "Audio exceeds the download size limit" }
                }

                file?.let { fflush(it); fclose(it) }
                file = null

                if (!readyFired) {
                    currentCoroutineContext().ensureActive()
                    done.onResult(null, "Download returned no bytes")
                    return@launch
                }

                currentCoroutineContext().ensureActive()
                check(declaredLength == null || offset == declaredLength) { "Incomplete audio download" }
                NSMutableData().writeToFile("$path.complete", atomically = true)
                DebugLog.d("stream complete: $path ($offset bytes)")
                currentCoroutineContext().ensureActive()
                done.onResult(path, null)
            } catch (e: CancellationException) {


                tmpPath?.let { path ->
                    listOf(path, "$path.len", "$path.grow", "$path.complete", "$path.failed").forEach {
                        NSFileManager.defaultManager.removeItemAtPath(it, null)
                    }
                }
                done.onResult(null, "Download cancelled")
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                DebugLog.e("stream failed", e)



                tmpPath?.let {
                    NSMutableData().writeToFile("$it.failed", atomically = true)
                    NSMutableData().writeToFile("$it.complete", atomically = true)
                }





                val code = httpCodeOf(e)
                val detail = when (e) {
                    is InvalidFirstMediaRangeException -> "$INVALID_FIRST_RANGE_PREFIX${e.message ?: "media rejected"}"
                    else -> e.message ?: e.toString()
                }
                done.onResult(
                    null,
                    if (code != null && code in REFUSAL_CODES) "$REFUSED_PREFIX$code $detail"
                    else detail,
                )
            } finally {
                file?.let { fclose(it) }
            }
        }
        if (downloadId != null) {
            jobsLock.lock()
            downloadJobs[downloadId] = job
            jobsLock.unlock()
            job.invokeOnCompletion {
                jobsLock.lock()
                if (downloadJobs[downloadId] === job) downloadJobs.remove(downloadId)
                jobsLock.unlock()
            }
        }
        job.start()
    }


    private fun sameRendition(old: String, new: String): Boolean {
        val oldLen = PlayerClient.lengthFromUrl(old) ?: return false
        return oldLen == PlayerClient.lengthFromUrl(new) && itagOf(old) != null && itagOf(old) == itagOf(new)
    }

    private fun itagOf(url: String): String? =
        Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.get(1)


    private fun httpCodeOf(e: Throwable): Int? =
        e.message?.let { message ->
            Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        }










    fun isRefusal(message: String?): Boolean = message?.startsWith(REFUSED_PREFIX) == true

    fun isInvalidFirstRange(message: String?): Boolean = message?.startsWith(INVALID_FIRST_RANGE_PREFIX) == true


    fun refusalCode(message: String?): Int? =
        if (message == null || !isRefusal(message)) null
        else message.removePrefix(REFUSED_PREFIX).takeWhile { it.isDigit() }.toIntOrNull()

    private const val REFUSED_PREFIX = "refused: "
    private const val INVALID_FIRST_RANGE_PREFIX = "invalid first range: "
    private val REFUSAL_CODES = setOf(403, 404, 410)



    private const val MAX_BYTES = 200L * 1024 * 1024


    private const val FIRST_RANGE_BYTES = 128L * 1024


    private const val CHUNK_TIMEOUT_MS = 15_000L


    private const val MAX_TRANSPORT_RETRIES = 8
    private val BACKOFF_MS = longArrayOf(250, 500, 1_000, 2_000, 4_000, 8_000, 8_000, 8_000)


    private const val MAX_REMINTS = 2
}
