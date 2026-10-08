package io.tezra.fermix.design

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

// The idle after a moment (the M51 update's section 4), as functions of the time into it: the breath, and the blinks
// at times a random source the caller passes in draws.

/** One breath. */
const val BREATH_MILLIS = 3_800f

/**
 * The idle breathes for this many whole breaths of being drawn at most, about ten minutes, and then the mark stands
 * at rest: the update breathes until the screen leaves, and a screen left showing that long is no longer watched, so
 * its frames stop. Whole breaths end the idle where it began, at rest.
 */
const val IDLE_MAX_MILLIS = 158 * BREATH_MILLIS

/**
 * The most one frame moves the idle on, in ms: the frames stop while the screen is out of sight or the phone asleep,
 * and the first one back moves it on no further than this, so the idle counts the time it was drawn, its bound among
 * it, and breathes on from where it was.
 */
const val IDLE_STEP_MAX_MILLIS = 100L

/** A blink's length, and the wait from one blink's start to the next one's, at least and at most. */
const val BLINK_MILLIS = 150f
private const val BLINK_GAP_MIN = 2_600f
private const val BLINK_GAP_MAX = 6_000f

/** How far a blink closes an eye at its middle: to a tenth of its height. */
private const val BLINK_DEPTH = 0.9f

/**
 * The breath at [ms] into the idle, from 0 to 1: the update's b = 0.5 + 0.5·sin(2πt / 3,800 ms), its t counted from a
 * quarter breath before the idle starts, so the first breath begins at rest and the moment before it does not jump.
 */
internal fun breathAt(ms: Float): Float {
    val breaths = (ms - BREATH_MILLIS / 4f) / BREATH_MILLIS
    // Only the breath's own phase counts: its fraction keeps the sine exact however long the idle has run.
    return (1f + sin(2f * PI.toFloat() * (breaths - floor(breaths)))) / 2f
}

/** The wait from one blink's start to the next one's, 2.6 to 6.0 s, as [random] draws it. */
internal fun blinkGap(random: Random): Float = BLINK_GAP_MIN + random.nextFloat() * (BLINK_GAP_MAX - BLINK_GAP_MIN)

/** The idle's blinks: [start] of the one under way or last, and [next], when the one to come starts. */
@Immutable
data class Blinks(
    val start: Float,
    val next: Float,
) {
    /**
     * The blinks at [ms]: once [ms] reaches [next], that blink is under way, and [random] draws when the next one
     * comes.
     */
    fun at(
        ms: Float,
        random: Random,
    ): Blinks = if (ms < next) this else Blinks(start = ms, next = ms + blinkGap(random))

    /** An eye's height at [ms], a share of its full height: 1 − 0.9·sin(πu) through a blink's 150 ms, else 1. */
    fun heightAt(ms: Float): Float {
        val u = (ms - start) / BLINK_MILLIS
        return if (u < 0f || u > 1f) 1f else 1f - BLINK_DEPTH * sin(PI.toFloat() * u)
    }

    companion object {
        /** No blink was, and none will be. */
        val Never = Blinks(start = Float.NEGATIVE_INFINITY, next = Float.POSITIVE_INFINITY)

        /** The idle's first blink comes 2.6 to 6.0 s into it, as [random] draws: the drop has just blinked. */
        fun first(random: Random): Blinks = Blinks(start = Float.NEGATIVE_INFINITY, next = blinkGap(random))
    }
}

/** Where the idle is: [ms] of it drawn, and its [blinks]. [Still] is no idle: no breath, no blink. */
@Immutable
data class MarkIdle(
    val ms: Float,
    val blinks: Blinks,
) {
    /**
     * The idle at a frame [gap] ms after this one's: moved on by the gap, by [IDLE_STEP_MAX_MILLIS] at most, with the
     * blinks at that time, the next one's wait drawn from [random] as one starts.
     */
    fun after(
        gap: Long,
        random: Random,
    ): MarkIdle {
        require(gap >= 0L) { "Frames run forward, not $gap ms back." }
        val moved = ms + gap.coerceAtMost(IDLE_STEP_MAX_MILLIS)
        return MarkIdle(ms = moved, blinks = blinks.at(moved, random))
    }

    companion object {
        val Still = MarkIdle(ms = 0f, blinks = Blinks.Never)
    }
}

/** This pose breathing about the feet and blinking as [idle] stands: vertical 1 + 0.016·b, horizontal 1 − 0.008·b. */
fun MarkPose.idling(idle: MarkIdle): MarkPose {
    val breath = breathAt(idle.ms)
    return copy(
        breathX = 1f - BREATH_NARROW * breath,
        breathY = 1f + BREATH_STRETCH * breath,
        idleBlink = idle.blinks.heightAt(idle.ms),
    )
}

private const val BREATH_STRETCH = 0.016f
private const val BREATH_NARROW = 0.008f
