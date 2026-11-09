package com.bxane.nuvx

import com.bxane.nuvx.ui.components.githubMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun htmlAndLineBreaksBecomeCommonMark() {
        val md = githubMarkdown("Line one\nline two\n\n<p align=\"center\"><img src=\"https://a/1.png\" alt=\"One\"></p>\nA<br>B\n```\ncode\n```")
        assertEquals("Line one  \nline two  \n\n ![One](https://a/1.png)  \nA  \nB  \n```\ncode\n```  ", md)
    }
}
