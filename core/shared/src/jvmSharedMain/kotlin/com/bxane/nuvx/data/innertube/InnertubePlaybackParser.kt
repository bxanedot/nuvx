package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.VersionPair
import com.bxane.nuvx.data.model.VersionTimeMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object InnertubePlaybackParser {
    fun watchQueuePairs(root: JsonElement): Pair<Int, Int> {
        val wrappers = InnertubeParser.collectRenderers(root, "playlistPanelVideoWrapperRenderer")
        return wrappers.size to wrappers.count { wrapper -> wrapperRenderers(wrapper).any { it.isAudioTrack() } }
    }

    private fun wrapperRenderers(wrapper: JsonObject): List<JsonObject> =
        listOfNotNull(wrapper.o("primaryRenderer").o("playlistPanelVideoRenderer")) +
            wrapper.a("counterpart").orEmpty().mapNotNull { it.o("counterpartRenderer").o("playlistPanelVideoRenderer") }

    private fun JsonObject.isAudioTrack(): Boolean = musicVideoType() == MUSIC_VIDEO_TYPE_ATV

    private fun JsonObject.musicVideoType(): String? =
        o("navigationEndpoint").o("watchEndpoint").o("watchEndpointMusicSupportedConfigs")
            .o("watchEndpointMusicConfig").s("musicVideoType")

    private const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"

    fun parseWatchQueue(
        root: JsonElement,
        preferAudio: Boolean,
        preferVideo: Boolean,
        panelSong: (JsonObject) -> Song?,
    ): List<Song> {
        val out = LinkedHashMap<String, Song>()
        val replacements = HashMap<String, JsonObject>()
        val dropped = HashSet<String>()
        if (preferAudio || preferVideo) {
            InnertubeParser.collectRenderers(root, "playlistPanelVideoWrapperRenderer").forEach { wrapper ->
                val halves = wrapperRenderers(wrapper)
                val primary = halves.firstOrNull() ?: return@forEach
                val audio = halves.firstOrNull { if (preferVideo) !it.isAudioTrack() else it.isAudioTrack() }
                    ?: return@forEach
                val primaryId = primary.s("videoId") ?: return@forEach
                replacements[primaryId] = audio
                halves.filter { it !== primary }.mapNotNullTo(dropped) { it.s("videoId") }
            }
        }
        InnertubeParser.collectRenderers(root, "playlistPanelVideoRenderer").forEach { found ->
            val foundId = found.s("videoId") ?: return@forEach
            if (foundId in dropped) return@forEach
            val song = panelSong(replacements[foundId] ?: found) ?: return@forEach
            out[song.videoId] = song
        }
        return out.values.toList()
    }

    fun parseVersionPair(
        root: JsonElement,
        videoId: String,
        wantAudio: Boolean,
        panelSong: (JsonObject) -> Song?,
    ): VersionPair? {
        fun JsonObject.fits(): Boolean = musicVideoType()?.let { (it == MUSIC_VIDEO_TYPE_ATV) == wantAudio } ?: true

        InnertubeParser.collectRenderers(root, "playlistPanelVideoWrapperRenderer").forEach { wrapper ->
            val primary = wrapper.o("primaryRenderer").o("playlistPanelVideoRenderer") ?: return@forEach
            val counterparts = wrapper.a("counterpart").orEmpty().filterIsInstance<JsonObject>()
            fun JsonObject.renderer(): JsonObject? = o("counterpartRenderer").o("playlistPanelVideoRenderer")

            if (primary.s("videoId") == videoId) {
                val match = counterparts.firstOrNull { it.renderer()?.fits() == true } ?: return@forEach
                val song = panelSong(match.renderer() ?: return@forEach) ?: return@forEach
                return VersionPair(song.copy(isVideo = !wantAudio), timeMapOf(match))
            }
            val asCounterpart = counterparts.firstOrNull { it.renderer().s("videoId") == videoId } ?: return@forEach
            if (!primary.fits()) return@forEach
            val song = panelSong(primary) ?: return@forEach
            return VersionPair(song.copy(isVideo = !wantAudio), timeMapOf(asCounterpart)?.inverted())
        }
        return null
    }

    private fun timeMapOf(counterpart: JsonObject): VersionTimeMap? {
        val segments = counterpart.o("segmentMap").a("segment").orEmpty().mapNotNull { segment ->
            val from = segment.s("primaryVideoStartTimeMilliseconds")?.toLongOrNull() ?: return@mapNotNull null
            val to = segment.s("counterpartVideoStartTimeMilliseconds")?.toLongOrNull() ?: return@mapNotNull null
            val length = segment.s("durationMilliseconds")?.toLongOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            VersionTimeMap.Segment(fromStartMs = from, toStartMs = to, durationMs = length)
        }
        return segments.takeIf { it.isNotEmpty() }?.let(::VersionTimeMap)
    }
}
