package com.bxane.nuvx.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.data.YtMusicRepository
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.model.QueueTier
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.isUnresolvedSpotify
import com.bxane.nuvx.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random

private val CONTINUATION_RETRIES_MS = longArrayOf(2_000L, 5_000L, 15_000L)

internal class BoundedQueueController(
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    private val isTransitioning: () -> Boolean,
    private val isCasting: () -> Boolean,
    private val refreshLayouts: () -> Unit,
    private val onContextStarted: () -> Unit,
    private val onRestoring: () -> Unit,
) {
    private val registry = ContextRegistry { snapshot ->
        scope.launch {
            val window = window ?: return@launch
            if (snapshot.contextToken != token) return@launch
            window.extendTo(snapshot.songs.size)
            request?.let { BoundedContextRequests.publish(it, snapshot) }
            player()?.let { saveSnapshot(it) }
            scheduleTopUp()
        }
    }

    private var window: QueueWindow? = null
    private var token: String? = null
    private var source: QueueSource? = null
    private var request: BoundedContextRequests.Request? = null
    private var canonicalJob: Job? = null
    private var continuationJob: Job? = null
    private var topUpJob: Job? = null
    private var editState = BoundedQueueEditState()

    val active: Boolean get() = window != null

    val hasPendingEdits: Boolean get() = editState.hasPendingEdits

    fun requestShuffleToggle(): Boolean {
        val window = window
        if (window == null || ListenTogether.state.value.inParty) return false
        val target = !(editState.desiredShuffle ?: window.isShuffleEnabled)
        editState = BoundedQueueEdits.requestShuffle(
            editState,
            desiredShuffle = target,
        )
        if (!isTransitioning()) applyDeferredEdit()
        return true
    }

    fun applyDeferredEdit() {
        val exoPlayer = player() ?: return
        val window = window ?: return
        if (ListenTogether.state.value.inParty || isTransitioning()) return
        val settlement = BoundedQueueEdits.settle(
            state = editState,
            settledGeneration = editState.contextGeneration,
        )
        val edit = settlement.edit ?: return
        editState = settlement.state
        anchor(exoPlayer)?.let { anchor ->
            if (window.currentCanonicalIndex != anchor) window.jumpTo(anchor)
        }
        if (edit.desiredShuffle != null) {
            window.setShuffle(edit.desiredShuffle, shuffleSeed = Random.nextLong())
            QueueShuffle.setEnabled(edit.desiredShuffle)
            AppSettings.setShuffleEnabled(edit.desiredShuffle)
            replaceUpcoming(exoPlayer, window)
        }
        if (edit.refresh) replaceUpcoming(exoPlayer, window)
        if (edit.topUp) {
            applyTopUp()
        }
        refreshLayouts()
    }

    private fun replaceUpcoming(exoPlayer: ExoPlayer, window: QueueWindow) {
        val snapshot = registry.snapshot() ?: return
        if (snapshot.contextToken != token) return
        window.extendTo(snapshot.songs.size)
        val from = exoPlayer.currentMediaItemIndex + 1
        if (from < 0) return
        val existing = (from until exoPlayer.mediaItemCount).map(exoPlayer::getMediaItemAt)
        val users = existing.filter { it.queueTier == QueueTier.USER_QUEUE }
        val autoplay = existing.filter { it.queueTier == QueueTier.AUTOPLAY }
        val existingContext = existing.filter { it.queueTier == QueueTier.CONTEXT }
            .associateBy { QueueWindowEntry(it.canonicalIndex ?: -1, it.contextCycleId ?: window.cycleId) }
        val context = BoundedQueuePlanner.physicalUpcoming(
            window = window,
            repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL &&
                BoundedQueuePlanner.canRepeat(snapshot.continuationState),
        ).map { entry ->
            existingContext[entry] ?: mediaItem(
                snapshot.songs[entry.canonicalIndex], entry.canonicalIndex, entry.cycleId,
            )
        }
        QueueShuffle.replaceChanged(exoPlayer, from, exoPlayer.mediaItemCount, users + context + autoplay)
    }

    fun start(args: Bundle) {
        val exoPlayer = player() ?: return
        val requestToken = args.getString(EXTRA_BOUNDED_CONTEXT_SONGS) ?: return
        val request = BoundedContextRequests.take(requestToken) ?: return
        if (isCasting()) {
            BoundedContextRequests.discard(requestToken)
            return
        }
        BoundedContextPersistence.clearImmediately()
        editState = BoundedQueueEdits.invalidateContext(editState)

        val source = request.source
        val requestedSongs = request.songs
            .filterNot { it.isUnresolvedSpotify }
            .map { it.copy(
                queueTier = QueueTier.CONTEXT,
                playbackSource = source.title,
                playbackSourceType = source.type,
                playbackSourceId = source.id,
            ) }
        if (requestedSongs.isEmpty()) return

        val requestedIndex = request.startIndex
            .coerceIn(requestedSongs.indices)
        val selectedKey = requestedSongs[requestedIndex].contextEntryKey()
        val contextToken = "${source.type.name}:${source.id.orEmpty()}:${UUID.randomUUID()}"
        val registered = registry.register(
            contextToken = contextToken,
            sourceKey = "${source.type.name}:${source.id.orEmpty()}",
            browseId = source.id,
            songs = requestedSongs,
            continuationToken = request.continuationToken,
        )
        val selectedIndex = registered.songs.indexOfFirst { it.contextEntryKey() == selectedKey }
            .takeIf { it >= 0 } ?: 0
        val shuffle = request.shuffle
        val window = QueueWindow(registered.songs.size, shuffleSeed = Random.nextLong())
            .startAt(selectedIndex, shuffle)

        continuationJob?.cancel()
        this.window = window
        token = contextToken
        this.source = source
        this.request = request
        BoundedContextRequests.publish(request, registered)

        val currentIndex = exoPlayer.currentMediaItemIndex
        val upcoming = if (currentIndex in 0 until exoPlayer.mediaItemCount) {
            (currentIndex + 1 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) }
        } else {
            emptyList()
        }
        val userQueue = upcoming.filter { it.queueTier == QueueTier.USER_QUEUE }.map(MediaItem::toSong)
        onContextStarted()
        val repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL
        val plan = BoundedQueuePlanner.buildInitialPlan(
            contextSnapshot = registered,
            selectedIndex = selectedIndex,
            queueWindow = window,
            preservedUserQueue = userQueue,
            repeatAll = repeatAll,
        )

        canonicalJob?.cancel()
        QueueShuffle.clearCanonicalContext()
        canonicalJob = scope.launch(Dispatchers.Default) {
            val canonicalItems = canonicalMediaItems(registered.songs, contextToken)
            if (isActive) QueueShuffle.setCanonicalContext(canonicalItems)
        }

        val items = mediaItems(plan, window)
        val start = plan.history.size
        exoPlayer.setMediaItems(items, start.coerceIn(items.indices), 0L)
        saveSnapshot(exoPlayer)
        exoPlayer.prepare()
        exoPlayer.play()
        startContinuationLoading(contextToken)
    }

    fun clear() {
        editState = BoundedQueueEdits.invalidateContext(editState)
        request?.handoffToken?.let(BoundedContextRequests::discard)
        canonicalJob?.cancel()
        canonicalJob = null
        continuationJob?.cancel()
        continuationJob = null
        topUpJob?.cancel()
        topUpJob = null
        registry.register(
            contextToken = "cleared:${UUID.randomUUID()}",
            sourceKey = "cleared",
            browseId = null,
            songs = emptyList(),
            continuationToken = null,
        )
        BoundedContextPersistence.clearImmediately()
        window = null
        token = null
        source = null
        request = null
        QueueShuffle.clearCanonicalContext()
    }

    private fun startContinuationLoading(contextToken: String) {
        continuationJob?.cancel()
        continuationJob = scope.launch {
            var failures = 0
            while (isActive && token == contextToken) {
                val claim = registry.claimContinuation(contextToken) ?: return@launch
                val page = withContext(Dispatchers.IO) {
                    YtMusicRepository.moreSongs(claim.continuationToken).getOrNull()
                }
                if (page == null) {
                    registry.fail(claim)
                    if (++failures > CONTINUATION_RETRIES_MS.size) {
                        request?.handoffToken?.let(BoundedContextRequests::release)
                        return@launch
                    }
                    delay(CONTINUATION_RETRIES_MS[failures - 1])
                    continue
                }
                failures = 0
                val source = source
                val songs = page.songs.map { song ->
                    if (source == null) song else song.copy(
                        queueTier = QueueTier.CONTEXT,
                        playbackSource = source.title,
                        playbackSourceType = source.type,
                        playbackSourceId = source.id,
                    )
                }
                val result = registry.complete(
                    claim,
                    ContinuationPage(songs = songs, continuationToken = page.continuation),
                )
                if (result.status != ContinuationResultStatus.COMPLETED) return@launch
            }
        }
    }

    fun scheduleTopUp() {
        if (window == null || token == null) return
        editState = BoundedQueueEdits.requestTopUp(editState)
        if (topUpJob?.isActive == true) return
        topUpJob = scope.launch {
            if (ListenTogether.state.value.inParty) return@launch
            if (isTransitioning()) {
                return@launch
            }
            applyTopUp()
            editState = editState.copy(topUpPending = false)
        }
    }

    private fun applyTopUp() {
        val exoPlayer = player() ?: return
        val window = window ?: return
        val snapshot = registry.snapshot() ?: return
        if (snapshot.contextToken != token) return
        window.extendTo(snapshot.songs.size)
        val currentIndex = anchor(exoPlayer) ?: window.currentCanonicalIndex ?: return
        if (window.currentCanonicalIndex != currentIndex) {
            window.jumpTo(currentIndex)
        }
        val now = exoPlayer.currentMediaItemIndex
        val afterCurrent = if (now in 0 until exoPlayer.mediaItemCount) {
            (now + 1 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) }
        } else emptyList()
        val existingEntries = afterCurrent
            .filter { it.queueTier == QueueTier.CONTEXT }
            .mapNotNull { item ->
                val canonicalIndex = QueueShuffle.getCanonicalIndex(item) ?: return@mapNotNull null
                QueueWindowEntry(canonicalIndex, item.contextCycleId ?: window.cycleId)
            }
        val repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL &&
            BoundedQueuePlanner.canRepeat(snapshot.continuationState)
        val additions = BoundedQueuePlanner.topUpEntries(
            window = window,
            materialized = existingEntries,
            repeatAll = repeatAll,
        )
        if (additions.isEmpty()) return
        val autoplayIndex = (now + 1 until exoPlayer.mediaItemCount)
            .firstOrNull { exoPlayer.getMediaItemAt(it).queueTier == QueueTier.AUTOPLAY }
            ?: exoPlayer.mediaItemCount
        val items = additions.map { entry ->
            mediaItem(snapshot.songs[entry.canonicalIndex], entry.canonicalIndex, entry.cycleId)
        }
        exoPlayer.addMediaItems(autoplayIndex, items)
    }

    private fun anchor(exoPlayer: ExoPlayer): Int? {
        val current = exoPlayer.currentMediaItem
        if (current?.queueTier == QueueTier.CONTEXT) {
            QueueShuffle.getCanonicalIndex(current)?.let { return it }
        }
        val currentIndex = exoPlayer.currentMediaItemIndex
        for (index in currentIndex - 1 downTo 0) {
            val item = exoPlayer.getMediaItemAt(index)
            if (item.queueTier == QueueTier.CONTEXT) {
                QueueShuffle.getCanonicalIndex(item)?.let { return it }
            }
        }
        return null
    }

    fun syncCursor(mediaItem: MediaItem?) {
        val window = window ?: return
        if (ListenTogether.state.value.inParty) return
        if (mediaItem?.queueTier != QueueTier.CONTEXT) return
        val canonicalIndex = QueueShuffle.getCanonicalIndex(mediaItem) ?: return
        val cycle = mediaItem.contextCycleId
        if (cycle != null) {
            window.commit(QueueWindowEntry(canonicalIndex, cycle))
        } else if (window.currentCanonicalIndex != canonicalIndex) {
            window.jumpTo(canonicalIndex)
        }
    }

    fun rebuildAfterParty() {
        val exoPlayer = player() ?: return
        val window = window ?: return
        val snapshot = registry.snapshot() ?: return
        if (snapshot.contextToken != token) return
        val anchor = anchor(exoPlayer)
        if (anchor == null || anchor !in snapshot.songs.indices) {
            clear()
            return
        }
        if (window.currentCanonicalIndex != anchor) window.jumpTo(anchor)
        QueueShuffle.setCanonicalContext(canonicalMediaItems(snapshot.songs, snapshot.contextToken))
        editState = BoundedQueueEdits.requestRefresh(editState)
        if (!isTransitioning()) applyDeferredEdit()
    }

    fun autoplayEligible(exoPlayer: ExoPlayer): Boolean {
        val window = window ?: return true
        val snapshot = registry.snapshot() ?: return false
        if (snapshot.contextToken != token) return false
        anchor(exoPlayer)?.let { anchor ->
            if (window.currentCanonicalIndex != anchor) window.jumpTo(anchor)
        }
        return BoundedQueuePlanner.autoplayEligible(
            window = window,
            continuationState = snapshot.continuationState,
            repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL,
        )
    }

    fun saveSnapshot(player: ExoPlayer, immediately: Boolean = false) {
        if (ListenTogether.state.value.inParty) return
        val window = window ?: return
        val context = registry.snapshot() ?: return
        val source = source ?: return
        if (context.contextToken != token || context.songs.isEmpty()) return
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
        val range = LastPlayed.window(player.mediaItemCount, currentIndex)
        if (range.isEmpty()) return
        val entries = range.map { index ->
            val item = player.getMediaItemAt(index)
            BoundedContextPersistence.Entry(
                song = item.toSong(),
                canonicalIndex = if (item.queueTier == QueueTier.CONTEXT) item.canonicalIndex else null,
                cycleId = if (item.queueTier == QueueTier.CONTEXT) item.contextCycleId else null,
            )
        }
        val state = BoundedContextPersistence.State(
            context = context,
            source = source,
            window = window.snapshot(),
            entries = entries,
            currentIndex = currentIndex - range.first,
            positionMs = player.currentPosition,
        )
        if (immediately) BoundedContextPersistence.saveImmediately(state) else BoundedContextPersistence.save(state)
    }

    fun savePlaybackState(player: ExoPlayer) {
        if (window != null && !ListenTogether.state.value.inParty) {
            BoundedContextPersistence.savePlaybackState(
                currentEntryId = player.currentMediaItem?.queueEntryId,
                positionMs = player.currentPosition,
            )
        }
    }

    fun restore(
        exoPlayer: ExoPlayer,
        persisted: BoundedContextPersistence.State,
    ): Boolean {
        val projected = BoundedContextPersistence.restoreProjection(
            persisted,
            repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL,
        ) ?: return false
        val restoredWindow = runCatching { QueueWindow.restore(projected.window) }.getOrNull()
            ?: return false
        if (restoredWindow.canonicalSize != projected.context.songs.size) return false
        val restoredCurrent = restoredWindow.currentCanonicalIndex ?: return false
        if (projected.currentIndex !in projected.entries.indices) return false

        val restoredContext = runCatching {
            registry.restore(projected.context)
        }.getOrNull() ?: return false
        if (restoredContext.songs.isEmpty()) return false

        val contextToken = restoredContext.contextToken
        QueueShuffle.setCanonicalContext(canonicalMediaItems(restoredContext.songs, contextToken))

        val items = projected.entries.map { entry ->
            val canonicalIndex = entry.canonicalIndex
            if (canonicalIndex == null) {
                entry.song.toMediaItem()
            } else {
                mediaItem(
                    entry.song.copy(queueTier = QueueTier.CONTEXT),
                    canonicalIndex,
                    entry.cycleId ?: restoredWindow.cycleId,
                )
            }
        }
        if (items.size != projected.entries.size) return false

        window = restoredWindow
        token = contextToken
        source = persisted.source
        request = BoundedContextRequests.restore(
            BoundedContextRequests.Request(
                songs = restoredContext.songs,
                startIndex = restoredCurrent,
                source = persisted.source,
                continuationToken = restoredContext.continuationToken,
                shuffle = restoredWindow.isShuffleEnabled,
            ),
        )
        editState = BoundedQueueEdits.invalidateContext(editState)
        onRestoring()
        exoPlayer.setMediaItems(items, projected.currentIndex, projected.positionMs)
        startContinuationLoading(contextToken)
        return true
    }

    private fun canonicalMediaItems(songs: List<Song>, contextToken: String): List<MediaItem> =
        songs.mapIndexed { index, song ->
            val canonical = song.copy(queueEntryId = "context:$contextToken:$index")
            QueueShuffle.withQueueMetadata(
                canonical.toMediaItem(),
                newEntryId = canonical.queueEntryId,
                newCanonicalIndex = index,
                newTier = QueueTier.CONTEXT,
            )
        }

    private fun mediaItems(plan: BoundedContextPlan, window: QueueWindow): List<MediaItem> {
        val canonical = plan.canonicalContext
        val history = plan.history.map { song ->
            mediaItem(song, canonical.indexOfFirst { it.contextEntryKey() == song.contextEntryKey() }, window.cycleId)
        }
        val current = mediaItem(plan.current, plan.currentCanonicalIndex, window.cycleId)
        val user = plan.userQueue.map { it.copy(queueEntryId = it.queueEntryId ?: UUID.randomUUID().toString()).toMediaItem() }
        val context = plan.contextUpcoming.map { entry ->
            mediaItem(canonical[entry.canonicalIndex], entry.canonicalIndex, entry.cycleId)
        }
        val autoplay = plan.autoplay.map { it.copy(queueEntryId = it.queueEntryId ?: UUID.randomUUID().toString()).toMediaItem() }
        return history + listOf(current) + user + context + autoplay
    }

    private fun mediaItem(song: Song, canonicalIndex: Int, cycleId: Long): MediaItem {
        val entry = song.copy(
            queueTier = QueueTier.CONTEXT,
            queueEntryId = "window:${UUID.randomUUID()}",
        )
        return QueueShuffle.withQueueMetadata(
            entry.toMediaItem(),
            newEntryId = entry.queueEntryId,
            newCanonicalIndex = canonicalIndex,
            newTier = QueueTier.CONTEXT,
            newContextCycleId = cycleId,
        )
    }
}
