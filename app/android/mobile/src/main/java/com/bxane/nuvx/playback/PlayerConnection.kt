package com.bxane.nuvx.playback

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.bxane.nuvx.data.model.ArtistRef
import com.bxane.nuvx.data.model.NOTIFICATION_ART_PX
import com.bxane.nuvx.data.model.PlaybackSourceType
import com.bxane.nuvx.data.model.QueueTier
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.SourceResolver
import com.bxane.nuvx.data.sources.TrackMatcher
import com.bxane.nuvx.download.Downloads
import com.bxane.nuvx.ui.rememberIsForeground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import com.bxane.nuvx.data.model.isLiveRadio

data class PlayerState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val position: PlaybackPosition = PlaybackPosition(),
    val durationMs: Long = 0L,
    val error: String? = null,
    val isLoading: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val isQualityUpgraded: Boolean = false,
)

@Composable
fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}

fun MediaController.toggleAutoplay() {
    sendCustomCommand(
        SessionCommand(ACTION_TOGGLE_AUTOPLAY, Bundle.EMPTY),
        Bundle.EMPTY,
    )
}

fun MediaController.toggleShuffle() {
    sendCustomCommand(
        SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY),
        Bundle.EMPTY,
    )
}

suspend fun MediaController.playBoundedContext(
    songs: List<Song>,
    startIndex: Int,
    source: QueueSource,
    continuationToken: String?,
    shuffle: Boolean = QueueShuffle.enabled.value,
) {
    val token = BoundedContextRequests.put(
        BoundedContextRequests.Request(
            songs = songs,
            startIndex = startIndex,
            source = source,
            continuationToken = continuationToken,
            shuffle = shuffle,
        ),
    )
    try {
        sendCustomCommand(
            SessionCommand(ACTION_START_BOUNDED_CONTEXT, Bundle.EMPTY),
            bundleOf(EXTRA_BOUNDED_CONTEXT_SONGS to token),
        ).await()
    } catch (error: Throwable) {
        BoundedContextRequests.discard(token)
        throw error
    }
}

suspend fun MediaController.beginRadioQueue() {
    sendCustomCommand(
        SessionCommand(ACTION_BEGIN_RADIO_QUEUE, Bundle.EMPTY),
        Bundle.EMPTY,
    ).await()
}

fun MediaController.upgradeQuality() {
    sendCustomCommand(
        SessionCommand(ACTION_UPGRADE_QUALITY, Bundle.EMPTY),
        Bundle.EMPTY,
    )
}

fun MediaController.revertToOriginal() {
    sendCustomCommand(
        SessionCommand(ACTION_REVERT_TO_ORIGINAL, Bundle.EMPTY),
        Bundle.EMPTY,
    )
}

fun MediaController.swapToVersion(targetSong: Song) {
    sendCustomCommand(
        SessionCommand(ACTION_SWAP_VERSION, Bundle.EMPTY),
        bundleOf(EXTRA_SWAP_MEDIA_ITEM to targetSong.toSongBundle()),
    )
}

private fun Bundle.artistsFromBundle(): List<ArtistRef> {
    val names = getStringArrayList(ARTISTS_NAMES).orEmpty()
    val ids = getStringArrayList(ARTISTS_IDS).orEmpty()
    if (names.isEmpty() || ids.isEmpty()) return emptyList()
    return names.indices.map { index ->
        ArtistRef(name = names[index], browseId = ids.getOrNull(index)?.ifBlank { null })
    }
}

private const val ARTISTS_NAMES = "artistNames"
private const val ARTISTS_IDS = "artistIds"

fun Song.toSongBundle(): Bundle = bundleOf(
    "videoId" to videoId,
    "title" to title,
    "artist" to artist,
    "thumbnailUrl" to thumbnailUrl,
    "durationText" to durationText,
    "artistId" to artistId,
    "albumId" to albumId,
    "albumName" to albumName,
    "isVideo" to isVideo,
    "isVideoOrigin" to isVideoOrigin,
    "setVideoId" to setVideoId,
    "fromAutoplay" to fromAutoplay,
    "radioName" to radioName,
    "localUri" to localUri,
    "downloadFormat" to downloadFormat,
    "localPath" to localPath,
    "sourceQuality" to sourceQuality,
    "playbackSource" to playbackSource,
    "playbackSourceType" to playbackSourceType?.name,
    "playbackSourceId" to playbackSourceId,
    "isExplicit" to (isExplicit ?: false),
    "queueTier" to queueTier.name,
    "queueEntryId" to queueEntryId,
).apply {
    putStringArrayList(ARTISTS_NAMES, ArrayList(artists.map { it.name }))
    putStringArrayList(ARTISTS_IDS, ArrayList(artists.map { it.browseId.orEmpty() }))
}

fun songFromBundle(b: Bundle): Song {
    val queueTier = b.getString("queueTier")?.let {
        runCatching { QueueTier.valueOf(it) }.getOrNull()
    } ?: if (b.getBoolean("fromAutoplay")) QueueTier.AUTOPLAY else QueueTier.CONTEXT
    return Song(
        videoId = b.getString("videoId").orEmpty(),
        title = b.getString("title").orEmpty(),
        artist = b.getString("artist").orEmpty(),
        thumbnailUrl = b.getString("thumbnailUrl"),
        durationText = b.getString("durationText"),
        artistId = b.getString("artistId"),
        artists = b.artistsFromBundle(),
        albumId = b.getString("albumId"),
        albumName = b.getString("albumName"),
        isVideo = b.getBoolean("isVideo"),
        isVideoOrigin = b.getBoolean("isVideoOrigin"),
        setVideoId = b.getString("setVideoId"),
        radioName = b.getString("radioName"),
        localUri = b.getString("localUri"),
        downloadFormat = b.getString("downloadFormat"),
        localPath = b.getString("localPath"),
        sourceQuality = b.getString("sourceQuality"),
        playbackSource = b.getString("playbackSource"),
        playbackSourceType = b.getString("playbackSourceType")?.let { runCatching { com.bxane.nuvx.data.model.PlaybackSourceType.valueOf(it) }.getOrNull() },
        playbackSourceId = b.getString("playbackSourceId"),
        isExplicit = if (b.containsKey("isExplicit")) b.getBoolean("isExplicit") else null,
        queueTier = queueTier,
        queueEntryId = b.getString("queueEntryId"),
    )
}

suspend fun MediaController.commitRadioQueue() {
    sendCustomCommand(
        SessionCommand(ACTION_COMMIT_RADIO_QUEUE, Bundle.EMPTY),
        Bundle.EMPTY,
    ).await()
}


fun MediaController.setQueueDragActive(active: Boolean) {
    sendCustomCommand(
        SessionCommand(ACTION_QUEUE_DRAG, Bundle.EMPTY),
        bundleOf(EXTRA_QUEUE_DRAG_ACTIVE to active),
    )
}

@Composable
fun rememberPlayerState(controller: MediaController?): PlayerState {
    val position = remember { PlaybackPosition() }
    var state by remember { mutableStateOf(PlayerState(position = position)) }

    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}

        var queueSnapshot = emptyList<Song>()

        var queueChanged = false

        fun sync(
            error: String? = null,
            rebuildQueue: Boolean = false,
            refreshCurrentQueueItem: Boolean = false,
        ) {
            val item = player.currentMediaItem
            if (rebuildQueue) {
                queueSnapshot = (0 until player.mediaItemCount)
                    .map { player.getMediaItemAt(it).toSong() }
            } else if (refreshCurrentQueueItem && item != null) {
                val index = player.currentMediaItemIndex
                if (index in queueSnapshot.indices) {
                    queueSnapshot = queueSnapshot.toMutableList().also { it[index] = item.toSong() }
                }
            }
            position.report(player.currentPosition.coerceAtLeast(0L))
            state = state.copy(
                song = item?.toSong(),
                isPlaying = player.isPlaying,
                durationMs = player.duration.coerceAtLeast(0L),
                error = error,
                isLoading = player.playbackState == Player.STATE_BUFFERING,
                repeatMode = player.repeatMode,
                queue = queueSnapshot,
                queueIndex = player.currentMediaItemIndex,
                hasPrevious = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
                isQualityUpgraded = item?.mediaMetadata?.extras
                    ?.getBoolean(EXTRA_QUALITY_UPGRADED) == true,
            )
        }

        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED ||
                    timeline.windowCount != queueSnapshot.size
                ) {
                    queueChanged = true
                } else if (timeline.windowCount > 0 && queueSnapshot.isNotEmpty()) {
                    val window = Timeline.Window()
                    for (i in 0 until timeline.windowCount) {
                        timeline.getWindow(i, window)
                        val item = window.mediaItem
                        val entryId = item.mediaMetadata.extras?.getString(EXTRA_QUEUE_ENTRY_ID)
                            ?: mediaItemEntryIds[item]
                        if (item.mediaId != queueSnapshot[i].videoId ||
                            (entryId != null && entryId != queueSnapshot[i].queueEntryId)
                        ) {
                            queueChanged = true
                            break
                        }
                    }
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                val reanchor = reason == Player.DISCONTINUITY_REASON_INTERNAL &&
                    newPosition.positionMs > oldPosition.positionMs
                if (!reanchor) position.seeks++
            }
            override fun onEvents(p: Player, events: Player.Events) = sync(
                error = state.error,
                rebuildQueue = queueChanged.also { queueChanged = false },
                refreshCurrentQueueItem = events.contains(Player.EVENT_MEDIA_METADATA_CHANGED),
            )
            override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
                sync(error?.let { "Playback failed: ${it.errorCodeName}" })
            }
        }
        player.addListener(listener)
        sync(rebuildQueue = true)
        onDispose { player.removeListener(listener) }
    }

    val foreground = rememberIsForeground()
    LaunchedEffect(controller, state.isPlaying, foreground) {
        while (controller != null && state.isPlaying && foreground) {
            position.report(controller.currentPosition.coerceAtLeast(0L))
            val duration = controller.duration.coerceAtLeast(0L)
            if (duration != state.durationMs) state = state.copy(durationMs = duration)
            delay(500)
        }
    }
    return state
}

fun MediaItem.toSong() = Song(
    videoId = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    thumbnailUrl = mediaMetadata.artworkUri?.toString(),
    durationText = mediaMetadata.extras?.getString(EXTRA_DURATION),
    artistId = mediaMetadata.extras?.getString(EXTRA_ARTIST_ID),
    artists = mediaMetadata.extras?.artistsFromBundle() ?: emptyList(),
    albumId = mediaMetadata.extras?.getString(EXTRA_ALBUM_ID),
    albumName = mediaMetadata.albumTitle?.toString(),
    isExplicit = mediaMetadata.extras?.takeIf { it.containsKey(EXTRA_EXPLICIT) }
        ?.getBoolean(EXTRA_EXPLICIT),
    isVideo = mediaMetadata.extras?.getBoolean(EXTRA_IS_VIDEO) == true,
    isVideoOrigin = mediaMetadata.extras?.getBoolean(EXTRA_VIDEO_ORIGIN) == true ||
        mediaMetadata.extras?.getBoolean(EXTRA_IS_VIDEO) == true,
    setVideoId = mediaMetadata.extras?.getString(EXTRA_SET_VIDEO_ID),
    sourceQuality = mediaMetadata.extras?.getString(EXTRA_SOURCE_QUALITY),
    sourceHiRes = mediaMetadata.extras?.getBoolean(EXTRA_SOURCE_HI_RES) == true,
    queueTier = this.queueTier,
    queueEntryId = this.queueEntryId,
    radioName = mediaMetadata.extras?.getString(EXTRA_RADIO_NAME),
    playbackSource = mediaMetadata.extras?.getString(EXTRA_PLAYBACK_SOURCE),
    playbackSourceType = mediaMetadata.extras?.getString(EXTRA_PLAYBACK_SOURCE_TYPE)
        ?.let { runCatching { PlaybackSourceType.valueOf(it) }.getOrNull() },
    playbackSourceId = mediaMetadata.extras?.getString(EXTRA_PLAYBACK_SOURCE_ID),
    localUri = mediaMetadata.extras?.getString(EXTRA_LOCAL_URI),
    localPath = mediaMetadata.extras?.getString(EXTRA_LOCAL_PATH),
)

internal val mediaItemTiers = java.util.Collections.synchronizedMap(java.util.WeakHashMap<MediaItem, QueueTier>())
internal val mediaItemEntryIds = java.util.Collections.synchronizedMap(java.util.WeakHashMap<MediaItem, String>())
internal val mediaItemCanonicalIndices = java.util.Collections.synchronizedMap(java.util.WeakHashMap<MediaItem, Int>())

val MediaItem.fromAutoplay: Boolean
    get() = queueTier == QueueTier.AUTOPLAY

val MediaItem.queueTier: QueueTier
    get() {
        val tierFromExtras = when (mediaMetadata.extras?.getString(EXTRA_QUEUE_TIER)) {
            "USER_QUEUE" -> QueueTier.USER_QUEUE
            "CONTEXT" -> QueueTier.CONTEXT
            "AUTOPLAY" -> QueueTier.AUTOPLAY
            else -> if (mediaMetadata.extras?.getBoolean(EXTRA_FROM_AUTOPLAY) == true) {
                QueueTier.AUTOPLAY
            } else {
                null
            }
        }
        return tierFromExtras ?: mediaItemTiers[this] ?: QueueTier.CONTEXT
    }

val MediaItem.queueEntryId: String?
    get() = mediaMetadata.extras?.getString(EXTRA_QUEUE_ENTRY_ID) ?: mediaItemEntryIds[this]

const val EXTRA_QUEUE_TIER = "nuvx.queueTier"
const val EXTRA_QUEUE_ENTRY_ID = "nuvx.queueEntryId"
const val EXTRA_CANONICAL_INDEX = "nuvx.canonicalIndex"

val MediaItem.canonicalIndex: Int?
    get() {
        val extras = mediaMetadata.extras
        if (extras != null && extras.containsKey(EXTRA_CANONICAL_INDEX)) {
            val idx = extras.getInt(EXTRA_CANONICAL_INDEX, -1)
            if (idx >= 0) return idx
        }
        return mediaItemCanonicalIndices[this]
    }

private const val EXTRA_FROM_AUTOPLAY = "nuvx.fromAutoplay"

private const val EXTRA_RADIO_NAME = "nuvx.radioName"

private const val EXTRA_PLAYBACK_SOURCE = "nuvx.playbackSource"
private const val EXTRA_PLAYBACK_SOURCE_TYPE = "nuvx.playbackSourceType"
private const val EXTRA_PLAYBACK_SOURCE_ID = "nuvx.playbackSourceId"

private const val EXTRA_ARTIST_ID = "nuvx.artistId"
private const val EXTRA_ALBUM_ID = "nuvx.albumId"

private const val EXTRA_SET_VIDEO_ID = "nuvx.setVideoId"

internal const val EXTRA_LOCAL_URI = "nuvx.localUri"

internal const val EXTRA_LOCAL_PATH = "nuvx.localPath"

private const val EXTRA_DURATION = "nuvx.durationText"
private const val EXTRA_EXPLICIT = "nuvx.explicit"

private const val EXTRA_SOURCE_QUALITY = "nuvx.sourceQuality"
private const val EXTRA_SOURCE_HI_RES = "nuvx.sourceHiRes"
private const val EXTRA_IS_VIDEO = "nuvx.isVideo"
private const val EXTRA_VIDEO_ORIGIN = "nuvx.isVideoOrigin"

const val EXTRA_QUALITY_UPGRADED = "nuvx.qualityUpgraded"

val MediaItem.isVideoOrigin: Boolean
    get() = mediaMetadata.extras?.getBoolean(EXTRA_VIDEO_ORIGIN) == true ||
        mediaMetadata.extras?.getBoolean(EXTRA_IS_VIDEO) == true

fun MediaController.autoplaySectionStart(): Int = autoplaySectionStart(
    fromAutoplay = (0 until mediaItemCount).map { getMediaItemAt(it).fromAutoplay },
    currentIndex = currentMediaItemIndex,
)

private val DIRECT_FILE_URI_EXTENSIONS = setOf(
    "m4a", "m4b", "m4p", "mp4", "aac", "3ga", "3gp", "3gpp",
    "alac", "amr", "awb", "wma", "aif", "aiff", "ac3", "dts",
    "dsf", "dff",
)

private fun resolvePlaybackUri(uriString: String, localPath: String?): String {
    if (localPath.isNullOrBlank() || !uriString.startsWith("content://")) return uriString
    val ext = localPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext !in DIRECT_FILE_URI_EXTENSIONS) return uriString
    val file = File(localPath)
    return if (file.exists() && file.canRead()) Uri.fromFile(file).toString() else uriString
}

private fun Song.matchQuery(): String = buildString {
    append("&n=").append(Uri.encode(title))
    append("&a=").append(Uri.encode(artist))
    TrackMatcher.secondsOf(durationText)?.let { append("&d=").append(it) }
    albumName?.takeIf { it.isNotBlank() }?.let { append("&l=").append(Uri.encode(it)) }
    isExplicit?.let { append("&e=").append(if (it) "1" else "0") }
    if (isVideo) append("&m=1")
}

private fun Song.pickedMixQuery(configId: String): String {
    if (SourceRegistry.config(configId)?.kind != SourceKind.ADDON) return ""
    return "&${SourceResolver.MIX_PARAM}=${SourceResolver.mixOf(sourceQuality)}"
}

fun Song.toMediaItem(): MediaItem {
    val sourceTrack = SourceRegistry.parseTrackKey(videoId)
    val offlineUri = localUri?.takeUnless(Downloads::isMissingLocalFile)
        ?: Downloads.verifiedSavedUri(videoId)
    val uriString = offlineUri ?: when {
        videoId.startsWith("content://") || videoId.startsWith("file://") -> videoId
        sourceTrack != null -> SourceRegistry.trackUri(sourceTrack.first, sourceTrack.second)
            .let { "$it${matchQuery()}${pickedMixQuery(sourceTrack.first)}" }
        OriginalVersion.isPinned(videoId) -> directYouTubeUri()
        else -> "nuvx://watch?v=$videoId${matchQuery()}"
    }
    return MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(resolvePlaybackUri(uriString, localPath))
        .apply { if (isLiveRadio(videoId)) setCustomCacheKey(videoId) }
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(albumName)
            .setArtworkUri(artworkAt(NOTIFICATION_ART_PX)?.toUri())
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply {
                if (queueTier != QueueTier.CONTEXT || queueEntryId != null || fromAutoplay ||
                    offlineUri != null || durationText != null ||
                    artistId != null || albumId != null || setVideoId != null ||
                    isExplicit != null || isVideo || isVideoOrigin || radioName != null ||
                    sourceQuality != null ||
                    playbackSource != null || playbackSourceType != null || playbackSourceId != null
                ) {
                    setExtras(
                        bundleOf(
                            EXTRA_QUEUE_TIER to queueTier.name,
                            EXTRA_QUEUE_ENTRY_ID to queueEntryId,
                            EXTRA_FROM_AUTOPLAY to fromAutoplay,
                            EXTRA_RADIO_NAME to radioName,
                            EXTRA_PLAYBACK_SOURCE to playbackSource,
                            EXTRA_PLAYBACK_SOURCE_TYPE to playbackSourceType?.name,
                            EXTRA_PLAYBACK_SOURCE_ID to playbackSourceId,
                            EXTRA_LOCAL_URI to offlineUri,
                            EXTRA_LOCAL_PATH to localPath,
                            EXTRA_DURATION to durationText,
                            EXTRA_ARTIST_ID to artistId,
                            EXTRA_ALBUM_ID to albumId,
                            EXTRA_SET_VIDEO_ID to setVideoId,
                            EXTRA_EXPLICIT to isExplicit,
                            EXTRA_IS_VIDEO to isVideo,
                            EXTRA_VIDEO_ORIGIN to isVideoOrigin,
                            EXTRA_SOURCE_QUALITY to sourceQuality,
                            EXTRA_SOURCE_HI_RES to sourceHiRes,
                        ),
                    )
                }
            }
            .build(),
    )
    .build()
    .also { item ->
        queueEntryId?.let { mediaItemEntryIds[item] = it }
        mediaItemTiers[item] = queueTier
    }
}

fun Song.toDirectYouTubeMediaItem(): MediaItem =
    toMediaItem().buildUpon()
        .setUri(directYouTubeUri())
        .build()

private fun Song.directYouTubeUri(): String =
    "nuvx://watch?v=$videoId${matchQuery()}&$DIRECT_YOUTUBE_PARAMETER=1&q=original"

fun Song.hasYouTubeOriginal(): Boolean =
    videoId.isNotBlank() &&
        !videoId.startsWith("content://") &&
        !videoId.startsWith("file://") &&
        !isVideo &&
        SourceRegistry.parseTrackKey(videoId) == null

const val DIRECT_YOUTUBE_PARAMETER = "direct_youtube"

fun mediaIdIn(uri: Uri): String? = if (uri.authority == "source") {
    val configId = uri.getQueryParameter("s")
    val trackId = uri.getQueryParameter("t")
    if (configId != null && trackId != null) SourceRegistry.trackKey(configId, trackId) else null
} else {
    uri.getQueryParameter("v")
}

suspend fun MediaController.playSongs(songs: List<Song>, startIndex: Int) {
    if (songs.isEmpty()) return
    sendCustomCommand(
        SessionCommand(ACTION_CLEAR_BOUNDED_CONTEXT, Bundle.EMPTY),
        Bundle.EMPTY,
    ).await()
    val shuffled = QueueShuffle.enabled.value
    val items = withContext(Dispatchers.Default) {
        val songsWithIds = songs.map { song ->
            if (song.queueEntryId == null) song.copy(queueEntryId = java.util.UUID.randomUUID().toString()) else song
        }
        val contextEntries = songsWithIds.filter { it.queueTier == QueueTier.CONTEXT }
        val canonicalIndexMap = contextEntries.mapIndexed { idx, s -> s.queueEntryId!! to idx }.toMap()

        val queue = if (shuffled) {
            QueueShuffle.startingOrder(songsWithIds, startIndex.coerceIn(songsWithIds.indices))
        } else {
            songsWithIds
        }
        val mediaItems = queue.map { song ->
            val item = song.toMediaItem()
            val cIndex = if (song.queueTier == QueueTier.CONTEXT) {
                canonicalIndexMap[song.queueEntryId]
            } else null
            if (cIndex != null) QueueShuffle.withQueueMetadata(item, newCanonicalIndex = cIndex) else item
        }

        val canonicalMediaItems = contextEntries.mapIndexed { idx, s ->
            QueueShuffle.withQueueMetadata(s.toMediaItem(), newCanonicalIndex = idx)
        }
        QueueShuffle.setCanonicalContext(canonicalMediaItems)

        mediaItems
    }
    setMediaItems(items, queueStartIndex(startIndex, items.size, shuffled), 0L)
    prepare()
    play()
}

internal fun queueStartIndex(requestedIndex: Int, itemCount: Int, shuffled: Boolean): Int =
    if (shuffled) 0 else requestedIndex.coerceIn(0, itemCount - 1)
