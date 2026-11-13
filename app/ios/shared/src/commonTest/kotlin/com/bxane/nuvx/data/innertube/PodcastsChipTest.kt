package com.bxane.nuvx.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodcastsChipTest {
    private fun homeWithChips(chips: String) =
        Json.parseToJsonElement(
            """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"header":{"chipCloudRenderer":{"chips":[$chips]}}}}}}]}}}""",
        ) as JsonObject

    private fun chip(title: String, params: String) =
        """{"chipCloudChipRenderer":{"text":{"runs":[{"text":"$title"}]},"navigationEndpoint":{"browseEndpoint":{"params":"$params"}}}}"""

    @Test
    fun translatedPodcastsChipIsFoundByStableParams() {
        val home = homeWithChips(
            chip("Sleep", "ggNCSgQIDBABSgQIBRAD") + "," +
                chip("Подкасты", "ggNCSgQIDBADSgQIBxAB"),
        )

        assertEquals("ggNCSgQIDBADSgQIBxAB", InnertubeParser.podcastsChipParams(home))
    }

    @Test
    fun englishPodcastsChipIsFoundByTitle() {
        val home = homeWithChips(chip("Podcasts", "server-updated-params"))

        assertEquals("server-updated-params", InnertubeParser.podcastsChipParams(home))
    }

    @Test
    fun unrelatedChipsAreIgnored() {
        val home = homeWithChips(chip("Sleep", "ggNCSgQIDBABSgQIBRAD"))

        assertNull(InnertubeParser.podcastsChipParams(home))
    }
}
