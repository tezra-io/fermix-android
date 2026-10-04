package io.tezra.fermix.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The voice note's 40 bars and its clock (design section 8.5). */
class WaveformTest {
    @Test
    fun `a level is the square root of its share of the loudest sample, never below a quiet line`() {
        assertEquals(1f, levelOf(MAX_AMPLITUDE))
        assertEquals(0.5f, levelOf(MAX_AMPLITUDE / 4), 0.001f)
        assertEquals(QUIET_LEVEL, levelOf(0))
        assertEquals(1f, levelOf(Int.MAX_VALUE))
    }

    @Test
    fun `a take's samples fold into 40 bars, each the loudest of its share`() {
        val levels = List(400) { if (it % 10 == 3) 0.9f else 0.1f }
        val bars = barsOf(levels)
        assertEquals(WAVE_BARS, bars.size)
        assertTrue(bars.all { it == 0.9f })
        assertEquals(List(WAVE_BARS) { QUIET_LEVEL }, barsOf(emptyList()))
        assertEquals(List(WAVE_BARS) { 0.4f }, barsOf(listOf(0.4f)))
    }

    @Test
    fun `the live row shows the newest 40 samples, quiet bars before them`() {
        val few = liveBars(listOf(0.5f, 0.6f))
        assertEquals(WAVE_BARS, few.size)
        assertEquals(listOf(QUIET_LEVEL, 0.5f, 0.6f), few.takeLast(3))
        val many = liveBars(List(100) { it / 100f })
        assertEquals(0.99f, many.last())
        assertEquals(0.6f, many.first())
    }

    @Test
    fun `durations read m colon ss`() {
        assertEquals("0:00", durationText(0L))
        assertEquals("0:11", durationText(11_400L))
        assertEquals("12:05", durationText(725_000L))
        assertEquals(List(WAVE_BARS) { EVEN_LEVEL }, noteBars(null))
    }
}
