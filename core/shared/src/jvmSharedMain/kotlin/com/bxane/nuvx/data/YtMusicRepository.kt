package com.bxane.nuvx.data

import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.innertube.Innertube
import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.data.model.Account
import com.bxane.nuvx.data.model.AccountChannel
import com.bxane.nuvx.data.model.isExactArtistMatch
import com.bxane.nuvx.data.model.normalizedArtistName
import com.bxane.nuvx.data.model.ArtistPage
import com.bxane.nuvx.data.model.BrowseType
import com.bxane.nuvx.data.model.HomeFeed
import com.bxane.nuvx.data.model.HomeShelf
import com.bxane.nuvx.data.model.LibraryPage
import com.bxane.nuvx.data.model.LibraryState
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.MoodGenreSection
import com.bxane.nuvx.data.model.PlaylistPrivacy
import com.bxane.nuvx.data.model.SearchFilter
import com.bxane.nuvx.data.model.SearchResult
import com.bxane.nuvx.data.model.ShelfItem
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.SongMenu
import com.bxane.nuvx.data.model.UserPlaylist
import com.bxane.nuvx.data.model.VersionTimeMap
import com.bxane.nuvx.data.model.WatchTarget
import com.bxane.nuvx.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object YtMusicRepository {

    private const val TAG = "nuvx"
    private val moodGenreShelfCache = ConcurrentHashMap<String, List<HomeShelf>>()
    private val audioVersionCache = ConcurrentHashMap<String, Song>()
    private val videoVersionCache = ConcurrentHashMap<String, Song?>()

    private val versionTimeMaps = ConcurrentHashMap<Pair<String, String>, VersionTimeMap>()

    fun cachedAudioVersion(videoId: String): Song? = audioVersionCache[videoId]
    fun cachedVideoVersion(videoId: String): Song? = videoVersionCache[videoId]

    fun rememberVideoVersion(audioId: String, video: Song) {
        videoVersionCache[audioId] = video
    }

    fun versionTimeMap(fromId: String, toId: String): VersionTimeMap? = versionTimeMaps[fromId to toId]

    suspend fun home(): Result<HomeFeed> = call("home") {
        val home = Innertube.browse("FEmusic_home")
        HomeFeed(InnertubeParser.parseHome(home), InnertubeParser.continuationToken(home))
    }

    suspend fun homeRecentlyPlayed(): Result<HomeShelf?> = call("home:recent") { recentlyPlayed() }

    suspend fun homeSupplement(browseId: String): Result<List<HomeShelf>> = call("home:$browseId") {
        shelvesOf(browseId)
    }

    val HOME_SUPPLEMENT_BROWSE_IDS = listOf(
        "FEmusic_new_releases",
        "FEmusic_explore",
    )

    suspend fun moreHome(token: String): Result<HomeFeed> = call("home:more") {
        val response = Innertube.browseContinuation(token)
        HomeFeed(
            shelves = InnertubeParser.parseHomeContinuation(response),
            continuation = InnertubeParser.continuationToken(response),
        )
    }

    private suspend fun recentlyPlayed(): HomeShelf? {
        val ytSongs = if (Innertube.cookie != null) {
            runCatching { fetchHistory() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val allSongs = ytSongs.distinctBy { it.videoId }.take(RECENT_LIMIT)
        if (allSongs.isEmpty()) return null
        return HomeShelf(
            title = RECENT_TITLE,
            items = allSongs.map {
                ShelfItem(
                    title = it.title,
                    subtitle = it.artist,
                    thumbnailUrl = it.thumbnailUrl,
                    videoId = it.videoId,
                    browseId = null,
                )
            },
        )
    }

    private suspend fun fetchHistory(): List<Song> = historyLock.withLock {
        val cookie = Innertube.cookie
        historyMemo?.takeIf { it.cookie == cookie && System.currentTimeMillis() - it.at < HISTORY_SHARE_MS }
            ?.let { return@withLock it.songs }
        InnertubeParser.collectSongsDeep(Innertube.browse(HISTORY)).distinctBy { it.videoId }
            .also { historyMemo = HistoryMemo(cookie, System.currentTimeMillis(), it) }
    }

    private val historyLock = Mutex()

    private const val HISTORY_SHARE_MS = 5_000L

    suspend fun history(): Result<List<Song>> = call("history") { fetchHistory() }

    suspend fun recents(): Result<List<Song>> = call("recents") {
        if (Innertube.cookie != null) {
            val history = runCatching { fetchHistory() }.getOrDefault(emptyList())
            if (history.isNotEmpty()) return@call history
        }
        emptyList()
    }

    suspend fun removeFromHistory(token: String): Result<Unit> = call("removeFromHistory") {
        Innertube.sendFeedback(token)
    }

    fun invalidateHistoryCache() {
        historyMemo = null
    }

    suspend fun quickPicks(excludeSongIds: Set<String> = emptySet()): Result<List<Song>> = call("quickPicks") {
        val homeRaw = runCatching { Innertube.browse("FEmusic_home") }.getOrNull()
        val shelves = if (homeRaw != null) InnertubeParser.parseHome(homeRaw) else emptyList()

        val qpShelf = shelves.firstOrNull {
            it.title.contains("quick", ignoreCase = true) ||
                it.title.contains("pick", ignoreCase = true) ||
                it.title.contains("mix", ignoreCase = true) ||
                it.title.contains("recommend", ignoreCase = true)
        }
        val qpSongs = qpShelf?.items?.mapNotNull { item ->
            item.videoId?.takeUnless { it in excludeSongIds }?.let { vid ->
                Song(
                    videoId = vid,
                    title = item.title,
                    artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                    thumbnailUrl = item.thumbnailUrl,
                )
            }
        }.orEmpty()
        if (qpSongs.isNotEmpty()) return@call qpSongs

        val candidateShelves = if (shelves.size > 1) {
            shelves.drop(1).filterNot {
                it.title.contains("recent", ignoreCase = true) ||
                    it.title.contains("history", ignoreCase = true) ||
                    it.title.contains("listen again", ignoreCase = true)
            }
        } else shelves

        val homeShelfSongs = candidateShelves.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                item.videoId?.takeUnless { it in excludeSongIds }?.let { vid ->
                    Song(
                        videoId = vid,
                        title = item.title,
                        artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            }
        }.distinctBy { it.videoId }

        if (homeShelfSongs.isNotEmpty()) return@call homeShelfSongs

        val allHomeTrackSongs = shelves.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                item.videoId?.let { vid ->
                    Song(
                        videoId = vid,
                        title = item.title,
                        artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            }
        }.distinctBy { it.videoId }

        val uniqueAllHome = allHomeTrackSongs.filterNot { it.videoId in excludeSongIds }
        if (uniqueAllHome.isNotEmpty()) return@call uniqueAllHome

        val explore = runCatching { shelvesOf("FEmusic_new_releases") }.getOrDefault(emptyList())
        val exploreSongs = explore.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                item.videoId?.takeUnless { it in excludeSongIds }?.let { vid ->
                    Song(
                        videoId = vid,
                        title = item.title,
                        artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            }
        }.distinctBy { it.videoId }

        if (exploreSongs.isNotEmpty()) return@call exploreSongs

        allHomeTrackSongs.ifEmpty { exploreSongs }
    }

    private const val HISTORY = "FEmusic_history"
    const val RECENT_TITLE = "Recents"

    private const val RECENT_LIMIT = 20

    private suspend fun shelvesOf(browseId: String): List<HomeShelf> =
        InnertubeParser.parseHome(Innertube.browse(browseId))

    suspend fun moodAndGenres(): Result<List<MoodGenreSection>> = call("moods-and-genres") {
        InnertubeParser.parseMoodAndGenres(Innertube.browse("FEmusic_moods_and_genres"))
    }

    suspend fun moodGenreShelves(browseId: String, params: String?): Result<List<HomeShelf>> {
        val key = "$browseId:${params.orEmpty()}"
        moodGenreShelfCache[key]?.let { return Result.success(it) }
        return call("mood-genre:$browseId") {
            InnertubeParser.parseHome(Innertube.browse(browseId, params))
        }.also { result -> result.getOrNull()?.let { moodGenreShelfCache.putIfAbsent(key, it) } }
    }

    suspend fun podcasts(): Result<List<HomeShelf>> = call("podcasts") {
        val params = InnertubeParser.podcastsChipParams(Innertube.browse("FEmusic_home"))
            ?: error("YouTube Music offered no Podcasts")
        InnertubeParser.parseHome(Innertube.browse("FEmusic_home", params))
    }

    suspend fun moodGenreArtwork(browseId: String, params: String?): Result<String?> =
        moodGenreShelves(browseId, params).map { shelves ->
            shelves.asSequence().flatMap { it.items.asSequence() }
                .mapNotNull(ShelfItem::thumbnailUrl)
                .firstOrNull()
        }

    data class SearchPage(
        val rows: List<SearchResult>,
        val continuation: String?,
    )

    suspend fun searchPage(query: String, filter: SearchFilter, anonymous: Boolean = true): Result<SearchPage> =
        YtMusicSearchRepository.searchPage(query, filter, anonymous)

    suspend fun searchContinuation(token: String, filter: SearchFilter): Result<SearchPage> =
        YtMusicSearchRepository.searchContinuation(token, filter)

    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        YtMusicSearchRepository.search(query, filter)

    suspend fun searchSuggestions(input: String): Result<List<String>> =
        YtMusicSearchRepository.searchSuggestions(input)

    suspend fun searchTypeahead(input: String): Result<SearchPage> =
        YtMusicSearchRepository.searchTypeahead(input)

    suspend fun resolveAudio(song: Song): Song {
        if (!song.isVideo) return song
        audioVersionCache[song.videoId]?.let { return it }
        officialVersion(song, wantAudio = true)?.let { audio ->
            audioVersionCache[song.videoId] = audio
            return audio
        }
        val target = TrackMatcher.targetOf(song)
        var searchFailed = false
        for (query in TrackMatcher.queries(target)) {
            val candidates = search(query, SearchFilter.SONGS)
                .onFailure { searchFailed = true }
                .getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            TrackMatcher.best(candidates, target)?.let { match ->
                Log.d(TAG, "audio switch: '${song.title}' -> '${match.title}' ($query)")
                audioVersionCache[song.videoId] = match
                return match
            }
            TrackMatcher.bestOfficialAudioForVideo(candidates, target)?.let { match ->
                Log.d(TAG, "audio switch: accepted video/runtime drift '${song.title}' -> '${match.title}' ($query)")
                audioVersionCache[song.videoId] = match
                return match
            }
        }
        Log.w(TAG, "audio switch: no official song match for '${song.title}' by '${song.artist}'")
        if (!searchFailed) audioVersionCache[song.videoId] = song
        return song
    }

    suspend fun resolveVideo(song: Song): Song? {
        if (song.isVideo) return null
        videoVersionCache[song.videoId]?.let { return it }
        officialVersion(song, wantAudio = false)?.let { video ->
            videoVersionCache[song.videoId] = video
            return video
        }
        val target = TrackMatcher.targetOf(song)
        for (query in TrackMatcher.queries(target)) {
            val candidates = search(query, SearchFilter.VIDEOS)
                .getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            TrackMatcher.best(candidates, target)?.let { match ->
                Log.d(TAG, "video switch: '${song.title}' -> '${match.title}' ($query)")
                videoVersionCache[song.videoId] = match
                return match
            }
        }
        Log.w(TAG, "video switch: no video match for '${song.title}' by '${song.artist}'")
        videoVersionCache[song.videoId] = null
        return null
    }

    private suspend fun officialVersion(song: Song, wantAudio: Boolean): Song? {
        if (Innertube.cookie == null) return null
        val pair = try {
            InnertubeParser.parseVersionPair(Innertube.next(song.videoId), song.videoId, wantAudio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "version pair lookup failed for '${song.title}': ${e.message}")
            null
        } ?: run {
            Log.d(TAG, "version pair: YouTube pairs nothing with '${song.title}'")
            return null
        }
        pair.timeMap?.let { map ->
            versionTimeMaps[song.videoId to pair.song.videoId] = map
            versionTimeMaps[pair.song.videoId to song.videoId] = map.inverted()
        }
        Log.d(
            TAG,
            "version pair: '${song.title}' -> '${pair.song.title}' (${pair.song.videoId}), " +
                "segments=${pair.timeMap?.segments?.size ?: 0}",
        )
        return pair.song
    }

    suspend fun account(): Result<Account> = call("account") {
        InnertubeParser.parseAccount(Innertube.accountMenu())
            ?: error("No account details")
    }

    suspend fun accountChannels(): Result<List<AccountChannel>> = call("channels") {
        val viaInnertube = runCatching {
            InnertubeParser.parseAccountChannels(Innertube.accountsList())
        }.onFailure { Log.w(TAG, "accounts_list unavailable: ${it.message}") }
            .getOrNull()
            .orEmpty()
        if (viaInnertube.isNotEmpty()) return@call viaInnertube
        InnertubeParser.parseAccountChannels(Innertube.accountSwitcher())
    }

    suspend fun library(): Result<LibraryPage> = call("library") {
        coroutineScope {
            val liked = async { browseSongs(LIKED_MUSIC).getOrNull() }
            val added = async { runCatching { songsPaged(LIBRARY_SONGS) }.getOrDefault(emptyList()) }
            val shelves = LIBRARY_FEEDS
                .map { (title, browseId) ->
                    async {
                        HomeShelf(title, runCatching { libraryItemsPaged(browseId) }.getOrDefault(emptyList()))
                    }
                }
                .awaitAll()
                .filter { it.items.isNotEmpty() }

            val likedPage = liked.await()
            val likedSongs = likedPage?.songs.orEmpty()
            val likedIds = likedSongs.mapTo(HashSet()) { it.videoId }
            LikeState.seedLiked(likedIds)
            LibraryPage(
                likedSongs = likedSongs,
                librarySongs = added.await().filterNot { it.videoId in likedIds },
                shelves = shelves,
                likedContinuation = likedPage?.continuation,
            )
        }
    }

    suspend fun syncLikedMusic(
        firstToken: String?,
        loadNext: suspend (String) -> SongPage? = { moreSongs(it).getOrNull() },
    ) {
        if (firstToken == null) return
        val seen = HashSet<String>()
        var next: String? = firstToken
        while (next != null && seen.add(next)) {
            val page = loadNext(next) ?: return
            LikeState.seedLiked(page.songs.mapTo(HashSet()) { it.videoId })
            next = page.continuation
        }
    }

    suspend fun radio(videoId: String, preferVideo: Boolean = false): Result<List<Song>> =
        call("radio:$videoId:$preferVideo") {
            InnertubeParser.parseWatchQueue(Innertube.next(videoId), preferVideo = preferVideo)
        }

    suspend fun findArtistPageId(name: String): Result<String?> = call("artistByName:$name") {
        if (normalizedArtistName(name).isEmpty()) return@call null
        val filter = SearchFilter.ARTISTS
        InnertubeParser.parseSearchPage(Innertube.search(name, filter.params)).rows
            .filterIsInstance<SearchResult.Browse>()
            .map { it.item }
            .firstOrNull { it.type == BrowseType.ARTIST && isExactArtistMatch(it.title, name) }
            ?.browseId
    }

    suspend fun trackLinks(videoId: String): Result<Song> = call("links:$videoId") {
        InnertubeParser.parseWatchQueue(Innertube.next(videoId))
            .firstOrNull { it.videoId == videoId }
            ?: error("no watch entry for $videoId")
    }

    data class SongPage(
        val songs: List<Song>,
        val continuation: String?,
        val suggested: List<Song> = emptyList(),
        val library: LibraryState? = null,
        val owned: Boolean? = null,
        val header: InnertubeParser.BrowseHeader? = null,
        val description: String? = null,
    )

    suspend fun browseSongs(browseId: String): Result<SongPage> = call("browse:$browseId") {
        val response = Innertube.browse(browseId)
        val page = if (browseId.startsWith("MPREb")) {
            albumPageOf(response)
        } else {
            pageOf(response)
        }
        if (!browseId.startsWith("VL")) page
        else page.copy(owned = InnertubeParser.parsePlaylistOwned(response))
    }

    private suspend fun albumPageOf(albumResponse: JsonObject): SongPage {
        val metadata = pageOf(albumResponse)
        val playlist = albumPlaylistOf(albumResponse, metadata.songs.size) ?: return metadata
        return pageOf(playlist).copy(
            library = metadata.library,
            header = metadata.header,
            description = metadata.description,
        )
    }

    private suspend fun albumPlaylistOf(albumResponse: JsonObject, albumRows: Int): JsonObject? {
        val playlistId = InnertubeParser.parseAlbumPlaylistId(albumResponse) ?: return null
        val response = try {
            Innertube.browse("VL${playlistId.removePrefix("VL")}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "album playlist $playlistId failed, keeping album rows: ${e.message}")
            return null
        }
        val shelf = InnertubeParser.parsePlaylistShelf(response)
        val rows = (shelf?.songs ?: InnertubeParser.collectSongsDeep(response)).size
        val more = (shelf?.continuation ?: InnertubeParser.continuationToken(response)) != null
        if (rows < albumRows && !more) {
            Log.w(TAG, "album playlist $playlistId has $rows rows vs album's $albumRows, keeping album rows")
            return null
        }
        return response
    }

    suspend fun moreSongs(token: String): Result<SongPage> = call("browse:more") {
        pageOf(Innertube.browseContinuation(token), showByToken[token])
    }

    private val showByToken = ConcurrentHashMap<String, String>()

    suspend fun playlistOwned(browseId: String): Result<Boolean?> = call("owner:$browseId") {
        InnertubeParser.parsePlaylistOwned(Innertube.browse(browseId))
    }

    private fun pageOf(response: JsonObject, show: String? = null): SongPage {
        val library = InnertubeParser.parseLibraryState(response)
        val header = InnertubeParser.parseBrowseHeader(response)
        InnertubeParser.parsePlaylistShelf(response)?.let { shelf ->
            return SongPage(shelf.songs, shelf.continuation, shelf.suggested, library, header = header)
        }
        val podcast = InnertubeParser.podcastShow(response) ?: show
        val continuation = InnertubeParser.continuationToken(response)
        if (podcast != null && continuation != null) showByToken[continuation] = podcast
        return SongPage(
            songs = InnertubeParser.collectSongsDeep(response, podcast).distinctBy { it.videoId },
            continuation = continuation,
            library = library,
            header = header,
            description = InnertubeParser.parseDescription(response),
        )
    }

    suspend fun allSongs(browseId: String): Result<List<Song>> = call("all:$browseId") {
        songsPaged(browseId).ifEmpty { error("No tracks here") }
    }

    private suspend fun songsPaged(browseId: String): List<Song> {
        val out = LinkedHashMap<String, Song>()
        var response = Innertube.browse(browseId)
        if (browseId.startsWith("MPREb")) {
            val albumRows = InnertubeParser.collectSongsDeep(response).size
            albumPlaylistOf(response, albumRows)?.let { response = it }
        }
        var page = 1
        val show = InnertubeParser.podcastShow(response)
        while (true) {
            val shelf = InnertubeParser.parsePlaylistShelf(response)
            (shelf?.songs ?: InnertubeParser.collectSongsDeep(response, show)).forEach { out[it.videoId] = it }
            val token = shelf?.continuation ?: InnertubeParser.continuationToken(response)
            if (token == null || page++ >= MAX_PAGES) break
            response = runCatching { Innertube.browseContinuation(token) }.getOrNull() ?: break
        }
        return out.values.toList()
    }

    private suspend fun itemsPaged(browseId: String, params: String? = null): List<ShelfItem> {
        val out = LinkedHashMap<String, ShelfItem>()
        var response = Innertube.browse(browseId, params)
        var page = 1
        while (true) {
            val parsed = InnertubeParser.parseLibraryItemPage(response)
            parsed.items.forEach { item ->
                val key = item.browseId ?: item.videoId ?: "${item.title}\n${item.subtitle}"
                out.putIfAbsent(key, item)
            }
            val token = parsed.continuation ?: break
            if (page++ >= MAX_PAGES) break
            response = runCatching { Innertube.browseContinuation(token) }.getOrNull() ?: break
        }
        return out.values.toList()
    }

    private suspend fun libraryItemsPaged(browseId: String): List<ShelfItem> = itemsPaged(browseId, null)

    const val MAX_PAGES = 10

    const val LIKED_MUSIC = "VLLM"

    private const val LIBRARY_SONGS = "FEmusic_liked_videos"

    private const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"

    const val PLAYLISTS_SHELF = "Playlists"

    private val LIBRARY_FEEDS = listOf(
        PLAYLISTS_SHELF to LIBRARY_PLAYLISTS,
        "Albums" to "FEmusic_liked_albums",
        "Artists" to "FEmusic_library_corpus_track_artists",
        "Subscriptions" to "FEmusic_library_corpus_artists",
        "Podcasts" to "FEmusic_library_non_music_audio_list",
    )

    suspend fun songMenu(videoId: String): Result<SongMenu> = call("menu:$videoId") {
        InnertubeParser.parseSongMenu(Innertube.next(videoId), videoId)
            ?: error("no menu for $videoId")
    }

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> =
        call("rate:$videoId") { Innertube.rate(videoId, status) }

    suspend fun setLibraryStatus(token: String): Result<Unit> =
        call("library:feedback") { Innertube.sendFeedback(token) }

    suspend fun releaseLibraryState(browseId: String): Result<LibraryState?> =
        call("library-state:$browseId") { InnertubeParser.parseLibraryState(Innertube.browse(browseId)) }

    suspend fun setSaved(playlistId: String, saved: Boolean): Result<Unit> =
        call("library:$playlistId") { Innertube.ratePlaylist(playlistId, saved) }

    suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> =
        call("subscription:$channelId") { Innertube.setSubscribed(channelId, subscribed) }

    suspend fun userPlaylists(): Result<List<UserPlaylist>> = call("playlists") {
        InnertubeParser.parseUserPlaylists(libraryItemsPaged(LIBRARY_PLAYLISTS))
    }

    suspend fun libraryPlaylists(): Result<List<ShelfItem>> = call("libraryPlaylists") {
        InnertubeParser.parseLibraryItems(Innertube.browse(LIBRARY_PLAYLISTS))
    }

    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String> = emptyList(),
    ): Result<String> = YtMusicPlaylistRepository.createPlaylist(title, privacy, videoIds)

    suspend fun addToPlaylist(
        playlistId: String,
        videoIds: List<String>,
    ): Result<Map<String, String>> =
        YtMusicPlaylistRepository.addToPlaylist(playlistId, videoIds)

    suspend fun removeFromPlaylist(
        playlistId: String,
        entries: List<Pair<String, String>>,
    ): Result<Unit> = YtMusicPlaylistRepository.removeFromPlaylist(playlistId, entries)

    suspend fun playlistEntries(browseId: String): Result<List<Song>> = call("entries:$browseId") {
        val out = LinkedHashMap<String, Song>()
        var response = Innertube.browse(browseId)
        var page = 1
        while (true) {
            val shelf = InnertubeParser.parsePlaylistShelf(response) ?: break
            shelf.songs.forEach { song ->
                val setVideoId = song.setVideoId ?: error("playlist entry without an id")
                out.putIfAbsent(setVideoId, song)
            }
            val token = shelf.continuation ?: break
            if (page++ >= MAX_PAGES) error("playlist too long to reorder")
            response = Innertube.browseContinuation(token)
        }
        out.values.toList()
    }

    suspend fun reorderPlaylist(
        playlistId: String,
        current: List<String>,
        target: List<String>,
    ): Result<Unit> = YtMusicPlaylistRepository.reorderPlaylist(playlistId, current, target)

    suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit> =
        YtMusicPlaylistRepository.renamePlaylist(playlistId, title)

    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        YtMusicPlaylistRepository.deletePlaylist(playlistId)

    suspend fun artistPage(browseId: String): Result<ArtistPage> = call("artist:$browseId") {
        val page = InnertubeParser.parseArtistPage(Innertube.browse(browseId))
        coroutineScope {
            val fullSongsDeferred = async {
                page.moreSongsBrowseId?.let { playlistId ->
                    runCatching { songsPaged(playlistId) }.getOrNull()
                }
            }
            val shelvesDeferred = page.sections.map { shelf ->
                async {
                    val moreBrowseId = shelf.moreBrowseId
                    if (moreBrowseId != null) {
                        val fullItems = runCatching {
                            itemsPaged(moreBrowseId, shelf.moreParams)
                        }.getOrNull()
                        if (!fullItems.isNullOrEmpty()) {
                            shelf.copy(items = fullItems)
                        } else {
                            shelf
                        }
                    } else {
                        shelf
                    }
                }
            }
            val fullSongs = fullSongsDeferred.await()
            val fullShelves = shelvesDeferred.awaitAll()
            val resolvedSongs = if (!fullSongs.isNullOrEmpty()) fullSongs else page.songs
            page.copy(songs = resolvedSongs, sections = fullShelves)
        }
    }

    suspend fun artistLanding(browseId: String): Result<ArtistPage> = call("artistLanding:$browseId") {
        InnertubeParser.parseArtistPage(Innertube.browse(browseId))
    }

    suspend fun watchQueue(target: WatchTarget): Result<List<Song>> = call("watch:${target.playlistId}") {
        InnertubeParser.parseWatchQueue(
            Innertube.nextPlaylist(target.playlistId, target.videoId, target.params),
        ).filterNot { it.isVideo }
    }

    suspend fun playlistAsAudio(playlistId: String): Result<List<Song>> = call("watchAudio:$playlistId") {
        val response = Innertube.nextPlaylist(playlistId)
        val songs = InnertubeParser.parseWatchQueue(response, preferAudio = true)
        val (pairs, audioPairs) = InnertubeParser.watchQueuePairs(response)
        Log.d(TAG, "watchAudio $playlistId rows=${songs.size} pairs=$pairs audioPairs=$audioPairs videosLeft=${songs.count { it.isVideo }}")
        songs
    }

    suspend fun mixedForYou(): Result<List<ShelfItem>> = call("mixedForYou") {
        if (Innertube.cookie == null) return@call emptyList()
        InnertubeParser.parseTwoRowItemsDeep(Innertube.browse("FEmusic_mixed_for_you"))
    }

    suspend fun recentHistory(): Result<List<Song>> = call("history:recent") {
        if (Innertube.cookie == null) return@call emptyList()
        val memo = historyMemo
        if (memo != null && memo.cookie == Innertube.cookie &&
            System.currentTimeMillis() - memo.at < HISTORY_MEMO_MS
        ) {
            memo.songs
        } else {
            fetchHistory()
        }
    }

    private class HistoryMemo(val cookie: String?, val at: Long, val songs: List<Song>)

    @Volatile
    private var historyMemo: HistoryMemo? = null

    private const val HISTORY_MEMO_MS = 120_000L

    internal suspend fun <T> call(label: String, block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching { block() }.recoverCatching { failure ->
                val rejected = failure.message?.contains("401") == true ||
                    failure.message?.contains("403") == true ||
                    failure.message?.contains("rejected", true) == true
                if (!rejected || Innertube.cookie == null) throw failure
                Log.w(TAG, "$label rejected; refreshing active session context once")
                Innertube.refreshSessionScope()
                block()
            }
                .onFailure { if (it is CancellationException) throw it }
                .onSuccess { Log.d(TAG, "$label ok") }
                .onFailure { Log.w(TAG, "$label failed: ${it.message}") }
        }
}
