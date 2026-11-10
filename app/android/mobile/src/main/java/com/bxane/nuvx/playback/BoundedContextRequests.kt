package com.bxane.nuvx.playback

import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

object BoundedContextRequests {
    data class Request(
        val songs: List<Song>,
        val startIndex: Int,
        val source: QueueSource,
        val continuationToken: String?,
        val shuffle: Boolean,
        val handoffToken: String? = null,
    )

    data class Snapshot(
        val request: Request,
        val songs: List<Song>,
        val continuationToken: String?,
        val version: Long,
        val serviceOwned: Boolean = false,
        val released: Boolean = false,
    )

    private data class Pending(
        val token: String,
        val request: Request,
    )

    private var pending: Pending? = null
    private var active: Snapshot? = null
    private var version = 0L

    private val _snapshot = MutableStateFlow<Snapshot?>(null)

    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    @Synchronized
    fun put(request: Request): String {
        val token = UUID.randomUUID().toString()
        val stored = request.copy(
            songs = request.songs.toList(),
            handoffToken = token,
        )
        pending = Pending(token, stored)
        version += 1
        publishLocked(
            Snapshot(
                request = stored,
                songs = stored.songs,
                continuationToken = stored.continuationToken,
                version = version,
            ),
        )
        return token
    }

    @Synchronized
    fun take(token: String?): Request? {
        if (token == null) return null
        val current = pending ?: return null
        if (current.token != token) return null
        pending = null
        return current.request
    }

    @Synchronized
    fun publish(
        request: Request,
        songs: List<Song>,
        continuationToken: String?,
    ): Boolean {
        val current = active ?: return false
        if (current.request.handoffToken != request.handoffToken ||
            request.handoffToken == null
        ) return false
        version += 1
        val updatedRequest = current.request.copy(
            songs = songs.toList(),
            continuationToken = continuationToken,
        )
        publishLocked(
            Snapshot(
                request = updatedRequest,
                songs = updatedRequest.songs,
                continuationToken = updatedRequest.continuationToken,
                version = version,
                serviceOwned = true,
            ),
        )
        return true
    }

    @Synchronized
    fun release(handoffToken: String) {
        val current = active ?: return
        if (current.request.handoffToken != handoffToken) return
        version += 1
        publishLocked(current.copy(version = version, serviceOwned = false, released = true))
    }

    @Synchronized
    fun publish(request: Request, snapshot: ContextSnapshot): Boolean =
        publish(request, snapshot.songs, snapshot.continuationToken)

    @Synchronized
    fun restore(request: Request): Request {
        val stored = request.copy(
            songs = request.songs.toList(),
            handoffToken = request.handoffToken ?: UUID.randomUUID().toString(),
        )
        version += 1
        publishLocked(
            Snapshot(
                request = stored,
                songs = stored.songs,
                continuationToken = stored.continuationToken,
                version = version,
                serviceOwned = true,
            ),
        )
        return stored
    }

    @Synchronized
    fun publish(
        handoffToken: String,
        songs: List<Song>,
        continuationToken: String?,
    ): Boolean {
        val request = active?.request?.takeIf { it.handoffToken == handoffToken } ?: return false
        return publish(request, songs, continuationToken)
    }

    @Synchronized
    fun discard(token: String) {
        if (pending?.token == token) pending = null
        if (active?.request?.handoffToken == token) {
            active = null
            publishLocked(null)
        }
    }

    @Synchronized
    fun clear() {
        pending = null
        active = null
        publishLocked(null)
    }

    private fun publishLocked(snapshot: Snapshot?) {
        active = snapshot
        _snapshot.value = snapshot
    }
}
