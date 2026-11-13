package com.bxane.nuvx.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame










class BoundedLruTest {

    @Test
    fun `reads back what was written`() {
        val lru = BoundedLru<String, String>(4)
        lru["a"] = "1"
        assertEquals("1", lru["a"])
        assertNull(lru["b"])
    }

    @Test
    fun `writing the same key twice overwrites rather than growing`() {
        val lru = BoundedLru<String, String>(4)
        lru["a"] = "1"
        lru["a"] = "2"
        assertEquals(1, lru.size)
        assertEquals("2", lru["a"])
    }

    @Test
    fun `it never grows past its ceiling`() {
        val lru = BoundedLru<String, String>(3)
        (1..10).forEach { lru["k$it"] = "$it" }
        assertEquals(3, lru.size)
    }

    @Test
    fun `the coldest entry is the one that goes`() {
        val lru = BoundedLru<String, String>(3)
        lru["a"] = "1"
        lru["b"] = "2"
        lru["c"] = "3"
        lru["d"] = "4"

        assertNull(lru["a"])
        assertEquals("2", lru["b"])
        assertEquals("4", lru["d"])
    }

    @Test
    fun `reading an entry protects it from the next eviction`() {
        val lru = BoundedLru<String, String>(3)
        lru["a"] = "1"
        lru["b"] = "2"
        lru["c"] = "3"

        assertEquals("1", lru["a"])
        lru["d"] = "4"
        assertEquals("1", lru["a"])
        assertNull(lru["b"])
    }

    @Test
    fun `rewriting an entry also protects it`() {
        val lru = BoundedLru<String, String>(3)
        lru["a"] = "1"
        lru["b"] = "2"
        lru["c"] = "3"
        lru["a"] = "1-updated"
        lru["d"] = "4"
        assertEquals("1-updated", lru["a"])
        assertNull(lru["b"])
    }

    @Test
    fun `a capacity of one keeps only the newest`() {
        val lru = BoundedLru<String, String>(1)
        lru["a"] = "1"
        lru["b"] = "2"
        assertNull(lru["a"])
        assertEquals("2", lru["b"])
    }

    @Test
    fun `clear empties it`() {
        val lru = BoundedLru<String, String>(4)
        lru["a"] = "1"
        lru.clear()
        assertEquals(0, lru.size)
        assertNull(lru["a"])
    }

    @Test
    fun `a re-read of the most recent entry does not rewrite the map`() {



        val lru = BoundedLru<String, String>(4)
        lru["a"] = "1"
        val first = lru["a"]
        assertSame(first, lru["a"])
    }
}
