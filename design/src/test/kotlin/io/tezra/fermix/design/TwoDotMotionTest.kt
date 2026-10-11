package io.tezra.fermix.design

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

private const val CLOSE = 1e-4f
private const val TURN = 2f * PI.toFloat()

/** [radians] brought into one turn, from 0. */
private fun turned(radians: Float): Float = radians.mod(TURN)

/**
 * The thinking card's two-dot mark in motion (the owner, 2026-10-10): the dots' orbit and the halo under them,
 * breathing between 0.92 and 1.08 of its 28 dp on b = 0.5 + 0.5·sin(2πt / 3,800 ms), twice as fast while a tool
 * runs; the halo the ink alone, at its peak in the middle and nothing at its edge; one clock for both, on the
 * frames, which reduce-motion never asks for.
 */
class TwoDotMotionTest {
    @Test
    fun `the halo is 0,92 of its size at the foot of a breath, 1 at its middle and 1,08 at its top`() {
        assertEquals(0.92f, haloScale(0f), CLOSE)
        assertEquals(1f, haloScale(0.5f), CLOSE)
        assertEquals(1.08f, haloScale(1f), CLOSE)
        assertEquals(28f, HALO_DIAMETER.value)
    }

    @Test
    fun `the breath is b = 0,5 + 0,5 sin(2 pi t over its period), at its middle when the clock starts`() {
        for (ms in listOf(0f, 475f, 950f, 1_900f, 2_850f, 3_800f, 12_345f)) {
            val b = 0.5f + 0.5f * sin(TURN * ms / 3_800f)
            assertEquals(b, haloBreath(ms / HALO_BREATH_MILLIS), CLOSE, "at $ms ms")
        }
        assertEquals(3_800f, HALO_BREATH_MILLIS)
        assertEquals(1_900f, HALO_TOOL_BREATH_MILLIS)
    }

    @Test
    fun `the halo is the ink at 14 percent in light mode and 12,5 in dark, fading to nothing at its edge`() {
        assertEquals(0.14f, haloPeak(FermixColors.Light), CLOSE)
        assertEquals(0.125f, haloPeak(FermixColors.Dark), CLOSE)
        assertEquals(0.14f, haloAlphaAt(0f, 0.14f), CLOSE)
        assertEquals(0f, haloAlphaAt(1f, 0.14f), CLOSE)
        assertEquals(0f, haloAlphaAt(1.3f, 0.14f), CLOSE)
        val along = (0..20).map { haloAlphaAt(it / 20f, 0.14f) }
        assertTrue(along.zipWithNext().all { (a, b) -> b < a }, "it fades all the way out: $along")
    }

    @Test
    fun `under reduced motion the mark asks for no frame and holds its pose, the halo at rest`() =
        runBlocking {
            val clock = SteppedClock(frames(0L, 61))
            val motion = TwoDotMotion()
            val run = launch(clock) { motion.run(reduced = true) { false } }
            yield()
            assertTrue(run.isCompleted, "nothing waits on a frame")
            assertEquals(61, clock.unasked)
            assertEquals(ORBIT_POSE, motion.angle, CLOSE)
            assertEquals(1f, motion.haloScale, CLOSE)
        }

    @Test
    fun `each frame turns the dots once per 1,200 ms and breathes the halo once per 3,800 ms`() =
        runBlocking {
            val clock = SteppedClock(frames(0L, 61))
            val motion = TwoDotMotion()
            val run = launch(clock) { motion.run(reduced = false) { false } }
            try {
                yield()
                // The first frame starts the clock; 60 more are 960 ms.
                assertEquals(turned(ORBIT_POSE + TURN * 960f / 1_200f), motion.angle, CLOSE)
                assertEquals(haloScale(haloBreath(960f / 3_800f)), motion.haloScale, CLOSE)
            } finally {
                run.cancel()
            }
        }

    @Test
    fun `while a tool runs the halo breathes once per 1,900 ms, on from where it was`() =
        runBlocking {
            val clock = SteppedClock(frames(0L, 61))
            val motion = TwoDotMotion()
            var asked = 0
            val run = launch(clock) { motion.run(reduced = false) { asked++ >= 30 } }
            try {
                yield()
                // 480 ms at the resting breath, then 480 ms at the tool's: the breath never jumps.
                val turns = 480f / 3_800f + 480f / 1_900f
                assertEquals(haloScale(haloBreath(turns)), motion.haloScale, CLOSE)
            } finally {
                run.cancel()
            }
        }

    @Test
    fun `a frame after a gap moves the mark on by 100 ms at most, so time out of sight counts for nothing`() =
        runBlocking {
            val clock = SteppedClock(listOf(0L, 16L, 600_016L))
            val motion = TwoDotMotion()
            val run = launch(clock) { motion.run(reduced = false) { false } }
            try {
                yield()
                assertEquals(turned(ORBIT_POSE + TURN * 116f / 1_200f), motion.angle, CLOSE)
            } finally {
                run.cancel()
            }
        }

    @Test
    fun `after half an hour drawn the mark stands at its pose and asks for no more frames`() =
        runBlocking {
            val bound = (TWO_DOT_MAX_MILLIS / 100L).toInt()
            val clock = SteppedClock(List(bound + 10) { it * 100L })
            val motion = TwoDotMotion()
            val run = launch(clock) { motion.run(reduced = false) { false } }
            try {
                yield()
                assertTrue(run.isCompleted, "the clock still asks for frames")
                assertTrue(clock.unasked > 0)
                assertEquals(ORBIT_POSE, motion.angle, CLOSE)
                assertEquals(1f, motion.haloScale, CLOSE)
                assertEquals(1_800_000L, TWO_DOT_MAX_MILLIS)
            } finally {
                run.cancel()
            }
        }
}
