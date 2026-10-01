package io.tezra.fermix.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The working indicator's line (design sections 8.2 and 13.9, onboarding gotcha 19): a pure function
 * of the elapsed time and a per-turn seed.
 */
class IndicatorTest {
    private val pools = DRAFT_INDICATOR_POOLS

    @Test
    fun `it reads Thinking for the first 5 s`() {
        listOf(0L, 1L, 4_999L).forEach { assertEquals("Thinking", indicatorLine(it, seed = 7, daemonSpeaking = false)) }
    }

    @Test
    fun `from 5 s it takes a phrase of the first pool and keeps it for 8 s`() {
        val first = indicatorLine(5_000, seed = 7, daemonSpeaking = false)
        assertTrue(first in pools.first, "$first is not in the first pool")
        assertEquals(first, indicatorLine(12_999, seed = 7, daemonSpeaking = false))
        val second = indicatorLine(13_000, seed = 7, daemonSpeaking = false)
        assertTrue(second in pools.first)
        assertNotEquals(first, second)
    }

    @Test
    fun `from 45 s it takes its phrases from the second pool`() {
        (0 until 100).forEach { seed ->
            assertTrue(indicatorLine(44_999, seed.toLong(), daemonSpeaking = false) in pools.first)
            assertTrue(indicatorLine(45_000, seed.toLong(), daemonSpeaking = false) in pools.second)
            assertTrue(indicatorLine(600_000, seed.toLong(), daemonSpeaking = false) in pools.second)
        }
    }

    @Test
    fun `no phrase comes twice in a row, for any seed, over ten minutes`() {
        (0 until 200).forEach { seed ->
            val lines = (0..TEN_MINUTES_MS step PHRASE_MS).map { indicatorLine(it, seed.toLong(), false) }
            lines.zipWithNext().forEach { (before, after) -> assertNotEquals(before, after, "seed $seed") }
        }
    }

    @Test
    fun `every phrase of the second pool comes round before any comes again`() {
        (0 until 50).forEach { seed ->
            val slots = pools.second.indices.map { 45_000 + it * PHRASE_MS }
            val seen = slots.map { indicatorLine(it, seed.toLong(), daemonSpeaking = false) }.toSet()
            assertEquals(pools.second.toSet(), seen, "seed $seed")
        }
    }

    @Test
    fun `the same time and seed give the same line, and the seed varies the order`() {
        assertEquals(indicatorLine(30_000, 11, false), indicatorLine(30_000, 11, false))
        val orders =
            (0 until 20).map { seed ->
                (0 until 5).map { indicatorLine(5_000 + it * PHRASE_MS, seed.toLong(), false) }
            }
        assertTrue(orders.toSet().size > 1, "every seed gave the same order")
    }

    @Test
    fun `while the daemon's headings or a running tool are on the card it reads plain Thinking`() {
        listOf(0L, 5_000L, 45_000L, 600_000L).forEach {
            assertEquals("Thinking", indicatorLine(it, seed = 7, daemonSpeaking = true))
        }
    }

    @Test
    fun `the UI can replace the pools, and pools that could repeat a phrase are refused`() {
        val custom = IndicatorPools("Working", listOf("A", "B"), listOf("C", "D"))
        assertTrue(indicatorLine(5_000, 1, false, custom) in listOf("A", "B"))
        assertThrows<IllegalArgumentException> { IndicatorPools("Working", listOf("A"), listOf("C", "D")) }
        assertThrows<IllegalArgumentException> { IndicatorPools("Working", listOf("A", "B"), listOf("B", "D")) }
        assertThrows<IllegalArgumentException> { IndicatorPools("Working", listOf("A", "A"), listOf("C", "D")) }
        assertThrows<IllegalArgumentException> { IndicatorPools("A", listOf("A", "B"), listOf("C", "D")) }
    }

    @Test
    fun `a negative elapsed time is refused`() {
        assertThrows<IllegalArgumentException> { indicatorLine(-1, 7, false) }
    }

    private companion object {
        const val PHRASE_MS = 8_000L
        const val TEN_MINUTES_MS = 600_000L
    }
}
