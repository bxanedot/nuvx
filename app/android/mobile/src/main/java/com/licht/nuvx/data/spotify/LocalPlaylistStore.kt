package com.licht.nuvx.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.licht.nuvx.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

object LocalPlaylistStore {
    private const val PREF_NAME = "nuvx_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"
    private const val KEY_REORDER_CHECKPOINT = "reorder_checkpoint_v1"
    private const val MAX_CHECKPOINT_BYTES = 4 * 1024 * 1024

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        durationText = sObj.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        artistId = sObj.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumId = sObj.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumName = sObj.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        isVideo = sObj.optBoolean("isVideo", false),
                    )
                    songs.add(song)
                }
                list.add(LocalPlaylist(id, title, songs))
            }
            _playlists.value = list
        }
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    fun createNuvxPlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val playlist = LocalPlaylist(id = "nuvx_local_" + java.util.UUID.randomUUID(), title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    fun addSong(id: String, song: Song): Boolean {
        val current = getPlaylist(id) ?: return false
        if (current.songs.any { it.videoId == song.videoId }) return false
        val updated = _playlists.value.map { if (it.id == current.id) it.copy(songs = it.songs + song) else it }
        _playlists.value = updated
        persist(updated)
        return true
    }

    fun removeSong(id: String, videoId: String): Boolean {
        val current = getPlaylist(id) ?: return false
        val updated = _playlists.value.map { if (it.id == current.id) it.copy(songs = it.songs.filterNot { song -> song.videoId == videoId }) else it }
        _playlists.value = updated
        persist(updated)
        return true
    }

    /** Undo of [removeSong]: puts [song] back at [at] unless it is already there again. */
    fun putBack(id: String, song: Song, at: Int): Boolean {
        val current = getPlaylist(id) ?: return false
        if (current.songs.any { it.videoId == song.videoId }) return false
        val songs = current.songs.toMutableList().apply { add(at.coerceIn(0, size), song) }
        val updated = _playlists.value.map { if (it.id == current.id) it.copy(songs = songs) else it }
        _playlists.value = updated
        persist(updated)
        return true
    }

    /** Undo/redo only if no newer edit has changed this playlist in the meantime. */
    @Synchronized
    fun replaceSongsIfUnchanged(id: String, expected: List<Song>, replacement: List<Song>): Boolean {
        val current = getPlaylist(id) ?: return false
        if (current.songs != expected) return false
        if (replacement == expected) return true
        val after = current.copy(songs = replacement)
        val checkpoint = createCheckpoint(current, after) ?: return false
        val updated = _playlists.value.map {
            if (it.id == current.id) after else it
        }
        // Commit the prior version and new playlist in the *same* transaction.
        // On failure, neither the old data nor the checkpoint is replaced.
        if (!persistCommitted(updated, checkpoint)) return false
        _playlists.value = updated
        return true
    }

    fun replaceSongs(id: String, songs: List<Song>): Boolean {
        val current = getPlaylist(id) ?: return false
        val updated = _playlists.value.map { if (it.id == current.id) it.copy(songs = songs) else it }
        _playlists.value = updated
        persist(updated)
        return true
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    /** Puts back a playlist removed by [deletePlaylist] at its old position (undo). */
    fun restore(playlist: LocalPlaylist, at: Int) {
        if (_playlists.value.any { it.id == playlist.id }) return
        val updated = _playlists.value.toMutableList().apply { add(at.coerceIn(0, size), playlist) }
        _playlists.value = updated
        persist(updated)
    }

    /** Adds restored playlists; one whose id already exists is skipped, never overwritten. Returns how many were added. */
    fun mergeIn(incoming: List<LocalPlaylist>): Int {
        val have = _playlists.value.mapTo(HashSet()) { it.id }
        val added = incoming.filter { it.id.isNotBlank() && have.add(it.id) }
        if (added.isEmpty()) return 0
        val updated = _playlists.value + added
        _playlists.value = updated
        persist(updated)
        return added.size
    }

    /**
     * Replaces every playlist with [updated] (a sync result), but only if the list is still [expected], so an edit
     * made while the sync ran is never overwritten; the next sync picks it up. Returns false when skipped.
     */
    @Synchronized
    fun replaceAllIfUnchanged(expected: List<LocalPlaylist>, updated: List<LocalPlaylist>): Boolean {
        if (_playlists.value != expected) return false
        if (updated == expected) return true
        _playlists.value = updated
        persist(updated)
        return true
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persistCommitted(list: List<LocalPlaylist>, checkpoint: String? = null): Boolean {
        if (!this::prefs.isInitialized) return true
        return runCatching {
            prefs.edit().putString(KEY_PLAYLISTS, encode(list)).apply {
                if (checkpoint != null) putString(KEY_REORDER_CHECKPOINT, checkpoint)
            }.commit()
        }.getOrDefault(false)
    }

    /** One previous-version checkpoint only, in the legacy playlist JSON format. */
    private fun createCheckpoint(before: LocalPlaylist, after: LocalPlaylist): String? = runCatching {
        if (before.id != after.id || before.id.isBlank()) return@runCatching null
        val raw = JSONObject()
            .put("version", 1)
            .put("playlistId", before.id)
            .put("before", encode(listOf(before)))
            .put("after", encode(listOf(after)))
            .toString()
        raw.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_CHECKPOINT_BYTES }
    }.getOrNull()

    private fun decodePlaylistList(raw: String): List<LocalPlaylist>? = runCatching {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_CHECKPOINT_BYTES) return@runCatching null
        val array = JSONArray(raw)
        if (array.length() != 1) return@runCatching null
        val item = array.getJSONObject(0)
        val songs = item.getJSONArray("songs")
        if (songs.length() > 20_000) return@runCatching null
        val playlist = LocalPlaylist(
            id = item.getString("id"),
            title = item.getString("title"),
            songs = (0 until songs.length()).map { index ->
                val s = songs.getJSONObject(index)
                Song(
                    videoId = s.optString("videoId", ""),
                    title = s.optString("title", ""),
                    artist = s.optString("artist", ""),
                    thumbnailUrl = s.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                    durationText = s.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                    artistId = s.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                    albumId = s.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                    albumName = s.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                    isVideo = s.optBoolean("isVideo", false),
                )
            },
        )
        listOf(playlist)
    }.getOrNull()

    /** Returns the previous playlist only if no later edit changed that playlist. */
    @Synchronized
    fun previousReorderVersion(id: String): LocalPlaylist? {
        if (!this::prefs.isInitialized) return null
        val current = getPlaylist(id) ?: return null
        val raw = runCatching { prefs.getString(KEY_REORDER_CHECKPOINT, null) }.getOrNull() ?: return null
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_CHECKPOINT_BYTES) return null
        return runCatching {
            val data = JSONObject(raw)
            if (data.getInt("version") != 1 || data.getString("playlistId") != current.id) return@runCatching null
            val beforeText = data.getString("before")
            val afterText = data.getString("after")
            if (decodePlaylistList(afterText)?.singleOrNull()?.id != current.id) return@runCatching null
            val before = decodePlaylistList(beforeText)?.singleOrNull() ?: return@runCatching null
            if (before.id != current.id || encode(listOf(current)) != afterText) return@runCatching null
            before
        }.getOrNull()
    }

    /** Restore only one playlist, refusing concurrent edits, corruption and replay. */
    @Synchronized
    fun restoreLastReorderVersion(id: String): Boolean {
        val before = previousReorderVersion(id) ?: return false
        val updated = _playlists.value.map { if (it.id == before.id) before else it }
        if (!runCatching {
                prefs.edit()
                    .putString(KEY_PLAYLISTS, encode(updated))
                    .remove(KEY_REORDER_CHECKPOINT)
                    .commit()
            }.getOrDefault(false)) return false
        _playlists.value = updated
        return true
    }

    internal fun initForTesting(preferences: SharedPreferences) {
        prefs = preferences
        load()
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching { prefs.edit().putString(KEY_PLAYLISTS, encode(list)).apply() }
    }

    private fun encode(list: List<LocalPlaylist>): String {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            return jsonArray.toString()
    }
}
