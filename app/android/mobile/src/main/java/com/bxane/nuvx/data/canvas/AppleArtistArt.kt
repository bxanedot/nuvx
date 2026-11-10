package com.bxane.nuvx.data.canvas

import com.bxane.nuvx.data.DebugLog as Log
import com.bxane.nuvx.data.AppleCatalogue
import com.bxane.nuvx.data.Http
import com.bxane.nuvx.data.model.DetailPage
import com.bxane.nuvx.data.model.UiState
import androidx.compose.ui.graphics.Color
import com.bxane.nuvx.ui.theme.ArtworkKeyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class AppleArtistArt(
    val heroUrl: String,
    val logoUrl: String?,
    val logoAspect: Float,
    val videoUrl: String?,
    val background: Int,
    val accent: Int,
    val keyColor: Int?,
)

fun AppleArtistArt.keyColors() = ArtworkKeyColors(Color(background), Color(accent))

object AppleArtistArtRepository {

    private const val TAG = "AppleArtistArt"
    private const val CACHE_SIZE = 64
    private const val HERO_WIDTH = 1200
    private const val LOGO_WIDTH = 1000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private class Entry(val art: AppleArtistArt?)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CACHE_SIZE
    }

    private val _updates = MutableStateFlow(0)

    val updates: StateFlow<Int> = _updates

    fun cached(browseId: String): AppleArtistArt? =
        synchronized(cache) { cache[browseId] }?.art

    suspend fun artFor(
        browseId: String,
        artistName: String,
        catalogue: suspend () -> Collection<String>,
    ): AppleArtistArt? {
        val key = artistName.normalized()
        if (key.isEmpty()) return null
        synchronized(cache) { cache[browseId] }?.let { return it.art }
        val titles = AppleCatalogue.keys(runCatching { catalogue() }.getOrDefault(emptyList()))
        if (titles.isEmpty()) return null

        val entry = withContext(Dispatchers.IO) {
            runCatching { lookup(artistName, key, titles) }
                .onFailure { Log.d(TAG, "lookup failed for $artistName: $it") }
                .getOrNull()
        } ?: return null
        synchronized(cache) { cache[browseId] = entry }
        _updates.value++
        return entry.art
    }

    private fun lookup(artistName: String, key: String, titles: Set<String>): Entry? {
        val search = get(
            "https://itunes.apple.com/search?term=${URLEncoder.encode(artistName, "UTF-8")}" +
                "&entity=musicArtist&limit=5",
        ) ?: return null
        val results = JSONObject(search).optJSONArray("results") ?: JSONArray()
        var page: String? = null
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (r.optString("artistName").normalized() != key) continue
            val id = r.optLong("artistId").takeIf { it > 0 } ?: continue
            if (!AppleCatalogue.sharesTitles(id, titles)) {
                Log.d(TAG, "$artistName: Apple ${r.optString("artistName")} ($id) shares no titles, skipped")
                continue
            }
            page = r.optString("artistLinkUrl").substringBefore('?').takeIf { it.isNotBlank() }
                ?: "https://music.apple.com/us/artist/$id"
            break
        }
        if (page == null) return Entry(null)

        val html = get(page) ?: return null
        return Entry(parse(html))
    }

    private fun parse(html: String): AppleArtistArt? {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) return null
        val header = findHeader(JSONObject(html.substring(start, end).let { "{\"d\":$it}" }).get("d"))
            ?: return null

        val logo = header.optJSONObject("artistLogo")?.optJSONObject("dictionary")
        val hero = (header.optJSONObject("artwork") ?: header.optJSONObject("colorBackdropArtwork"))
            ?.optJSONObject("dictionary") ?: return null

        val logoW = logo?.optInt("width") ?: 0
        val logoH = logo?.optInt("height") ?: 0
        val heroW = hero.optInt("width").takeIf { it > 0 } ?: return null
        val heroH = hero.optInt("height").takeIf { it > 0 } ?: return null

        val heroUrl = hero.optString("url").fill(
            HERO_WIDTH, (HERO_WIDTH.toLong() * heroH / heroW).toInt(), "jpg",
        ) ?: return null
        val logoUrl = if (logo != null && logoW > 0 && logoH > 0) {
            logo.optString("url").fill(LOGO_WIDTH, (LOGO_WIDTH.toLong() * logoH / logoW).toInt(), "png")
        } else {
            null
        }
        val clip = header.optJSONObject("videoArtwork")?.optJSONObject("dictionary")
            ?.optJSONObject("motionArtistSquare1x1")
        val video = clip?.optString("video")?.takeIf { it.startsWith("http") }
        val colours = clip?.optJSONObject("previewFrame")?.takeIf { video != null } ?: hero
        val background = colours.optString("bgColor").toArgb()
            ?: hero.optString("bgColor").toArgb() ?: return null
        val accent = colours.optString("textColor1").toArgb() ?: background
        return AppleArtistArt(
            heroUrl = heroUrl,
            logoUrl = logoUrl,
            logoAspect = if (logoUrl != null) logoW.toFloat() / logoH else 1f,
            videoUrl = video,
            background = background,
            accent = accent,
            keyColor = header.optString("keyColor").toArgb(),
        )
    }

    private fun findHeader(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.has("artistLogo")) return node
                val keys = node.keys()
                while (keys.hasNext()) findHeader(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findHeader(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun String.toArgb(): Int? =
        takeIf { it.length == 6 }?.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }

    private fun String.fill(w: Int, h: Int, format: String): String? {
        if (isBlank() || !contains("{w}")) return null
        return replace("{w}", w.toString())
            .replace("{h}", h.toString())
            .replace("{c}", "bb")
            .replace("{f}", format)
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private fun String.normalized(): String = AppleCatalogue.matchKey(this)
}

fun DetailPage.appleCatalogue(): List<String> =
    AppleCatalogue.titlesOf((songs as? UiState.Success)?.data.orEmpty(), sections)
