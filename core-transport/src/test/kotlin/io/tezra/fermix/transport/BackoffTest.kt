package io.tezra.fermix.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/** Design section 5.1's reconnect wait: 1 s doubling to 30 s, with jitter, and back to 1 s after a success. */
class BackoffTest {
    private val ceilings = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)

    @Test
    fun `each failure doubles the wait from 1 s until it holds at 30 s`() {
        var backoff = Backoff(jitter = Random(SEED))
        ceilings.forEach { ceiling ->
            assertEquals(ceiling, backoff.ceilingMs)
            backoff = backoff.next()
        }
    }

    @Test
    fun `the step stops at the cap, so no count of failures overflows it`() {
        var backoff = Backoff(jitter = Random(SEED))
        repeat(1_000) { backoff = backoff.next() }
        assertEquals(5, backoff.step)
        assertSame(backoff, backoff.next())
        assertEquals(30_000L, backoff.ceilingMs)
    }

    @Test
    fun `a wait is jittered between half its ceiling and the ceiling, both ends reached`() {
        var backoff = Backoff(jitter = Random(SEED))
        ceilings.forEach { ceiling ->
            val waits = List(DRAWS) { backoff.delayMs() }
            assertTrue(waits.all { it in ceiling / 2..ceiling }, "a wait outside ${ceiling / 2}..$ceiling")
            assertTrue(waits.min() < ceiling * 6 / 10, "the low end of $ceiling's jitter is never drawn")
            assertTrue(waits.max() > ceiling * 9 / 10, "the high end of $ceiling's jitter is never drawn")
            backoff = backoff.next()
        }
    }

    @Test
    fun `a success resets the wait to the first`() {
        var backoff = Backoff(jitter = Random(SEED))
        repeat(4) { backoff = backoff.next() }
        val reset = backoff.reset()
        assertEquals(0, reset.step)
        assertEquals(1_000L, reset.ceilingMs)
    }

    @Test
    fun `a first wait that is not positive or above the last is refused`() {
        assertThrows<IllegalArgumentException> { Backoff(firstMs = 0, jitter = Random(SEED)) }
        assertThrows<IllegalArgumentException> { Backoff(firstMs = 2_000, maxMs = 1_000, jitter = Random(SEED)) }
        assertThrows<IllegalArgumentException> { Backoff(maxMs = Long.MAX_VALUE, jitter = Random(SEED)) }
    }

    private companion object {
        const val SEED = 51
        const val DRAWS = 1_000
    }
}
