package com.bxane.nuvx

import com.bxane.nuvx.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistOwnershipTest {

    private fun owned(json: String): Boolean? =
        InnertubeParser.parsePlaylistOwned(Json.parseToJsonElement(json))

    @Test
    fun `editable header renderer means own playlist`() {
        val json = """
        {"header":{"musicEditablePlaylistDetailHeaderRenderer":{
          "header":{"musicResponsiveHeaderRenderer":{"buttons":[
            {"toggleButtonRenderer":{
              "defaultIcon":{"iconType":"BOOKMARK_BORDER"},
              "toggledIcon":{"iconType":"BOOKMARK"}}}
          ]}}
        }}}
        """
        assertEquals(true, owned(json))
    }

    @Test
    fun `delete in the header menu means own playlist`() {
        val json = """
        {"header":{"musicResponsiveHeaderRenderer":{
          "buttons":[{"menuRenderer":{"items":[
            {"menuNavigationItemRenderer":{"icon":{"iconType":"DELETE"}}}
          ]}}]
        }}}
        """
        assertEquals(true, owned(json))
    }

    @Test
    fun `header with no save bookmark means own playlist`() {
        val json = """
        {"header":{"musicResponsiveHeaderRenderer":{
          "buttons":[
            {"musicPlayButtonRenderer":{}},
            {"menuRenderer":{"items":[
              {"menuNavigationItemRenderer":{"icon":{"iconType":"SHARE"}}}
            ]}}
          ]
        }}}
        """
        assertEquals(true, owned(json))
    }

    @Test
    fun `save bookmark means saved rather than own`() {
        val json = """
        {"header":{"musicResponsiveHeaderRenderer":{
          "buttons":[
            {"musicPlayButtonRenderer":{}},
            {"toggleButtonRenderer":{
              "isToggled":"true",
              "defaultIcon":{"iconType":"BOOKMARK_BORDER"},
              "toggledIcon":{"iconType":"BOOKMARK"}}}
          ]
        }}}
        """
        assertEquals(false, owned(json))
    }

    @Test
    fun `a track row's delete does not make the playlist own`() {
        val json = """
        {"header":{"musicResponsiveHeaderRenderer":{
          "buttons":[{"toggleButtonRenderer":{
            "defaultIcon":{"iconType":"BOOKMARK_BORDER"}}}]
        }},
         "contents":[{"musicResponsiveListItemRenderer":{"menu":{"menuRenderer":{"items":[
           {"menuNavigationItemRenderer":{"icon":{"iconType":"DELETE"}}}
         ]}}}}]}
        """
        assertEquals(false, owned(json))
    }

    @Test
    fun `a headerless response says nothing`() {
        val json = """
        {"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[
          {"musicResponsiveListItemRenderer":{}}
        ]}}}
        """
        assertNull(owned(json))
    }
}
