package io.tezra.fermix.design

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private const val CLOSE = 1e-4f

/** A random source that always draws [value], so a test knows the gap it picks. */
private class Fixed(
    private val value: Int,
) : Random() {
    override fun nextBits(bitCount: Int): Int = value ushr (Int.SIZE_BITS - bitCount)
}

/** Draws 0, so a gap is the shortest. */
private val LOWEST = Fixed(0)

/** Draws every bit set, so a gap is the longest a float allows. */
private val HIGHEST = Fixed(-1)

/**
 * The idle after a moment (the M51 update's section 4) as functions of time: the breath about the feet, and the blinks,
 * whose times come from a random source the caller passes in.
 */
class MarkIdleTest {
    @Test
    fun `the breath is the update's b, counted from a quarter breath before the idle, so it starts at rest`() {
        for (ms in listOf(0f, 500f, 950f, 1_900f, 2_850f, 3_800f, 12_345f)) {
            val t = ms - 950f
            val b = 0.5f + 0.5f * sin(2f * PI.toFloat() * t / 3_800f)
            assertEquals(b, breathAt(ms), CLOSE, "at $ms ms")
        }
        assertEquals(0f, breathAt(0f), CLOSE)
        assertEquals(1f, breathAt(1_900f), CLOSE)
        assertEquals(0f, breathAt(3_800f), CLOSE)
    }

    @Test
    fun `breathing stretches up 1,6 percent and narrows 0,8 percent at the top of a breath, and rests at its foot`() {
        val top = MarkPose.Rest.idling(MarkIdle(ms = 1_900f, blinks = Blinks.Never))
        assertEquals(1.016f, top.breathY, CLOSE)
        assertEquals(0.992f, top.breathX, CLOSE)
        assertEquals(MarkPose.Rest, MarkPose.Rest.idling(MarkIdle(ms = 0f, blinks = Blinks.Never)))
        assertEquals(MarkPose.Rest, MarkPose.Rest.idling(MarkIdle.Still))
    }

    @Test
    fun `a blink takes 150 ms, the eye at 1 minus 0,9 sin(pi u) of its height`() {
        val blinks = Blinks(start = 1_000f, next = 5_000f)
        assertEquals(1f, blinks.heightAt(999f))
        assertEquals(1f, blinks.heightAt(1_000f), CLOSE)
        assertEquals(0.1f, blinks.heightAt(1_075f), CLOSE)
        assertEquals(1f - 0.9f * sin(PI.toFloat() / 3f), blinks.heightAt(1_050f), CLOSE)
        assertEquals(1f, blinks.heightAt(1_150f), CLOSE)
        assertEquals(1f, blinks.heightAt(1_151f))
    }

    @Test
    fun `a blink comes 2,6 to 6,0 s after the one before, as the random source draws`() {
        assertEquals(2_600f, blinkGap(LOWEST), CLOSE)
        assertEquals(6_000f, blinkGap(HIGHEST), 1f)
        val seeded = Random(18)
        assertTrue((1..1_000).all { blinkGap(seeded) in 2_600f..6_000f })
    }

    @Test
    fun `the first blink comes that long into the idle, and each starts the wait for the next`() {
        val first = Blinks.first(LOWEST)
        assertEquals(2_600f, first.next)
        assertEquals(1f, first.heightAt(0f))
        assertEquals(first, first.at(2_599f, HIGHEST))
        val blinking = first.at(2_610f, LOWEST)
        assertEquals(Blinks(start = 2_610f, next = 5_210f), blinking)
        assertEquals(0.1f, blinking.heightAt(2_685f), CLOSE)
        assertEquals(blinking, blinking.at(5_209f, LOWEST))
    }

    @Test
    fun `idling takes the breath and the blink at the idle's time`() {
        val idle = MarkIdle(ms = 2_685f, blinks = Blinks(start = 2_610f, next = 5_210f))
        val pose = MarkPose.Rest.copy(happy = 1f).idling(idle)
        assertEquals(0.1f, pose.idleBlink, CLOSE)
        assertEquals(1f + 0.016f * breathAt(2_685f), pose.breathY, CLOSE)
        assertEquals(1f, pose.happy)
    }

    @Test
    fun `a frame moves the idle on by the time since the last one, by 100 ms at most, and blinks on that time`() {
        val idle = MarkIdle(ms = 1_000f, blinks = Blinks.first(LOWEST))
        assertEquals(MarkIdle(ms = 1_016f, blinks = idle.blinks), idle.after(16L, HIGHEST))
        // A frame after the screen was out of sight, or the phone asleep, moves it on as one long frame.
        assertEquals(1_100f, idle.after(600_000L, HIGHEST).ms)
        assertEquals(1_000f, idle.after(0L, HIGHEST).ms)
        val due = MarkIdle(ms = 2_590f, blinks = idle.blinks)
        assertEquals(MarkIdle(ms = 2_606f, blinks = Blinks(start = 2_606f, next = 5_206f)), due.after(16L, LOWEST))
        assertThrows(IllegalArgumentException::class.java) { idle.after(-1L, LOWEST) }
    }

    @Test
    fun `the idle stops after whole breaths, about ten minutes in, at rest`() {
        assertEquals(0f, IDLE_MAX_MILLIS % BREATH_MILLIS)
        assertTrue(IDLE_MAX_MILLIS in 600_000f..610_000f)
        assertEquals(0f, breathAt(IDLE_MAX_MILLIS), 1e-3f)
    }
}
