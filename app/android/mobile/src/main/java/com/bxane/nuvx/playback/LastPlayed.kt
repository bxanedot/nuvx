package com.bxane.nuvx.playback

import android.content.Context
import android.content.SharedPreferences
import com.bxane.nuvx.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object LastPlayed {

    class Snapshot(val songs: List<Song>, val index: Int, val positionMs: Long)

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    internal fun window(size: Int, index: Int): IntRange {
        if (size <= 0) return IntRange.EMPTY
        val safeIndex = index.coerceIn(0, size - 1)
        val start = (safeIndex - MAX_QUEUE_HISTORY).coerceAtLeast(0)
        return start until (safeIndex + 1 + KEEP_AHEAD).coerceAtMost(size)
    }

    fun saveQueue(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) {
            clear()
            return
        }
        val safeIndex = index.coerceIn(songs.indices)
        val bounds = window(songs.size, safeIndex)
        val stored = StoredQueue(
            tracks = songs.subList(bounds.first, bounds.last + 1).map { StoredTrack.from(it) },
        )
        val encoded = runCatching {
            json.encodeToString(StoredQueue.serializer(), stored)
        }.getOrNull() ?: return
        prefs.edit()
            .putString(KEY_QUEUE, encoded)
            .putInt(KEY_INDEX, safeIndex - bounds.first)
            .apply()
    }

    fun saveQueueImmediately(songs: List<Song>, index: Int, positionMs: Long) {
        if (songs.isEmpty()) {
            clearImmediately()
            return
        }
        val safeIndex = index.coerceIn(songs.indices)
        val bounds = window(songs.size, safeIndex)
        val stored = StoredQueue(
            tracks = songs.subList(bounds.first, bounds.last + 1).map { StoredTrack.from(it) },
        )
        val encoded = runCatching {
            json.encodeToString(StoredQueue.serializer(), stored)
        }.getOrNull() ?: return
        prefs.edit()
            .putString(KEY_QUEUE, encoded)
            .putInt(KEY_INDEX, safeIndex - bounds.first)
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .commit()
    }

    fun savePlaybackState(index: Int, positionMs: Long) {
        if (!prefs.contains(KEY_QUEUE)) return
        prefs.edit()
            .putInt(KEY_INDEX, index.coerceAtLeast(0))
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .apply()
    }

    fun load(): Snapshot? {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return null
        val stored = runCatching { json.decodeFromString<StoredQueue>(raw) }.getOrNull()
            ?: return null
        if (stored.tracks.isEmpty()) return null
        val songs = stored.tracks.map(StoredTrack::toSong)
        val index = if (prefs.contains(KEY_INDEX)) {
            prefs.getInt(KEY_INDEX, 0)
        } else {
            stored.legacyIndex ?: 0
        }
        val positionMs = if (prefs.contains(KEY_POSITION)) {
            prefs.getLong(KEY_POSITION, 0L)
        } else {
            stored.legacyPositionMs ?: 0L
        }
        return Snapshot(
            songs = songs,
            index = index.coerceIn(songs.indices),
            positionMs = positionMs.coerceAtLeast(0L),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun clearImmediately() {
        prefs.edit().clear().commit()
    }

    @Serializable
    private data class StoredQueue(
        val tracks: List<StoredTrack>,
        val index: Int? = null,
        val positionMs: Long? = null,
    ) {
        val legacyIndex: Int? get() = index
        val legacyPositionMs: Long? get() = positionMs
    }

    @Serializable
    private data class StoredTrack(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String? = null,
        val auto: Boolean = false,
        val tier: String? = null,
        val entryId: String? = null,
        val local: String? = null,
        val path: String? = null,
        val duration: String? = null,
        val album: String? = null,
        val explicit: Boolean? = null,
        val video: Boolean = false,
        val radio: String? = null,
        val source: String? = null,
        val sourceType: String? = null,
        val sourceId: String? = null,
    ) {
        fun toSong(): Song {
            val resolvedTier = when (tier) {
                "USER_QUEUE" -> com.bxane.nuvx.data.model.QueueTier.USER_QUEUE
                "CONTEXT" -> com.bxane.nuvx.data.model.QueueTier.CONTEXT
                "AUTOPLAY" -> com.bxane.nuvx.data.model.QueueTier.AUTOPLAY
                else -> if (auto) com.bxane.nuvx.data.model.QueueTier.AUTOPLAY else com.bxane.nuvx.data.model.QueueTier.CONTEXT
            }
            return Song(
                videoId = id,
                title = title,
                artist = artist,
                thumbnailUrl = artwork,
                durationText = duration,
                albumName = album,
                isExplicit = explicit,
                isVideo = video,
                queueTier = resolvedTier,
                queueEntryId = entryId,
                radioName = radio,
                playbackSource = source,
                playbackSourceType = sourceType?.let {
                    runCatching { com.bxane.nuvx.data.model.PlaybackSourceType.valueOf(it) }.getOrNull()
                },
                playbackSourceId = sourceId,
                localUri = local,
                localPath = path,
            )
        }

        companion object {
            fun from(song: Song) = StoredTrack(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                artwork = song.thumbnailUrl,
                auto = song.fromAutoplay,
                tier = song.queueTier.name,
                entryId = song.queueEntryId,
                local = song.localUri,
                path = song.localPath,
                duration = song.durationText,
                album = song.albumName,
                explicit = song.isExplicit,
                video = song.isVideo,
                radio = song.radioName,
                source = song.playbackSource,
                sourceType = song.playbackSourceType?.name,
                sourceId = song.playbackSourceId,
            )
        }
    }

    private const val KEEP_AHEAD = 50
    private const val PREFS_NAME = "nuvx_last_played"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
}
