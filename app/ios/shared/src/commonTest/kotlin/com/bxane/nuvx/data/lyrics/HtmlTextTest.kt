package com.bxane.nuvx.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue








class HtmlTextTest {



    @Test
    fun `the marked element is found and its text returned`() {
        val html = """
            <html><body>
            <div class="song">
              <div data-lyrics-container="true">[Verse 1]<br>Hello</div>
              <div class="ad">buy things</div>
            </div>
            </body></html>
        """.trimIndent()
        val text = HtmlText.textOfFirstElement(
            html, "div", { it.contains("data-lyrics-container") },
        )
        assertTrue(text!!.contains("Hello"))

        assertTrue(!text.contains("buy things"))
    }

    @Test
    fun `nesting is counted rather than cut at the first close tag`() {


        val html = """
            <div data-lyrics-container="true">
              line one
              <div class="wrapper"><span>line two</span></div>
              line three
            </div>
        """.trimIndent()
        val text = HtmlText.textOfFirstElement(html, "div", { true })!!
        assertTrue(text.contains("line one"))
        assertTrue(text.contains("line two"))
        assertTrue(text.contains("line three"))
    }

    @Test
    fun `a skipped subtree is skipped whole`() {


        val html = """
            <div data-lyrics-container="true">
              real line
              <div data-exclude-from-selection="true">Buy this now<div>nested ad</div></div>
              another real line
            </div>
        """.trimIndent()
        val text = HtmlText.textOfFirstElement(
            html, "div", { true }, skipWhen = { it.contains("data-exclude-from-selection") },
        )!!
        assertTrue(text.contains("real line"))
        assertTrue(text.contains("another real line"))
        assertTrue(!text.contains("Buy this now"))
        assertTrue(!text.contains("nested ad"))
    }

    @Test
    fun `a page with no such element is a miss`() {
        assertNull(HtmlText.textOfFirstElement("<html><body>hi</body></html>", "div", { true }))
    }

    @Test
    fun `an unclosed element at the end of a page still gives its content`() {


        val html = """<div data-lyrics-container="true">one<br>two"""
        val text = HtmlText.textOfFirstElement(html, "div", { true })
        assertTrue(text!!.contains("one"))
        assertTrue(text.contains("two"))
    }



    @Test
    fun `a break becomes a newline`() {
        val text = HtmlText.toText("one<br>two")
        assertEquals("one\ntwo", text)
    }

    @Test
    fun `a block element becomes a newline`() {
        assertTrue(HtmlText.toText("<p>one</p><p>two</p>").contains("\n"))
    }

    @Test
    fun `a self-closing tag does not confuse the depth count`() {


        val html = """<div data-lyrics-container="true">a<br/>b<hr>c</div>"""
        val text = HtmlText.textOfFirstElement(html, "div", { true })!!
        assertTrue(text.contains("a"))
        assertTrue(text.contains("c"))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("Rock & Roll", HtmlText.decodeEntities("Rock &amp; Roll"))
        assertEquals("it's", HtmlText.decodeEntities("it&#39;s"))
        assertEquals("café", HtmlText.decodeEntities("caf&#233;"))
        assertEquals("—", HtmlText.decodeEntities("&mdash;"))
    }

    @Test
    fun `an unknown entity is left alone rather than dropped`() {


        assertEquals("a &bogus; b", HtmlText.decodeEntities("a &bogus; b"))
    }

    @Test
    fun `a script or style body is not read as words`() {
        val text = HtmlText.toText("""before<script>var x = "after";</script>after""")
        assertTrue(text.contains("before"))
        assertTrue(!text.contains("var x"))
    }

    @Test
    fun `text with no markup passes through`() {
        assertEquals("just words", HtmlText.toText("just words"))
    }

    @Test
    fun `an attribute containing a greater-than does not end the tag early`() {


        val text = HtmlText.toText("""a<span title="x > y">b</span>c""")
        assertTrue(text.contains("a"))
        assertTrue(text.contains("b"))
        assertTrue(text.contains("c"))
    }
}
