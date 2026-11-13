package com.bxane.nuvx.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

































object RemoteArtworkStore {


    private const val MAX_PICTURE_BYTES = 2L * 1024 * 1024


    private const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

    private val mutex = Mutex()

    private val memory = HashMap<String, EmbeddedArt.Picture>()


    private val order = ArrayDeque<String>()
    private var totalBytes = 0L

    private val inFlight = HashMap<String, CompletableDeferred<EmbeddedArt.Picture?>>()









    suspend fun resolveArt(fileUrl: String, authHeader: String? = null): EmbeddedArt.Picture? {
        mutex.withLock { hitLocked(fileUrl) }?.let { return it }





        val (waiting, mine) = mutex.withLock {
            val existing = inFlight[fileUrl]
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<EmbeddedArt.Picture?>().also { inFlight[fileUrl] = it } to true
            }
        }
        if (!mine) return runCatching { waiting.await() }.getOrNull()

        var result: EmbeddedArt.Picture? = null
        try {
            result = RemoteArtReader.picture(fileUrl, authHeader)
        } catch (e: CancellationException) {



            mutex.withLock { inFlight.remove(fileUrl) }
            waiting.complete(null)
            throw e
        } catch (_: Throwable) {
            result = null
        }


        mutex.withLock {
            inFlight.remove(fileUrl)
            storeLocked(fileUrl, result)
        }
        waiting.complete(result)
        return result
    }








    suspend fun clear() = mutex.withLock {
        memory.clear()
        order.clear()
        totalBytes = 0
    }


    private fun hitLocked(key: String): EmbeddedArt.Picture? {
        val picture = memory[key] ?: return null
        touchLocked(key)
        return picture
    }

    private fun touchLocked(key: String) {



        if (order.remove(key)) order.addLast(key)
    }


    private fun storeLocked(key: String, picture: EmbeddedArt.Picture?) {
        if (picture == null || picture.bytes.size > MAX_PICTURE_BYTES) return
        if (key in memory) return
        memory[key] = picture
        order.addLast(key)
        totalBytes += picture.bytes.size
        while (totalBytes > MAX_TOTAL_BYTES && order.isNotEmpty()) {
            val oldest = order.removeFirst()
            memory.remove(oldest)?.let { totalBytes -= it.bytes.size }
        }
    }
}
