package com.bxane.nuvx

import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.AddonSource
import com.bxane.nuvx.data.sources.DeviceCodecs
import com.bxane.nuvx.data.sources.SourceConfig
import com.bxane.nuvx.data.sources.SourceHealth
import com.bxane.nuvx.data.sources.SourceKind
import com.bxane.nuvx.data.sources.SourceRegistry
import com.bxane.nuvx.data.sources.StreamRequest
import com.bxane.nuvx.data.sources.addon.AddonAuth
import com.bxane.nuvx.data.sources.addon.AddonClient
import com.bxane.nuvx.data.sources.addon.AddonException
import com.bxane.nuvx.data.sources.addon.AddonQualityOption
import com.bxane.nuvx.data.sources.addon.AddonUnavailable
import com.bxane.nuvx.playback.StreamContainer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AddonSourceTest {

    private lateinit var server: MockWebServer

    private val routes = mutableMapOf<String, MockResponse>()

    private val seen = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val path = request.requestUrl?.encodedPath.orEmpty()
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        DeviceCodecs.forced = true
        AppSettings.dolbyAtmos.value = true
        AddonAuth.testHeaders = { _, _ -> mapOf(AddonAuth.TOKEN_HEADER to "test-token", AddonAuth.PROOF_HEADER to "test-proof") }
    }

    @After
    fun tearDown() {
        server.shutdown()
        DeviceCodecs.forced = null
        AppSettings.dolbyAtmos.value = true
        AddonAuth.testHeaders = null
        AddonAuth.refuseForTest(null)
    }

    @Test
    fun `normalizeBase accepts every form of the address a user might paste`() {
        val expected = "https://addon.example.com"
        assertEquals(expected, AddonClient.normalizeBase("https://addon.example.com"))
        assertEquals(expected, AddonClient.normalizeBase("https://addon.example.com/"))
        assertEquals(expected, AddonClient.normalizeBase("  https://addon.example.com//  "))
        assertEquals(expected, AddonClient.normalizeBase("https://addon.example.com/manifest.json"))
        assertEquals(expected, AddonClient.normalizeBase("https://addon.example.com/MANIFEST.JSON"))
    }

    @Test
    fun `normalizeBase keeps a token-bearing path intact`() {
        assertEquals(
            "https://addon.example.com/abc123",
            AddonClient.normalizeBase("https://addon.example.com/abc123/manifest.json"),
        )
    }

    @Test
    fun `redact hides the token in the path and keeps the host`() {
        assertEquals(
            "https://addon.example.com/***",
            AddonClient.redact("https://addon.example.com/secret-token/stream/42"),
        )
        assertEquals("***", AddonClient.redact("not a url"))
    }

    @Test
    fun `a well-formed manifest reports healthy with its name and version`() = runBlocking {
        route("/manifest.json", manifest())
        val health = source().health()
        assertTrue("expected Ok, got $health", health is SourceHealth.Ok)
        assertEquals("Test Addon v2.1.0", (health as SourceHealth.Ok).detail)
    }

    @Test
    fun `an addon that cannot be searched is rejected, not merely unreachable`() = runBlocking {
        route("/manifest.json", json("""{"id":"x","name":"X","nuvxAuth":true,"resources":["stream"]}"""))
        val health = source().health()
        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
        assertTrue((health as SourceHealth.Rejected).reason.contains("needs search"))
    }

    @Test
    fun `an addon that declares search but not stream is accepted`() = runBlocking {
        route("/manifest.json", json("""{"id":"x","name":"X","nuvxAuth":true,"resources":["search"]}"""))
        assertTrue(source().health() is SourceHealth.Ok)
    }

    @Test
    fun `an addon with no manifest is healthy when its search endpoint answers`() = runBlocking {
        route("/search", json("""{"tracks":[{"id":"t1","title":"S","artist":"A"}]}"""))

        val health = source().health()

        assertTrue("expected Ok, got $health", health is SourceHealth.Ok)
    }

    private fun outdatedManifest() = json(
        """{"id":"com.test.old","name":"Old Addon","version":"1.0.0","resources":["search","stream"]}""",
    )

    @Test
    fun `a manifest without nuvxAuth is still healthy`() = runBlocking {
        route("/manifest.json", outdatedManifest())

        val health = source().health()

        assertTrue("expected Ok, got $health", health is SourceHealth.Ok)
    }

    @Test
    fun `an out-of-date addon is still searched and streamed without credentials`() = runBlocking {
        route("/manifest.json", outdatedManifest())
        route("/search", json("""{"tracks":[{"id":"t1","title":"S","artist":"A"}]}"""))
        route("/stream/t1", json("""{"url":"https://cdn/a.flac"}"""))
        AddonAuth.refuseForTest("nuvx couldn't verify this device: The bootloader is unlocked")
        val source = source()

        assertEquals(1, source.search("S", 10, false, null).size)
        assertNotNull(source.stream("t1", StreamRequest.Lossless))
        val sent = synchronized(seen) { seen.toList() }
        assertTrue(sent.none { it.getHeader(AddonAuth.TOKEN_HEADER) != null })
    }

    @Test
    fun `nuvxAuth false is out of date but still searched`() = runBlocking {
        route(
            "/manifest.json",
            json("""{"id":"x","name":"X","nuvxAuth":false,"resources":["search","stream"]}"""),
        )
        route("/search", json("""{"tracks":[{"id":"t1","title":"S","artist":"A"}]}"""))

        assertEquals(1, source().search("S", 10, false, null).size)
    }

    @Test
    fun `a device the auth service refuses is reported in its words`() = runBlocking {
        route("/manifest.json", manifest())
        AddonAuth.testHeaders = { _, _ ->
            throw AddonException("nuvx couldn't verify this device: The bootloader is unlocked")
        }

        val health = source().health()

        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
        assertEquals(
            "nuvx couldn't verify this device: The bootloader is unlocked",
            (health as SourceHealth.Rejected).reason,
        )
    }

    @Test
    fun `an auth service that can't be reached reads as unreachable`() = runBlocking {
        route("/manifest.json", manifest())
        AddonAuth.testHeaders = { _, _ -> throw AddonUnavailable("Couldn't reach nuvx's addon verification service") }

        assertTrue(source().health() is SourceHealth.Unreachable)
    }

    @Test
    fun `while the device is refused an addon is not asked to search or stream`() = runBlocking {
        route("/manifest.json", manifest())
        route("/search", json("""{"tracks":[{"id":"t1","title":"S","artist":"A"}]}"""))
        route("/stream/t1", json("""{"url":"https://cdn/a.flac"}"""))
        AddonAuth.refuseForTest("nuvx couldn't verify this device: revoked")
        val source = source()

        assertTrue(source.search("S", 10, false, null).isEmpty())
        assertTrue(source.search("S A", 10, false, null).isEmpty())
        assertNull(source.stream("t1", StreamRequest.Lossless))
        val asked = synchronized(seen) { seen.map { it.requestUrl?.encodedPath } }
        assertEquals(listOf("/manifest.json"), asked.distinct())

        AddonAuth.refuseForTest(null)
        assertEquals(1, source.search("S", 10, false, null).size)
    }

    @Test
    fun `an addon is still searched while its manifest is down`() = runBlocking {
        route("/manifest.json", MockResponse().setResponseCode(503))
        route("/search", json("""{"tracks":[{"id":"t1","title":"S","artist":"A"}]}"""))

        assertEquals(1, source().search("S", 10, false, null).size)
    }

    @Test
    fun `identify records whether the manifest declares nuvxAuth`() = runBlocking {
        route("/manifest.json", manifest())
        assertEquals(true, SourceRegistry.identify(config().baseUrl).getOrThrow().nuvxAuth)

        route("/manifest.json", outdatedManifest())
        assertEquals(false, SourceRegistry.identify(config().baseUrl).getOrThrow().nuvxAuth)
    }

    @Test
    fun `only an addon known to lack nuvxAuth counts as needing an update`() {
        assertTrue(SourceConfig(kind = SourceKind.ADDON, nuvxAuth = false).needsAddonUpdate)
        assertFalse(SourceConfig(kind = SourceKind.ADDON, nuvxAuth = true).needsAddonUpdate)
        assertFalse(SourceConfig(kind = SourceKind.ADDON, nuvxAuth = null).needsAddonUpdate)
        assertFalse(SourceConfig(kind = SourceKind.CUSTOM_MODULE, nuvxAuth = false).needsAddonUpdate)
    }

    @Test
    fun `a URL that answers with something other than a manifest is rejected`() = runBlocking {
        route("/manifest.json", json("""{"hello":"world"}"""))
        assertTrue(source().health() is SourceHealth.Rejected)
    }

    @Test
    fun `no manifest at the URL is rejected rather than reported as a miss`() = runBlocking {
        val health = source().health()
        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
    }

    @Test
    fun `a server error reads as unreachable`() = runBlocking {
        route("/manifest.json", MockResponse().setResponseCode(503))
        assertTrue(source().health() is SourceHealth.Unreachable)
    }

    @Test
    fun `search maps rows to songs tagged with this source`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json(
                """
                {"tracks":[
                  {"id":"t1","title":"Song One","artist":"Artist","album":"Album",
                   "duration":245,"artworkURL":"https://cdn/x.jpg","format":"flac"},
                  {"id":"t2","title":"Song Two","artist":"Artist","duration":61,"format":"mp3"}
                ]}
                """.trimIndent(),
            ),
        )

        val config = config()
        val songs = AddonSource(config).search("song", limit = 10)

        assertEquals(2, songs.size)
        assertEquals("Song One", songs[0].title)
        assertEquals("Album", songs[0].albumName)
        assertEquals("https://cdn/x.jpg", songs[0].thumbnailUrl)
        assertEquals("4:05", songs[0].durationText)
        assertEquals("LOSSLESS", songs[0].sourceQuality)
        assertEquals("1:01", songs[1].durationText)
        assertEquals("HIGH", songs[1].sourceQuality)
        assertTrue(songs[0].videoId.startsWith("src:${config.id}::"))
        assertTrue(songs[0].videoId.endsWith("::t1"))
    }

    @Test
    fun `search drops rows with no id or no title`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json("""{"tracks":[{"id":"","title":"No id"},{"id":"t","title":""},{"id":"ok","title":"Fine"}]}"""),
        )
        val songs = AddonSource(config()).search("x", limit = 10)
        assertEquals(1, songs.size)
        assertEquals("Fine", songs[0].title)
    }

    @Test
    fun `search honours the caller's limit`() = runBlocking {
        route("/manifest.json", manifest())
        val rows = (1..20).joinToString(",") { """{"id":"t$it","title":"Song $it","artist":"A"}""" }
        route("/search", json("""{"tracks":[$rows]}"""))
        assertEquals(3, AddonSource(config()).search("x", limit = 3).size)
    }

    @Test
    fun `an unreachable addon returns no results rather than throwing`() = runBlocking {
        route("/manifest.json", manifest())
        assertTrue(AddonSource(config()).search("x", limit = 5).isEmpty())
    }

    @Test
    fun `manual refresh asks again after a cached empty search`() = runBlocking {
        route("/manifest.json", manifest())
        route("/search", json("""{"tracks":[]}"""))
        val source = AddonSource(config())

        assertTrue(source.search("song", limit = 5).isEmpty())
        route(
            "/search",
            json("""{"tracks":[{"id":"recovered","title":"Song","artist":"Artist"}]}"""),
        )
        assertTrue(source.search("song", limit = 5).isEmpty())

        source.clearCompletedTrackCalls()
        val refreshed = source.search("song", limit = 5)

        assertEquals(listOf("Song"), refreshed.map { it.title })
        assertEquals(2, synchronized(seen) { seen.count { it.requestUrl?.encodedPath == "/search" } })
    }

    @Test
    fun `manual refresh asks again for a cached stream URL`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/track", json("""{"url":"https://cdn/old.flac","codec":"flac"}"""))
        val source = AddonSource(config())

        assertEquals("https://cdn/old.flac", source.stream("track", StreamRequest.Lossless)?.url)
        route("/stream/track", json("""{"url":"https://cdn/new.flac","codec":"flac"}"""))
        assertEquals("https://cdn/old.flac", source.stream("track", StreamRequest.Lossless)?.url)

        source.clearCompletedTrackCalls()
        val refreshed = source.stream("track", StreamRequest.Lossless)

        assertEquals("https://cdn/new.flac", refreshed?.url)
        assertEquals(2, synchronized(seen) { seen.count { it.requestUrl?.encodedPath == "/stream/track" } })
    }

    @Test
    fun `declared setting defaults travel with the request and quality overrides them`() = runBlocking {
        route(
            "/manifest.json",
            json(
                """
                {"id":"a","name":"A","nuvxAuth":true,"resources":["search","stream","settings"],
                 "settings":[
                   {"key":"quality","type":"select","default":"normal",
                    "options":[{"value":"lossless"},{"value":"high"},{"value":"normal"}]},
                   {"key":"region","type":"text","default":"US"},
                   {"key":"preferOpus","type":"toggle","default":true}
                 ]}
                """.trimIndent(),
            ),
        )
        route("/search", json("""{"tracks":[]}"""))

        AddonSource(config()).search("hello", limit = 5)

        val url = requestFor("/search")
        assertEquals("hello", url.queryParameter("q"))
        assertEquals("US", url.queryParameter("region"))
        assertEquals("true", url.queryParameter("preferOpus"))
        assertEquals("lossless", url.queryParameter("quality"))
    }

    @Test
    fun `an addon that enumerates no options is sent the tier verbatim`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.flac"}"""))

        AddonSource(config()).stream("t1", StreamRequest.Lossless)

        assertEquals("LOSSLESS", requestFor("/stream/t1").queryParameter("quality"))
    }

    private fun qualityManifest() = json(
        """
        {"id":"a","name":"A","nuvxAuth":true,"resources":["search","stream"],
         "settings":[{"key":"quality","type":"select","default":"max","perNetwork":true,
           "options":[{"value":"max","label":"MAX"},{"value":"hires","label":"HiRes"},
                      {"value":"lossless","label":"CD"},{"value":"low","label":"Low"}]}]}
        """.trimIndent(),
    )

    @Test
    fun `a picked quality replaces the negotiated one on search and stream`() = runBlocking {
        route("/manifest.json", qualityManifest())
        route("/search", json("""{"tracks":[]}"""))
        route("/stream/t1", json("""{"url":"https://cdn/a.flac"}"""))

        val source = AddonSource(config().copy(pickedQuality = "hires"))
        source.search("hello", limit = 5)
        source.stream("t1", StreamRequest.Lossless)

        assertEquals("hires", requestFor("/search").queryParameter("quality"))
        assertEquals("hires", requestFor("/stream/t1").queryParameter("quality"))
    }

    @Test
    fun `with nothing picked the first listed option is sent`() = runBlocking {
        route("/manifest.json", qualityManifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.flac"}"""))

        AddonSource(config()).stream("t1", StreamRequest.Lossless)

        assertEquals("max", requestFor("/stream/t1").queryParameter("quality"))
    }

    @Test
    fun `a picked quality does not raise a request below the top tier`() = runBlocking {
        route("/manifest.json", qualityManifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.mp3"}"""))

        AddonSource(config().copy(pickedQuality = "hires")).stream("t1", StreamRequest.Capped(128))

        assertEquals("low", requestFor("/stream/t1").queryParameter("quality"))
    }

    @Test
    fun `a pick the addon no longer lists falls back to the first listed option`() = runBlocking {
        route("/manifest.json", qualityManifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.flac"}"""))

        AddonSource(config().copy(pickedQuality = "gone")).stream("t1", StreamRequest.Lossless)

        assertEquals("max", requestFor("/stream/t1").queryParameter("quality"))
    }

    @Test
    fun `the active quality is the pick while it is listed and the first option otherwise`() {
        val options = listOf(AddonQualityOption("max", "MAX"), AddonQualityOption("hires", "HiRes"))
        val listed = config().copy(qualityOptions = options)

        assertEquals("max", listed.activeQuality?.value)
        assertEquals("hires", listed.copy(pickedQuality = "hires").activeQuality?.value)
        assertEquals("max", listed.copy(pickedQuality = "gone").activeQuality?.value)
        assertNull(config().activeQuality)
    }

    @Test
    fun `a pick is dropped once the addon declares no quality options at all`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.flac"}"""))

        AddonSource(config().copy(pickedQuality = "hires")).stream("t1", StreamRequest.Lossless)

        assertEquals("LOSSLESS", requestFor("/stream/t1").queryParameter("quality"))
    }

    @Test
    fun `re-identifying an addon keeps a pick it still lists and drops one it does not`() = runBlocking {
        route("/manifest.json", qualityManifest())
        val base = config()

        val kept = SourceRegistry.identify(base.baseUrl, base.copy(pickedQuality = "hires")).getOrThrow()
        val dropped = SourceRegistry.identify(base.baseUrl, base.copy(pickedQuality = "gone")).getOrThrow()

        assertEquals("hires", kept.pickedQuality)
        assertNull(dropped.pickedQuality)
    }

    @Test
    fun `a pick still goes out while the manifest is unreachable`() = runBlocking {
        route("/manifest.json", MockResponse().setResponseCode(503))
        route("/stream/t1", json("""{"url":"https://cdn/a.flac"}"""))

        AddonSource(config().copy(pickedQuality = "hires", nuvxAuth = true)).stream("t1", StreamRequest.Lossless)

        assertEquals("hires", requestFor("/stream/t1").queryParameter("quality"))
    }

    @Test
    fun `a capped request asks for the addon's small tier`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.mp3"}"""))

        AddonSource(config()).stream("t1", StreamRequest.Capped(maxKbps = 64))

        assertEquals("LOW", requestFor("/stream/t1").queryParameter("quality"))
    }

    @Test
    fun `a search behind a capped request asks the addon at that tier`() = runBlocking {
        route("/manifest.json", manifest())
        route("/search", json("""{"tracks":[]}"""))

        AddonSource(config()).search("x", limit = 5, request = StreamRequest.Capped(maxKbps = 64))

        assertEquals("LOW", requestFor("/search").queryParameter("quality"))
    }

    @Test
    fun `a search with no request behind it still asks for lossless`() = runBlocking {
        route("/manifest.json", manifest())
        route("/search", json("""{"tracks":[]}"""))

        AddonSource(config()).search("x", limit = 5)

        assertEquals("LOSSLESS", requestFor("/search").queryParameter("quality"))
    }

    @Test
    fun `the atmos hint travels on search and stream when the device and the user both want it`() =
        runBlocking {
            route("/manifest.json", manifest())
            route("/search", json("""{"tracks":[]}"""))
            route("/stream/t1", json("""{"url":"https://cdn/a.flac","codec":"flac"}"""))

            val source = AddonSource(config())
            source.search("x", limit = 5)
            source.stream("t1", StreamRequest.Lossless)

            assertEquals("auto", requestFor("/search").queryParameter("atmos"))
            assertEquals("auto", requestFor("/stream/t1").queryParameter("atmos"))
        }

    @Test
    fun `no atmos hint is sent when the setting is off`() = runBlocking {
        AppSettings.dolbyAtmos.value = false
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.flac","codec":"flac"}"""))

        AddonSource(config()).stream("t1", StreamRequest.Lossless)

        assertNull(requestFor("/stream/t1").queryParameter("atmos"))
    }

    @Test
    fun `no atmos hint is sent when the device cannot decode it`() = runBlocking {
        DeviceCodecs.forced = false
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.flac","codec":"flac"}"""))

        AddonSource(config()).stream("t1", StreamRequest.Lossless)

        assertNull(requestFor("/stream/t1").queryParameter("atmos"))
    }

    @Test
    fun `a declared atmos default is not overridden by the hint`() = runBlocking {
        route(
            "/manifest.json",
            json(
                """
                {"id":"a","name":"A","nuvxAuth":true,"resources":["search","stream","settings"],
                 "settings":[{"key":"atmos","type":"select","default":"only",
                              "options":[{"value":"only"},{"value":"auto"},{"value":"off"}]}]}
                """.trimIndent(),
            ),
        )
        route("/stream/t1", json("""{"url":"https://cdn/a.flac","codec":"flac"}"""))

        AddonSource(config()).stream("t1", StreamRequest.Lossless)

        assertEquals("only", requestFor("/stream/t1").queryParameter("atmos"))
    }

    @Test
    fun `asking for atmos gets an immersive answer the player is told how to open`() = runBlocking {
        val url = "https://cdn.example.com/dash/tidal-atmos"
        route("/manifest.json", manifest())
        server.dispatcher = atmosDispatcher(url)

        val stream = AddonSource(config()).stream("t1", StreamRequest.Lossless)!!

        assertEquals(url, stream.url)
        assertEquals("eac3-joc", stream.format.codec)
        assertTrue(stream.format.isDolbyAtmos)
        assertEquals("Dolby Atmos", stream.format.summary)
        assertEquals(false, stream.format.isLossless)
        assertEquals("application/dash+xml", StreamContainer.manifestMimeOf(url))
    }

    @Test
    fun `without the hint the same addon answers with the stereo ladder`() = runBlocking {
        AppSettings.dolbyAtmos.value = false
        route("/manifest.json", manifest())
        server.dispatcher = atmosDispatcher("https://cdn.example.com/dash/tidal-atmos")

        val stream = AddonSource(config()).stream("t1", StreamRequest.Lossless)!!

        assertEquals("flac", stream.format.codec)
        assertEquals(true, stream.format.isLossless)
        assertFalse(stream.format.isDolbyAtmos)
    }

    @Test
    fun `a Tidal immersive row is tagged as Atmos and not as its LOW bitrate label`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json(
                """
                {"tracks":[
                  {"id":"tidal:479222720","title":"Gehra Hua","artist":"Shashwat Sachdev",
                   "album":"Dhurandhar","duration":362,"format":"flac",
                   "audioQuality":"LOSSLESS","audioModes":["STEREO"],"provider":"Tidal"},
                  {"id":"tidal:527739156","title":"Gehra Hua (From \"Dhurandhar\")",
                   "artist":"Shashwat Sachdev","album":"Dhurandhar","duration":362,"format":"dash",
                   "audioQuality":"LOW","audioModes":["DOLBY_ATMOS"],"atmos":true,"provider":"Tidal"}
                ]}
                """.trimIndent(),
            ),
        )

        val songs = AddonSource(config()).search("gehra hua", limit = 10)

        assertEquals(2, songs.size)
        assertEquals("LOSSLESS", songs[0].sourceQuality)
        assertEquals("DOLBY", songs[1].sourceQuality)
    }

    @Test
    fun `an immersive row is recognised from audioModes with no atmos flag`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json(
                """{"tracks":[{"id":"t1","title":"S","artist":"A","duration":200,
                   "audioQuality":"LOW","audioModes":["DOLBY_ATMOS"]}]}""",
            ),
        )

        assertEquals("DOLBY", AddonSource(config()).search("s", limit = 5)[0].sourceQuality)
    }

    @Test
    fun `a stereo row keeps the tier its label states`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json(
                """{"tracks":[{"id":"t1","title":"S","artist":"A","duration":200,
                   "audioQuality":"LOW","audioModes":["STEREO"],"format":"mp3"}]}""",
            ),
        )

        assertEquals("LOW", AddonSource(config()).search("s", limit = 5)[0].sourceQuality)
    }

    @Test
    fun `a hi-res row stays lossless but is flagged hi-res for its badge`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json(
                """{"tracks":[
                  {"id":"t1","title":"S","artist":"A","duration":247,"audioQuality":"HI_RES_LOSSLESS","format":"dash"},
                  {"id":"t2","title":"S","artist":"A","duration":247,"audioQuality":"LOSSLESS","format":"flac"},
                  {"id":"t3","title":"S","artist":"A","duration":247,"audioQuality":"HI_RES_LOSSLESS","audioModes":["DOLBY_ATMOS"]}
                ]}""",
            ),
        )

        val songs = AddonSource(config()).search("s", limit = 5)

        assertEquals("LOSSLESS", songs[0].sourceQuality)
        assertTrue(songs[0].sourceHiRes)
        assertEquals("LOSSLESS", songs[1].sourceQuality)
        assertFalse(songs[1].sourceHiRes)
        assertEquals("DOLBY", songs[2].sourceQuality)
        assertFalse(songs[2].sourceHiRes)
    }

    @Test
    fun `a picked stereo row is asked for without the atmos hint and plays stereo`() = runBlocking {
        server.dispatcher = atmosDispatcher("https://cdn.example.com/dash/tidal-atmos")
        route("/manifest.json", manifest())

        val picked = AddonSource(config()).stream("t1", StreamRequest.Lossless, stereoOnly = true)!!

        assertNull(requestFor("/stream/t1").queryParameter("atmos"))
        assertFalse(picked.format.isDolbyAtmos)
        assertEquals("flac", picked.format.codec)
    }

    @Test
    fun `an unpicked stream still carries the atmos hint`() = runBlocking {
        server.dispatcher = atmosDispatcher("https://cdn.example.com/dash/tidal-atmos")
        route("/manifest.json", manifest())

        val stream = AddonSource(config()).stream("t1", StreamRequest.Lossless)!!

        assertEquals("auto", requestFor("/stream/t1").queryParameter("atmos"))
        assertTrue(stream.format.isDolbyAtmos)
    }

    @Test
    fun `a picked stereo row sends the declared atmos setting's off value`() = runBlocking {
        route(
            "/manifest.json",
            json(
                """
                {"id":"a","name":"A","nuvxAuth":true,"resources":["search","stream","settings"],
                 "settings":[{"key":"atmos","type":"select","default":"auto",
                              "options":[{"value":"only"},{"value":"auto"},{"value":"off"}]}]}
                """.trimIndent(),
            ),
        )
        route("/stream/t1", json("""{"url":"https://cdn/a.flac","codec":"flac"}"""))

        AddonSource(config()).stream("t1", StreamRequest.Lossless, stereoOnly = true)

        assertEquals("off", requestFor("/stream/t1").queryParameter("atmos"))
    }

    private fun atmosDispatcher(atmosUrl: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            synchronized(seen) { seen += request }
            val url = request.requestUrl
            val path = url?.encodedPath.orEmpty()
            if (!path.startsWith("/stream/")) return routes[path] ?: MockResponse().setResponseCode(404)
            return if (url?.queryParameter("atmos") == "auto") {
                json(
                    """
                    {"url":"$atmosUrl","format":"dash","quality":"Tidal · Dolby Atmos",
                     "streamQuality":"[Tidal] DOLBY_ATMOS","codec":"eac3_joc","container":"mp4",
                     "manifest":"dash","sampleRate":48000,"atmos":true,"encrypted":false}
                    """.trimIndent(),
                )
            } else {
                json("""{"url":"https://cdn.example.com/a.flac","codec":"flac","container":"flac"}""")
            }
        }
    }

    @Test
    fun `a track id containing a slash stays one path segment`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/a%2Fb%3Fc", json("""{"url":"https://cdn/a.flac"}"""))

        val stream = AddonSource(config()).stream("a/b?c", StreamRequest.Lossless)

        assertNotNull("the id was not encoded into a single segment", stream)
    }

    @Test
    fun `the routing fields become the format the player is told about`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/stream/t1",
            json(
                """
                {"url":"https://cdn.example.com/a.flac","format":"flac","quality":"lossless",
                 "codec":"flac","container":"flac","manifest":"none","encrypted":false,
                 "sampleRate":96000,"bitDepth":24}
                """.trimIndent(),
            ),
        )

        val stream = AddonSource(config()).stream("t1", StreamRequest.Lossless)!!

        assertEquals("https://cdn.example.com/a.flac", stream.url)
        assertEquals("flac", stream.format.codec)
        assertEquals(96000, stream.format.sampleRateHz)
        assertEquals(24, stream.format.bitDepth)
        assertEquals(true, stream.format.isLossless)
    }

    @Test
    fun `an extensionless HLS playlist is declared to the player rather than sniffed`() = runBlocking {
        val url = "https://cdn.example.com/dash/t1"
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"$url","format":"flac","codec":"flac","manifest":"hls"}"""))

        assertNull(StreamContainer.manifestMimeOf("https://cdn.example.com/dash/never-declared"))

        AddonSource(config()).stream("t1", StreamRequest.Lossless)

        assertEquals("application/x-mpegURL", StreamContainer.manifestMimeOf(url))
    }

    @Test
    fun `a declared dash transport is passed on too`() = runBlocking {
        val url = "https://cdn.example.com/play/t2"
        route("/manifest.json", manifest())
        route("/stream/t2", json("""{"url":"$url","manifest":"dash"}"""))

        AddonSource(config()).stream("t2", StreamRequest.Lossless)

        assertEquals("application/dash+xml", StreamContainer.manifestMimeOf(url))
    }

    @Test
    fun `an encrypted rendition is refused`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/stream/t1",
            json("""{"url":"https://cdn/a.mpd","encrypted":"widevine","manifest":"dash"}"""),
        )
        assertNull(AddonSource(config()).stream("t1", StreamRequest.Lossless))
    }

    @Test
    fun `encrypted false is not treated as encrypted`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.flac","encrypted":false}"""))
        assertNotNull(AddonSource(config()).stream("t1", StreamRequest.Lossless))
    }

    @Test
    fun `a malformed stream URL is refused before the player sees it`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"/relative/path.flac"}"""))
        assertNull(AddonSource(config()).stream("t1", StreamRequest.Lossless))
    }

    @Test
    fun `a stream 404 is a miss, not a crash`() = runBlocking {
        route("/manifest.json", manifest())
        assertNull(AddonSource(config()).stream("nope", StreamRequest.Lossless))
    }

    @Test
    fun `the search row's own URL is used when the stream endpoint has nothing`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json(
                """{"tracks":[{"id":"t1","title":"S","artist":"A","duration":180,
                   "streamURL":"https://cdn.example.com/direct.mp3","format":"mp3"}]}""",
            ),
        )

        val source = AddonSource(config())
        source.search("s", limit = 5)
        val stream = source.stream("t1", StreamRequest.Best)!!

        assertEquals("https://cdn.example.com/direct.mp3", stream.url)
        assertEquals("mp3", stream.format.codec)
        assertEquals(180, stream.durationSec)
    }

    @Test
    fun `with no row remembered and no stream answer there is nothing to play`() = runBlocking {
        route("/manifest.json", manifest())
        assertNull(AddonSource(config()).stream("unknown", StreamRequest.Best))
    }

    @Test
    fun `a searched row's duration reaches the stream it produces`() = runBlocking {
        route("/manifest.json", manifest())
        route("/search", json("""{"tracks":[{"id":"t1","title":"S","artist":"A","duration":242}]}"""))
        route("/stream/t1", json("""{"url":"https://cdn/a.flac","codec":"flac"}"""))

        val source = AddonSource(config())
        source.search("s", limit = 5)

        assertEquals(242, source.stream("t1", StreamRequest.Lossless)!!.durationSec)
    }

    @Test
    fun `a rate-limited request is waited out and retried`() = runBlocking {
        route("/manifest.json", manifest())
        val answers = ArrayDeque(
            listOf(
                MockResponse().setResponseCode(429).setHeader("Retry-After", "0"),
                json("""{"url":"https://cdn/a.flac","codec":"flac"}"""),
            ),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val path = request.requestUrl?.encodedPath.orEmpty()
                if (path == "/stream/t1") return answers.removeFirst()
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }

        val stream = AddonSource(config()).stream("t1", StreamRequest.Lossless)

        assertNotNull("a 429 should be retried, not reported as a miss", stream)
        assertEquals(0, answers.size)
    }

    @Test
    fun `a bare answer still yields what can be inferred from the URL`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn.example.com/track.128.mp3"}"""))

        val format = AddonSource(config()).stream("t1", StreamRequest.Best)!!.format

        assertEquals("mp3", format.codec)
        assertEquals(false, format.isLossless)
    }

    @Test
    fun `a bitrate in bits per second is read as kbps`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.mp3","codec":"mp3","bitrate":320000}"""))
        assertEquals(320, AddonSource(config()).stream("t1", StreamRequest.Best)!!.format.kbps)
    }

    @Test
    fun `a bitrate already in kbps is left alone`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.flac","codec":"flac","bitrate":1411}"""))
        assertEquals(1411, AddonSource(config()).stream("t1", StreamRequest.Lossless)!!.format.kbps)
    }

    @Test
    fun `an Atmos rendition is refused on a device that cannot decode it`() = runBlocking {
        DeviceCodecs.forced = false
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.mp4","codec":"eac3_joc","quality":"Dolby Atmos"}"""))

        assertNull(AddonSource(config()).stream("t1", StreamRequest.Lossless))
    }

    @Test
    fun `an Atmos rendition plays on a device that can decode it`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"https://cdn/a.mp4","codec":"eac3-joc","quality":"Dolby Atmos"}"""))

        val stream = AddonSource(config()).stream("t1", StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.format.isDolbyAtmos)
    }

    @Test
    fun `a real Tidal Hi-Res answer routes to DASH on the strength of its format field`() = runBlocking {
        val url = "https://im-fa.manifest.tidal.com/1/manifests/Egk0MTc1OTQ2NzEYAigBMAJY3onUY2CHaGoIUExBWUJBQ0s"
        route("/manifest.json", manifest())
        route(
            "/stream/t1",
            json(
                """
                {"url":"$url","format":"dash","quality":"Tidal · Hi-Res FLAC (DASH)",
                 "streamQuality":"[Tidal] HI_RES_LOSSLESS","provider":"Tidal","expiresAt":1788633325}
                """.trimIndent(),
            ),
        )

        val stream = AddonSource(config()).stream("t1", StreamRequest.Lossless)!!

        assertEquals(url, stream.url)
        assertEquals("application/dash+xml", StreamContainer.manifestMimeOf(url))
        assertEquals("flac", stream.format.codec)
        assertEquals(true, stream.format.isLossless)
    }

    @Test
    fun `a container in the format field is not mistaken for a transport`() = runBlocking {
        val url = "https://aac.saavncdn.com/601/b81082b74fa06e4596b5b111b0115d1a_320.mp4"
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"url":"$url","format":"mp4","quality":"320kbps","provider":"JioSaavn"}"""))

        val stream = AddonSource(config()).stream("t1", StreamRequest.Best)!!

        assertNull("mp4 is a container, not a manifest", StreamContainer.manifestMimeOf(url))
        assertEquals(320, stream.format.kbps)
        assertEquals("mp4", stream.format.codec)
        assertEquals(false, stream.format.isLossless)
    }

    @Test
    fun `a 200 carrying an error and no url is a miss`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/t1", json("""{"error":"Deezer ARL required — set it in the setup page"}"""))
        assertNull(AddonSource(config()).stream("t1", StreamRequest.Lossless))
    }

    @Test
    fun `a gateway error on an unknown id is a miss rather than a crash`() = runBlocking {
        route("/manifest.json", manifest())
        route("/stream/nope", MockResponse().setResponseCode(502))
        assertNull(AddonSource(config()).stream("nope", StreamRequest.Lossless))
    }

    @Test
    fun `real search rows survive their provider-prefixed ids`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json(
                """
                {"tracks":[{"id":"tidal:417594671","sourceId":"417594671","title":"Daylight",
                  "artist":"David Kushner","album":"Daylight","albumId":"417594670",
                  "albumArtworkURL":"https://resources.tidal.com/images/x/1080x1080.jpg",
                  "trackNumber":1,"duration":212,"format":"flac","isrc":"QZXDB2300005",
                  "audioQuality":"LOSSLESS","provider":"Tidal","audioModes":["STEREO"]}],
                 "albums":[],"artists":[],"playlists":[]}
                """.trimIndent(),
            ),
        )

        val songs = AddonSource(config()).search("daylight", limit = 5)

        assertEquals(1, songs.size)
        assertEquals("LOSSLESS", songs[0].sourceQuality)
        assertEquals("3:32", songs[0].durationText)
        assertTrue(songs[0].videoId.endsWith("::tidal:417594671"))
        assertEquals("https://resources.tidal.com/images/x/1080x1080.jpg", songs[0].thumbnailUrl)
    }

    @Test
    fun `bit depth and sample rate are read out of a free-text quality label`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/stream/t1",
            json("""{"url":"https://cdn/a","format":"dash","quality":"FLAC 24-bit / 96 kHz"}"""),
        )

        val format = AddonSource(config()).stream("t1", StreamRequest.Lossless)!!.format

        assertEquals(24, format.bitDepth)
        assertEquals(96000, format.sampleRateHz)
    }

    @Test
    fun `a URL nobody declared is still judged on its extension`() {
        assertEquals(
            "application/x-mpegURL",
            StreamContainer.manifestMimeOf("https://cdn.example.com/audio/playlist.m3u8"),
        )
        assertEquals(
            "application/dash+xml",
            StreamContainer.manifestMimeOf("https://cdn.example.com/audio/manifest.mpd?token=abc"),
        )
        assertNull(StreamContainer.manifestMimeOf("https://cdn.example.com/audio/track.flac"))
        assertNull(StreamContainer.manifestMimeOf("https://cdn.example.com/audio/track"))
    }

    private fun route(path: String, response: MockResponse) {
        routes[path] = response
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun manifest() = json(
        """{"id":"com.test.addon","name":"Test Addon","version":"2.1.0","nuvxAuth":true,"resources":["search","stream"]}""",
    )

    private fun config() = SourceConfig(
        kind = SourceKind.ADDON,
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun source() = AddonSource(config())

    private fun requestFor(path: String) = synchronized(seen) {
        seen.first { it.requestUrl?.encodedPath == path }.requestUrl!!
    }

    @Test
    fun `an addon URL is stored normalised so two spellings are one source`() {
        assertEquals(
            AddonClient.normalizeBase("https://a.example.com/manifest.json"),
            AddonClient.normalizeBase("https://a.example.com/"),
        )
        assertFalse(AddonClient.normalizeBase("https://a.example.com/").endsWith("/"))
    }
}
