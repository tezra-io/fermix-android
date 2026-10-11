package io.tezra.fermix.design

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

// The thinking card's two-dot mark in motion (the owner, 2026-10-10): its dots' orbit and, under them, a halo of the
// ink that breathes, the idea of libraries.dev's thinking orbs (README, "Design"). One clock moves both, on the frames,
// and the mark reads it as it draws, so a frame redraws the mark and recomposes nothing.

/** The halo's size at rest: the breath at its middle. */
internal val HALO_DIAMETER = 28.dp

/**
 * One breath of the halo, b = 0.5 + 0.5·sin(2πt / 3,800 ms), the length of the Fermix mark's own idle breath; and
 * one while a tool runs, twice as fast.
 */
internal const val HALO_BREATH_MILLIS = BREATH_MILLIS
internal const val HALO_TOOL_BREATH_MILLIS = BREATH_MILLIS / 2f

/** The halo's size at the foot and at the top of a breath, as a share of [HALO_DIAMETER]. */
private const val HALO_SMALLEST = 0.92f
private const val HALO_LARGEST = 1.08f

/**
 * The ink's alpha at the halo's middle, in each mode: light mode's 14 % takes white down 11.9 in CIELAB lightness;
 * dark mode's light ink brightens near-black faster, and 12.5 % lifts it by 11.8, so the two weigh the same.
 */
private const val LIGHT_PEAK = 0.14f
private const val DARK_PEAK = 0.125f

/**
 * The halo's falloff: its alpha, as a share of the peak, halfway out, between the peak at its middle and nothing at
 * its edge; the gradient's three stops are evenly spaced.
 */
private const val HALFWAY = 0.5f
private const val HALFWAY_SHARE = 0.45f

/**
 * The mark moves for this long drawn at most, half an hour, and then holds its pose, the halo at rest: a card shown
 * that long is no longer watched, and its phrase and shimmer still say it is thinking.
 */
internal const val TWO_DOT_MAX_MILLIS = 1_800_000L

/** The most one frame moves the mark on, in ms: time out of sight, or asleep, counts for no more. */
private const val STEP_MAX_MILLIS = 100L

/** The breath, from 0 to 1, at [turns] breaths into it: b = 0.5 + 0.5·sin(2π·turns), at its middle at 0. */
internal fun haloBreath(turns: Float): Float = (1f + sin(2f * PI.toFloat() * turns)) / 2f

/** The halo's size at [breath], as a share of [HALO_DIAMETER]: 0.92 at its foot, 1 at its middle, 1.08 at its top. */
internal fun haloScale(breath: Float): Float = HALO_SMALLEST + (HALO_LARGEST - HALO_SMALLEST) * breath

/** The ink's alpha at the halo's middle in the mode of [colors]: the mode whose ink is the lighter is dark. */
internal fun haloPeak(colors: FermixColors): Float =
    if (colors.ink.luminance() > colors.canvas.luminance()) DARK_PEAK else LIGHT_PEAK

/** The halo's alpha at [fraction] of its radius out, from [peak] at its middle to nothing at its edge and past it. */
internal fun haloAlphaAt(
    fraction: Float,
    peak: Float,
): Float {
    val out = fraction.coerceIn(0f, 1f)
    val share =
        if (out <= HALFWAY) {
            1f - (1f - HALFWAY_SHARE) * out / HALFWAY
        } else {
            HALFWAY_SHARE * (1f - out) / (1f - HALFWAY)
        }
    return peak * share
}

/** The halo's falloff as the gradient's alphas at its middle, halfway out and its edge, evenly spaced stops. */
internal fun haloStops(peak: Float): List<Float> = listOf(peak, peak * HALFWAY_SHARE, 0f)

/** The dots' line at [turns] of their orbit from the canon's pose, in radians, the first dot to the left. */
private fun orbitAngle(turns: Float): Float = (ORBIT_POSE + 2f * PI.toFloat() * turns).mod(2f * PI.toFloat())

/**
 * The mark's one clock: the dots' [angle], a turn per [FermixMotion.MARK_ORBIT_MILLIS] from the canon's pose, and the
 * halo's breath, a turn per [HALO_BREATH_MILLIS], or per [HALO_TOOL_BREATH_MILLIS] while a tool runs, each frame
 * moving it on from where it was, so the breath never jumps as a tool starts or stops. Under reduce-motion it holds
 * the pose with the halo at rest. The pose it holds is two plain floats the mark reads as it draws, so a frame boxes
 * nothing; a preview makes one at a pose of its own ([angle], [haloScale]) and never runs it.
 */
@Stable
internal class TwoDotMotion(
    angle: Float = orbitAngle(0f),
    haloScale: Float = haloScale(haloBreath(0f)),
) {
    private var orbitTurns = 0f
    private var breathTurns = 0f

    /** The dots' line, in radians, the first dot to the left. */
    var angle by mutableFloatStateOf(angle)
        private set

    /** The halo's size as a share of [HALO_DIAMETER]. */
    var haloScale by mutableFloatStateOf(haloScale)
        private set

    /**
     * Moves the mark on each frame, the breath at the tool's pace while [fast] says one runs, until the screen
     * leaves and cancels it or [TWO_DOT_MAX_MILLIS] of it has been drawn, when the mark holds its pose and asks for no
     * more frames; under [reduced] motion it asks for none at all. Its frames are an infinite animation's, which
     * Compose's tests and previews hold.
     */
    suspend fun run(
        reduced: Boolean,
        fast: () -> Boolean,
    ) {
        if (!reduced) turn(fast)
        rest()
    }

    private suspend fun turn(fast: () -> Boolean) {
        var last = withInfiniteAnimationFrameMillis { it }
        var drawn = 0L
        while (drawn < TWO_DOT_MAX_MILLIS) {
            val frame = withInfiniteAnimationFrameMillis { it }
            // Every frame counts a millisecond at least, so the bound comes within that many frames.
            val step = (frame - last).coerceIn(1L, STEP_MAX_MILLIS)
            last = frame
            drawn += step
            val breath = if (fast()) HALO_TOOL_BREATH_MILLIS else HALO_BREATH_MILLIS
            orbitTurns = (orbitTurns + step / FermixMotion.MARK_ORBIT_MILLIS.toFloat()).mod(1f)
            breathTurns = (breathTurns + step / breath).mod(1f)
            hold()
        }
    }

    private fun rest() {
        orbitTurns = 0f
        breathTurns = 0f
        hold()
    }

    /** The pose the turns make, which the mark draws. */
    private fun hold() {
        angle = orbitAngle(orbitTurns)
        haloScale = haloScale(haloBreath(breathTurns))
    }
}
