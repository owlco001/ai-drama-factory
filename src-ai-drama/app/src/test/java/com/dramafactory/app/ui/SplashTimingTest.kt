package com.dramafactory.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplashTimingTest {
    @Test
    fun `default timing uses full splash duration for exit`() {
        val timing = splashTiming()

        assertEquals(2320L, timing.fadeStartMs)
        assertEquals(280L, timing.fadeDurationMs)
        assertEquals(2600L, timing.totalMs)
        assertEquals(timing.totalMs, timing.fadeStartMs + timing.fadeDurationMs)
    }

    @Test
    fun `short total duration clamps fade without exceeding timeline`() {
        val timing = splashTiming(totalMs = 100L, fadeDurationMs = 280L)

        assertEquals(0L, timing.fadeStartMs)
        assertEquals(100L, timing.fadeDurationMs)
        assertEquals(100L, timing.fadeStartMs + timing.fadeDurationMs)
    }

    @Test
    fun `completion gate invokes completion only once`() {
        val gate = SplashCompletionGate()

        assertTrue(gate.complete())
        assertFalse(gate.complete())
        assertFalse(gate.complete())
    }
}