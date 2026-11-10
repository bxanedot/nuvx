package com.bxane.nuvx

import com.bxane.nuvx.ui.player.edgeScrollSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEdgeScrollTest {

    private fun speed(top: Float, viewportStart: Int = 0, viewportEnd: Int = 600) =
        edgeScrollSpeed(
            top = top,
            bottom = top + 100f,
            viewportStart = viewportStart,
            viewportEnd = viewportEnd,
            zone = 50f,
            speed = 1000f,
        )

    @Test
    fun `a row in the middle of the list scrolls it neither way`() {
        assertEquals(0f, speed(top = 250f), 0f)
    }

    @Test
    fun `a row clear of the zone by a single pixel still scrolls nothing`() {
        assertEquals(0f, speed(top = 51f), 0f)
        assertEquals(0f, speed(top = 449f), 0f)
    }

    @Test
    fun `reaching the top of the list scrolls back towards the start`() {
        assertTrue(speed(top = 40f) < 0f)
    }

    @Test
    fun `reaching the bottom scrolls on towards the end`() {
        assertTrue(speed(top = 460f) > 0f)
    }

    @Test
    fun `the speed at the very edge is the full speed asked for`() {
        assertEquals(-1000f, speed(top = 0f), 0.01f)
        assertEquals(1000f, speed(top = 500f), 0.01f)
    }

    @Test
    fun `held past the edge it goes no faster than at it`() {
        assertEquals(-1000f, speed(top = -400f), 0.01f)
        assertEquals(1000f, speed(top = 900f), 0.01f)
    }

    @Test
    fun `a row barely inside the zone already moves the list`() {
        val barely = speed(top = 49.5f)
        assertTrue("$barely", barely <= -200f && barely > -250f)
    }

    @Test
    fun `the ramp is even across the zone`() {
        assertEquals(-600f, speed(top = 25f), 0.01f)
        assertEquals(600f, speed(top = 475f), 0.01f)
    }

    @Test
    fun `the zone is measured from the viewport, not from zero`() {
        assertEquals(0f, speed(top = 0f, viewportStart = -200, viewportEnd = 400), 0f)
        assertEquals(-1000f, speed(top = -200f, viewportStart = -200, viewportEnd = 400), 0.01f)
    }

    @Test
    fun `a viewport too short to clear both edges scrolls neither way`() {
        assertEquals(0f, speed(top = 10f, viewportStart = 0, viewportEnd = 120), 0f)
    }

    @Test
    fun `no zone is no auto-scroll`() {
        assertEquals(
            0f,
            edgeScrollSpeed(
                top = 0f,
                bottom = 100f,
                viewportStart = 0,
                viewportEnd = 600,
                zone = 0f,
                speed = 1000f,
            ),
            0f,
        )
    }
}
