package com.bxane.nuvx.data.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue









class YouTubeLyricsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }



    @Test
    fun `a real video id is accepted`() {
        assertTrue(isVideoId("dQw4w9WgXcQ"))
        assertTrue(isVideoId("aBc-123_xyz"))
    }

    @Test
    fun `anything else is refused before a request is spent`() {



        assertFalse(isVideoId(""))
        assertFalse(isVideoId("short"))
        assertFalse(isVideoId("waytoolongtobevalid"))
        assertFalse(isVideoId("has space!!"))
        assertFalse(isVideoId("src:addon1::track"))
        assertFalse(isVideoId("/Users/someone/track.m4a"))
    }



    @Test
    fun `a renderer is found at the top level`() {
        val page = json.parseToJsonElement(
            """{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"a"}]}}}"""
        )
        assertEquals(1, page.objectsNamedCount("musicDescriptionShelfRenderer"))
    }

    @Test
    fun `a renderer is found several levels down`() {




        val page = json.parseToJsonElement(
            nest(mapOf("musicDescriptionShelfRenderer" to mapOf("description" to text)))
        )
        assertEquals(1, page.objectsNamedCount("musicDescriptionShelfRenderer"))
    }

    @Test
    fun `a renderer inside an array is found`() {
        val page = json.parseToJsonElement(
            """{"items":[{"a":1},{"tabRenderer":{"x":1}}]}"""
        )
        assertEquals(1, page.objectsNamedCount("tabRenderer"))
    }

    @Test
    fun `a name that is not there is not found`() {
        val page = json.parseToJsonElement("""{"tabRenderer":{"x":1}}""")
        assertEquals(0, page.objectsNamedCount("transcriptCueRenderer"))
    }

    @Test
    fun `a walk does not loop on a deeply nested array`() {
        val page = json.parseToJsonElement("""{"a":[[[{"tabRenderer":{}}]]]}""")
        assertEquals(1, page.objectsNamedCount("tabRenderer"))
    }



    @Test
    fun `a plain text run is read`() {
        val element = json.parseToJsonElement("""{"runs":[{"text":"hello"}]}""")
        assertEquals(listOf("hello"), element.youtubeStrings())
    }

    @Test
    fun `a simpleText node is read`() {
        val element = json.parseToJsonElement("""{"simpleText":"Lyrics"}""")
        assertEquals(listOf("Lyrics"), element.youtubeStrings())
    }

    @Test
    fun `several runs join in order`() {
        val element = json.parseToJsonElement("""{"runs":[{"text":"one "},{"text":"two"}]}""")
        assertEquals(listOf("one ", "two"), element.youtubeStrings())
    }

    @Test
    fun `text is found however deep it is`() {
        val element = json.parseToJsonElement("""{"a":{"b":{"text":"deep"}}}""")
        assertEquals(listOf("deep"), element.youtubeStrings())
    }

    @Test
    fun `numbers in the subtree are not read as words`() {



        val element = json.parseToJsonElement("""{"durationMs":342000,"runs":[{"text":"a"}]}""")
        assertEquals(listOf("a"), element.youtubeStrings())
    }

    @Test
    fun `a node with no text reads as nothing rather than throwing`() {
        assertTrue(json.parseToJsonElement("""{"other":1}""").youtubeStrings().isEmpty())
    }
}


private fun kotlinx.serialization.json.JsonElement.objectsNamedCount(name: String): Int {
    val found = mutableListOf<JsonObject>()
    fun walk(element: kotlinx.serialization.json.JsonElement) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (key == name && value is JsonObject) found += value
                walk(value)
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return found.size
}


private fun nest(value: Any, depth: Int = 6): String {
    val names = listOf(
        "musicShelfRenderer", "contents", "musicShelfRenderer",
        "sectionList", "content", "tabRenderer",
    )
    var wrapped: Any = value
    for (name in names.take(depth)) wrapped = mapOf(name to wrapped)
    return "{\"contents\":{\"tabs\":[${toElement(wrapped)}]}}"
}

private fun toElement(value: Any): String = when (value) {
    is Map<*, *> -> value.entries.joinToString(",", prefix = "{", postfix = "}") { (key, entry) ->
        "\"${key}\":${toElement(entry ?: "null")}"
    }
    is List<*> -> value.joinToString(",", prefix = "[", postfix = "]") { toElement(it ?: "null") }
    is String -> "\"$value\""
    else -> "null"
}

private val text = mapOf("runs" to listOf(mapOf("text" to "a")))
