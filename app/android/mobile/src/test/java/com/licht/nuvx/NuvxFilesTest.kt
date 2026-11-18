package com.licht.nuvx

import com.licht.nuvx.data.sync.NuvxFiles
import org.junit.Assert.assertEquals
import org.junit.Test

class NuvxFilesTest {
    @Test
    fun onlyWellFormedSongsFromTheComputerAreOffered() {
        val list = NuvxFiles.parseList(
            """{"files":[
              {"id":"dQw4w9WgXcQ","title":"Never","artist":"Rick","art":"https://i.ytimg.com/vi/x/hq.jpg","durationMs":213000,"size":3500000,"ext":"m4a"},
              {"id":"../etc/pass","title":"Bad id","size":10,"ext":"m4a"},
              {"id":"kJQP7kiw5Fk","title":"Odd type","size":10,"ext":"exe"},
              {"id":"9bZkp7q19f0","title":"Empty","size":0,"ext":"webm"},
              {"id":"YQHsXMglC9A","title":"Plain art","art":"http://192.168.1.1/x","size":5,"ext":"webm"}
            ]}""",
        )
        assertEquals(listOf("dQw4w9WgXcQ", "YQHsXMglC9A"), list.map { it.id })
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", list[0].art)
        assertEquals(null, list[1].art)
    }
}
