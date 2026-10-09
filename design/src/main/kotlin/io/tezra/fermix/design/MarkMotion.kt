package io.tezra.fermix.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The Fermix mark's motion as data (the M51 update's sections 3, 5 and 6): its easings, the one sampler, and one
// table per property, which the reference player beside the update holds too (its DROP and HOP), so the two cannot
// drift. Times are in ms on a moment's one clock; lengths are in mark units, the mark's 100 × 100 box.

/** The easings of the update's 3.3. */
object MarkEasing {
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** What leaves a screen (the update's 7.2 and 7.5). */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** The update's "standard", (0.2, 0, 0, 1): Material's emphasized curve, which the app already names. */
    val Standard: Easing = FermixMotion.emphasized
    val InOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Gravity's t². */
    val Fall: Easing = Easing { it * it }

    /** 1 + 2.9(t − 1)³ + 1.9(t − 1)², which overshoots by 12 % (the update's "about 10 %") before it settles. */
    val Back: Easing =
        Easing { t ->
            val u = t - 1f
            1f + 2.9f * u * u * u + 1.9f * u * u
        }

    /** Stays at the segment's start value until the next key. */
    val Hold: Easing = Easing { 0f }
}

/** One key of a table: at [ms] the property is [value], and [easing] shapes the segment from here to the next key. */
@Immutable
data class MarkKey(
    val ms: Int,
    val value: Float,
    val easing: Easing = MarkEasing.InOut,
)

/**
 * A table's value at [ms] (the update's section 6, the reference player's `sample`): before the first key the first
 * value, between two keys the earlier key's easing over the segment, and from the last key on the last value. The
 * keys run in time order, two never at one time.
 */
fun sample(
    keys: List<MarkKey>,
    ms: Float,
): Float {
    require(keys.isNotEmpty()) { "A table has a key." }
    // By index, the check and the search alike: a moment samples its tables on every frame, and this makes nothing.
    for (i in 1 until keys.size) {
        require(keys[i].ms > keys[i - 1].ms) {
            "A table's keys run in time order: ${keys[i - 1].ms} ms, then ${keys[i].ms} ms."
        }
    }
    val after = firstAfter(keys, ms)
    return when {
        ms <= keys.first().ms -> keys.first().value
        after == keys.size -> keys.last().value
        else -> between(keys[after - 1], keys[after], ms)
    }
}

/** The index of the first of [keys] after [ms], or their count when none is. */
private fun firstAfter(
    keys: List<MarkKey>,
    ms: Float,
): Int {
    for (i in keys.indices) {
        if (keys[i].ms > ms) return i
    }
    return keys.size
}

/** The value at [ms] between the keys [from] and [to], on [from]'s easing. */
private fun between(
    from: MarkKey,
    to: MarkKey,
    ms: Float,
): Float = from.value + (to.value - from.value) * from.easing.transform((ms - from.ms) / (to.ms - from.ms))

/** A group of words rises in over this long on emphasized decelerate (the update's 3.2 and 7.4). */
const val RISE_MILLIS = 420

/** How far in a group of words is at [ms], from 0 before [start] to 1 once it has risen, [RISE_MILLIS] later. */
fun riseIn(
    ms: Float,
    start: Int,
): Float = MarkEasing.EmphasizedDecelerate.transform(((ms - start) / RISE_MILLIS).coerceIn(0f, 1f))

/** Welcome's drop (the update's 3.2): a dot falls, swells into the silhouette, opens its visor and pops its eyes. */
object Drop {
    /** The dot's lowest point. */
    val dropBottom = listOf(MarkKey(0, -6f, MarkEasing.Fall), MarkKey(380, 96f))

    /** The dot's half-width and half-height: stretched as it falls, squashed as it lands. */
    val dropRx = listOf(MarkKey(0, 5f, MarkEasing.Hold), MarkKey(330, 5f), MarkKey(400, 8.6f), MarkKey(470, 6f))
    val dropRy = listOf(MarkKey(0, 7.5f, MarkEasing.Hold), MarkKey(330, 7.5f), MarkKey(400, 4f), MarkKey(470, 6f))

    /** 0, the dot; 1, the silhouette (3.4). */
    val morph =
        listOf(MarkKey(0, 0f, MarkEasing.Hold), MarkKey(470, 0f, MarkEasing.EmphasizedDecelerate), MarkKey(900, 1f))

    /** The whole body's scale about the feet: it overshoots and settles. */
    val bodyScale =
        listOf(
            MarkKey(0, 1f, MarkEasing.Hold),
            MarkKey(700, 1f),
            MarkKey(860, 1.04f),
            MarkKey(980, 0.99f),
            MarkKey(1_060, 1f),
        )

    /** The visor's vertical scale about its centre, an eyelid: 0 is closed. */
    val visorOpen =
        listOf(
            MarkKey(0, 0f, MarkEasing.Hold),
            MarkKey(820, 0f, MarkEasing.EmphasizedDecelerate),
            MarkKey(1_000, 1.08f),
            MarkKey(1_080, 1f),
        )

    /** Each eye's scale about its centre: they pop in, left then right. */
    val eyeLeft = listOf(MarkKey(0, 0f, MarkEasing.Hold), MarkKey(940, 0f, MarkEasing.Back), MarkKey(1_140, 1f))
    val eyeRight = listOf(MarkKey(0, 0f, MarkEasing.Hold), MarkKey(1_000, 0f, MarkEasing.Back), MarkKey(1_200, 1f))

    /** Both eyes' height, a share of the full height: one blink as the mark settles. */
    val blink = listOf(MarkKey(0, 1f, MarkEasing.Hold), MarkKey(1_320, 1f), MarkKey(1_380, 0.1f), MarkKey(1_460, 1f))

    /** The dot lands, at the fall's last key: the update's optional `CLOCK_TICK` (3.5). */
    const val LANDS_MILLIS = 380

    /**
     * Whether a frame at [ms] draws the dot landing, for its tick: from [LANDS_MILLIS] until the squash it lands in
     * settles, at the last key of [dropRy]. A frame past that, the clock standing at its end or the first frame back
     * after time out of sight, finds the dot long landed.
     */
    fun drawsLanding(ms: Float): Boolean = ms >= LANDS_MILLIS && ms < dropRy.last().ms

    /** The drop's one clock runs this long (section 6), past the last words' landing at 1,740 ms. */
    const val CLOCK_MILLIS = 1_800

    /** The words rise in from these times: the title, the tagline, and the actions together. */
    val words = listOf(1_100, 1_210, 1_320)

    /** How far the words rise as they fade in. */
    val wordsRise: Dp = 10.dp
}

/** Paired's happy hop (the update's section 5), timed from the moment the screen takes the approval. */
object Hop {
    /** The eyes turn into arcs: the rectangles' height times 1 − happy, the arcs at opacity happy. */
    val happy = listOf(MarkKey(0, 0f), MarkKey(180, 1f))

    /** The body's lift; up is negative. */
    val hopY =
        listOf(
            MarkKey(0, 0f),
            MarkKey(90, 1.2f, MarkEasing.Standard),
            MarkKey(250, -10f, MarkEasing.Fall),
            MarkKey(400, 0f, MarkEasing.Hold),
            MarkKey(500, 0f),
        )

    /** The body's scale about the feet as it crouches, leaves the ground and lands. */
    val hopScaleY =
        listOf(
            MarkKey(0, 1f),
            MarkKey(90, 0.9f, MarkEasing.Standard),
            MarkKey(200, 1.06f),
            MarkKey(400, 0.92f),
            MarkKey(520, 1f),
        )
    val hopScaleX =
        listOf(
            MarkKey(0, 1f),
            MarkKey(90, 1.07f, MarkEasing.Standard),
            MarkKey(200, 0.96f),
            MarkKey(400, 1.06f),
            MarkKey(520, 1f),
        )

    /** The mark leaves the ground, rising from the crouch's key: M51's `CONFIRM`, `PairApproved`. */
    const val LEAVES_GROUND_MILLIS = 90

    /** The title rises in from this time (the update's 7.4), this far. */
    const val TITLE_MILLIS = 250
    val titleRise: Dp = 8.dp

    /** The hop's one clock runs this long, past the title's landing at 670 ms, as the drop's runs past its words. */
    const val CLOCK_MILLIS = 700
}

/**
 * One instant of the mark, every value the drawing takes (the update's sections 3 to 6 and 7.4, the reference player's
 * `REST` and `render`): the drop's, the hop's, the idle's, and where the eyes look and how far open they are
 * ([eyeX] and [eyeY], in mark units, down positive; [squint], a share of their height). [Rest] is the mark standing,
 * eyes open, looking ahead.
 */
@Immutable
data class MarkPose(
    val dropBottom: Float = 96f,
    val dropRx: Float = 6f,
    val dropRy: Float = 6f,
    val morph: Float = 1f,
    val bodyScale: Float = 1f,
    val visorOpen: Float = 1f,
    val eyeLeft: Float = 1f,
    val eyeRight: Float = 1f,
    val blink: Float = 1f,
    val happy: Float = 0f,
    val hopY: Float = 0f,
    val hopScaleX: Float = 1f,
    val hopScaleY: Float = 1f,
    val breathX: Float = 1f,
    val breathY: Float = 1f,
    val idleBlink: Float = 1f,
    val eyeX: Float = 0f,
    val eyeY: Float = 0f,
    val squint: Float = 1f,
) {
    companion object {
        val Rest = MarkPose()
    }
}

/** The drop at [ms] on its clock: every row of 3.2 sampled, the rest at rest. */
fun dropAt(ms: Float): MarkPose =
    MarkPose(
        dropBottom = sample(Drop.dropBottom, ms),
        dropRx = sample(Drop.dropRx, ms),
        dropRy = sample(Drop.dropRy, ms),
        morph = sample(Drop.morph, ms),
        bodyScale = sample(Drop.bodyScale, ms),
        visorOpen = sample(Drop.visorOpen, ms),
        eyeLeft = sample(Drop.eyeLeft, ms),
        eyeRight = sample(Drop.eyeRight, ms),
        blink = sample(Drop.blink, ms),
    )

/** The hop at [ms] on its clock: every row of section 5 sampled over the rest. */
fun hopAt(ms: Float): MarkPose =
    MarkPose(
        happy = sample(Hop.happy, ms),
        hopY = sample(Hop.hopY, ms),
        hopScaleX = sample(Hop.hopScaleX, ms),
        hopScaleY = sample(Hop.hopScaleY, ms),
    )
