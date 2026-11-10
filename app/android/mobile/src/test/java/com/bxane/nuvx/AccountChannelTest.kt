package com.bxane.nuvx

import com.bxane.nuvx.data.innertube.InnertubeParser
import com.bxane.nuvx.auth.normalizeDataSyncId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountChannelTest {

    private fun parse(json: String) =
        InnertubeParser.parseAccountChannels(Json.parseToJsonElement(json))

    @Test
    fun `personal channel and brand channel are both read`() {
        val channels = parse(SWITCHER)

        assertEquals(2, channels.size)

        val personal = channels[0]
        assertEquals("Ada Lovelace", personal.name)
        assertEquals("@ada", personal.subtitle)
        assertNull(personal.pageId)
        assertEquals("SESSION_A", personal.dataSyncId)
        assertTrue(personal.activeOnWeb)

        val brand = channels[1]
        assertEquals("Analytical Engine Radio", brand.name)
        assertEquals("113355", brand.pageId)
        assertEquals("SESSION_B", brand.dataSyncId)
        assertEquals(false, brand.activeOnWeb)
    }

    @Test
    fun `token order is not assumed`() {
        val channels = parse(SHUFFLED_TOKENS)
        assertEquals(1, channels.size)
        assertEquals("113355", channels[0].pageId)
        assertEquals("SESSION_B", channels[0].dataSyncId)
    }

    @Test
    fun `delegated half of the datasync id is used`() {
        assertEquals("SESSION_B", parse(SHUFFLED_TOKENS)[0].dataSyncId)
    }

    @Test
    fun `plain account falls back when delegated half is empty`() {
        assertEquals("SYNC_PERSONAL", normalizeDataSyncId("SYNC_PERSONAL||"))
        assertNull(normalizeDataSyncId(""))
    }

    @Test
    fun `an entry with nothing to send is dropped`() {
        assertTrue(parse(NO_TOKENS).isEmpty())
    }

    @Test
    fun `an unrecognised envelope yields nothing rather than throwing`() {
        assertTrue(parse("""{"responseContext":{},"contents":{}}""").isEmpty())
    }

    private companion object {
        const val SWITCHER = """
        {"data":{"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{
          "sections":[{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{
            "contents":[
              {"accountItem":{
                "accountName":{"simpleText":"Ada Lovelace"},
                "accountPhoto":{"thumbnails":[{"url":"https://x/s40","width":40}]},
                "isSelected":true,
                "channelHandle":{"simpleText":"@ada"},
                "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                  {"accountSigninToken":{"signinUrl":"https://accounts.google.com/x"}},
                  {"datasyncIdToken":{"datasyncIdToken":"SYNC_PERSONAL||SESSION_A"}}
                ]}}
              }},
              {"accountItem":{
                "accountName":{"runs":[{"text":"Analytical Engine Radio"}]},
                "accountPhoto":{"thumbnails":[{"url":"https://y/s40","width":40}]},
                "isSelected":false,
                "accountByline":{"simpleText":"Brand account"},
                "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                  {"pageIdToken":{"pageId":"113355"}},
                  {"datasyncIdToken":{"datasyncIdToken":"SYNC_BRAND||SESSION_B"}}
                ]}}
              }}
            ]}}]}}]}}}}]}}
        """

        const val SHUFFLED_TOKENS = """
        {"contents":[{"accountItem":{
          "accountName":{"simpleText":"Analytical Engine Radio"},
          "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
            {"offlineCacheKeyToken":{"clientCacheKey":"CACHE"}},
            {"datasyncIdToken":{"datasyncIdToken":"SYNC_BRAND||SESSION_B"}},
            {"pageIdToken":{"pageId":"113355"}}
          ]}}
        }}]}
        """

        const val NO_TOKENS = """
        {"contents":[{"accountItem":{
          "accountName":{"simpleText":"Ada Lovelace"},
          "serviceEndpoint":{"signalServiceEndpoint":{"signal":"CLIENT_SIGNAL"}}
        }}]}
        """
    }
}
