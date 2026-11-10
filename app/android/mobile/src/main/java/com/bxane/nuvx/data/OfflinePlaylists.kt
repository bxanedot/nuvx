package com.bxane.nuvx.data

import android.content.Context
import android.content.SharedPreferences
import com.bxane.nuvx.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class OfflinePlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = OfflinePlaylists.PREFIX + id
    val isLiked: Boolean get() = id == OfflinePlaylists.LIKED_ID
}

object OfflinePlaylists {
    const val PREFIX = "local:offline:"
    const val LIKED_ID = "liked"

    private const val PREF_NAME = "nuvx_offline_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var likedTitle = "Liked songs"

    private val _playlists = MutableStateFlow(listOf(OfflinePlaylist(LIKED_ID, likedTitle, emptyList())))
    val playlists: StateFlow<List<OfflinePlaylist>> = _playlists.asStateFlow()

    private val _liked = MutableStateFlow<Set<String>>(emptySet())

    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    fun init(context: Context, likedTitle: String) {
        if (this::prefs.isInitialized) return
        this.likedTitle = likedTitle
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        publish(load())
        val loaded = _playlists.value
        io.launch {
            playlists.filter { it !== loaded }.conflate().collect { persist(it) }
        }
    }

    fun isOfflinePlaylist(browseId: String?): Boolean = browseId?.startsWith(PREFIX) == true

    fun isLikedPage(browseId: String?): Boolean = browseId == PREFIX + LIKED_ID

    fun get(browseId: String?): OfflinePlaylist? {
        val id = browseId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        return _playlists.value.firstOrNull { it.id == id }
    }

    fun isLiked(song: Song): Boolean = song.localUri?.let { it in _liked.value } == true

    fun toggleLike(song: Song): Boolean {
        val key = song.localUri ?: return false
        var nowLiked = false
        mutate { list ->
            nowLiked = key !in _liked.value
            list.map { playlist ->
                if (!playlist.isLiked) return@map playlist
                val rest = playlist.songs.filterNot { it.localUri == key }
                playlist.copy(songs = if (nowLiked) listOf(song.stored()) + rest else rest)
            }
        }
        return nowLiked
    }

    fun create(title: String, songs: List<Song> = emptyList()): OfflinePlaylist {
        val playlist = OfflinePlaylist(
            id = "p" + System.currentTimeMillis().toString(36),
            title = title.trim(),
            songs = songs.filter { it.localUri != null }.distinctBy { it.localUri }.map { it.stored() },
        )
        mutate { it + playlist }
        return playlist
    }

    class AddResult(val added: Int, val alreadyThere: Int)

    fun add(ids: Collection<String>, songs: List<Song>): AddResult {
        val incoming = songs.filter { it.localUri != null }.distinctBy { it.localUri }
        var added = 0
        var alreadyThere = 0
        mutate { list ->
            list.map { playlist ->
                if (playlist.id !in ids) return@map playlist
                val known = playlist.songs.mapTo(HashSet()) { it.localUri }
                val fresh = incoming.filter { known.add(it.localUri) }.map { it.stored() }
                if (fresh.isEmpty()) {
                    alreadyThere++
                    return@map playlist
                }
                added++
                playlist.copy(songs = if (playlist.isLiked) fresh + playlist.songs else playlist.songs + fresh)
            }
        }
        return AddResult(added, alreadyThere)
    }

    fun remove(browseId: String, song: Song) {
        val id = browseId.removePrefix(PREFIX)
        val key = song.localUri ?: return
        mutate { list ->
            list.map { if (it.id == id) it.copy(songs = it.songs.filterNot { s -> s.localUri == key }) else it }
        }
    }

    fun rename(browseId: String, title: String) {
        val id = browseId.removePrefix(PREFIX)
        if (id == LIKED_ID || title.isBlank()) return
        mutate { list -> list.map { if (it.id == id) it.copy(title = title.trim()) else it } }
    }

    fun delete(browseId: String) {
        val id = browseId.removePrefix(PREFIX)
        if (id == LIKED_ID) return
        mutate { list -> list.filterNot { it.id == id } }
    }

    private inline fun mutate(change: (List<OfflinePlaylist>) -> List<OfflinePlaylist>) {
        synchronized(lock) { publish(change(_playlists.value)) }
    }

    private fun publish(list: List<OfflinePlaylist>) {
        val liked = list.firstOrNull { it.isLiked } ?: OfflinePlaylist(LIKED_ID, likedTitle, emptyList())
        val ordered = listOf(liked.copy(title = likedTitle)) + list.filterNot { it.isLiked }
        _playlists.value = ordered
        _liked.value = liked.songs.mapNotNullTo(HashSet()) { it.localUri }
    }

    private fun Song.stored(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        localUri = localUri,
        localPath = localPath,
        localDateAddedSeconds = localDateAddedSeconds,
        localDateModifiedSeconds = localDateModifiedSeconds,
        trackNumber = trackNumber,
    )

    private fun load(): List<OfflinePlaylist> {
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val songs = obj.getJSONArray("songs")
                OfflinePlaylist(
                    id = obj.getString("id"),
                    title = obj.optString("title"),
                    songs = (0 until songs.length()).mapNotNull { songFrom(songs.getJSONObject(it)) },
                )
            }
        }.getOrElse {
            DebugLog.w("nuvx", "Offline playlists unreadable: ${it.message}")
            emptyList()
        }
    }

    private fun persist(list: List<OfflinePlaylist>) {
        runCatching {
            val array = JSONArray()
            list.forEach { playlist ->
                val songs = JSONArray()
                playlist.songs.forEach { songs.put(it.toJson()) }
                array.put(
                    JSONObject()
                        .put("id", playlist.id)
                        .put("title", playlist.title)
                        .put("songs", songs),
                )
            }
            prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
        }.onFailure { DebugLog.w("nuvx", "Offline playlists not saved: ${it.message}") }
    }

    private fun Song.toJson(): JSONObject = JSONObject().apply {
        put("videoId", videoId)
        put("title", title)
        put("artist", artist)
        putOpt("thumbnailUrl", thumbnailUrl)
        putOpt("durationText", durationText)
        putOpt("artistId", artistId)
        putOpt("albumId", albumId)
        putOpt("albumName", albumName)
        putOpt("localUri", localUri)
        putOpt("localPath", localPath)
        localDateAddedSeconds?.let { put("added", it) }
        localDateModifiedSeconds?.let { put("modified", it) }
        trackNumber?.let { put("track", it) }
    }

    private fun songFrom(obj: JSONObject): Song? {
        fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
        val uri = obj.text("localUri") ?: return null
        return Song(
            videoId = obj.text("videoId") ?: uri,
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            thumbnailUrl = obj.text("thumbnailUrl"),
            durationText = obj.text("durationText"),
            artistId = obj.text("artistId"),
            albumId = obj.text("albumId"),
            albumName = obj.text("albumName"),
            localUri = uri,
            localPath = obj.text("localPath"),
            localDateAddedSeconds = if (obj.has("added")) obj.getLong("added") else null,
            localDateModifiedSeconds = if (obj.has("modified")) obj.getLong("modified") else null,
            trackNumber = if (obj.has("track")) obj.getInt("track") else null,
        )
    }
}

fun OfflinePlaylist.toGroup(): LocalGroup = LocalGroup(
    browseId = browseId,
    title = title,
    subtitle = "",
    thumbnailUrl = if (isLiked) null else songs.firstNotNullOfOrNull { it.thumbnailUrl },
    type = com.bxane.nuvx.data.model.BrowseType.PLAYLIST,
    songs = songs,
)
