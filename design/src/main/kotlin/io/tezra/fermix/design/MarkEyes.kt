package io.tezra.fermix.design

import kotlin.math.floor

// The mark's eyes on Connecting and Verify (the M51 update's 7.4) as functions of the time into a phase: the search's
// sweep, the check's narrowing, the securing's opening and blink, and Verify's look down at the code. Each gives a
// pose of the eyes alone, which a screen breathes and blinks with the idle (MarkPose.idling) and FermixMark draws.
// Lengths are in mark units.

/** Connecting while it reaches the computer, and tries Tailscale: the eyes sweep side to side, looking for it. */
object Search {
    /** How far the eyes look each way. */
    const val REACH = 2.6f

    /** One sweep, from one side to the other, in-out; the first, from the centre, takes as long. */
    const val SWEEP_MILLIS = 380
}

/** Connecting's Checking: the eyes come back to the centre and narrow, checking. */
object Narrow {
    const val MILLIS = 300

    /** The share of their height the eyes narrow to. */
    const val SQUINT = 0.72f
    val easing = MarkEasing.EmphasizedDecelerate
}

/** Connecting's Securing: the eyes open to full, and blink once (the blink's times are the reference player's). */
object Open {
    const val MILLIS = 200
    val easing = MarkEasing.EmphasizedDecelerate
    val blink = listOf(MarkKey(0, 1f, MarkEasing.Hold), MarkKey(50, 1f), MarkKey(110, 0.1f), MarkKey(190, 1f))
}

/** Verify: the eyes look down at the code and stay there. */
object LookDown {
    val eyeY =
        listOf(MarkKey(0, 0f, MarkEasing.Hold), MarkKey(200, 0f, MarkEasing.EmphasizedDecelerate), MarkKey(500, 2.6f))

    /** The look's clock runs to its last key. */
    val CLOCK_MILLIS: Int = eyeY.last().ms
}

/**
 * The eyes [ms] into the search: from the centre to one side over the first sweep, then from side to side, a sweep each
 * [Search.SWEEP_MILLIS], however long it runs.
 */
fun searchingAt(ms: Float): MarkPose {
    require(ms >= 0f) { "A search runs from 0 ms, not $ms." }
    val sweep = Search.SWEEP_MILLIS.toFloat()
    if (ms < sweep) return MarkPose(eyeX = 0f - Search.REACH * MarkEasing.InOut.transform(ms / sweep))
    val sweeps = (ms - sweep) / sweep
    val done = floor(sweeps)
    // The eyes stand on the left after the first sweep and every second one after it.
    val from = if (done.toLong() % 2L == 0L) -Search.REACH else Search.REACH
    return MarkPose(eyeX = from - 2f * from * MarkEasing.InOut.transform(sweeps - done))
}

/** The eyes [ms] into Checking, from where they were, [from]: back to the centre and narrowed. */
fun checkingAt(
    ms: Float,
    from: MarkPose,
): MarkPose {
    val shown = Narrow.easing.transform((ms / Narrow.MILLIS).coerceIn(0f, 1f))
    // The eyes' place less the way they have come, which ends on +0 from either side, as the rest's centre is.
    return MarkPose(
        eyeX = from.eyeX - from.eyeX * shown,
        squint = from.squint + (Narrow.SQUINT - from.squint) * shown,
    )
}

/** The eyes [ms] into Securing, from where they were, [from]: back to the centre, open, and one blink. */
fun securingAt(
    ms: Float,
    from: MarkPose,
): MarkPose {
    val shown = Open.easing.transform((ms / Open.MILLIS).coerceIn(0f, 1f))
    return MarkPose(
        eyeX = from.eyeX - from.eyeX * shown,
        squint = from.squint + (1f - from.squint) * shown,
        blink = sample(Open.blink, ms),
    )
}

/** The eyes [ms] into Verify's look at the code. */
fun lookingDownAt(ms: Float): MarkPose = MarkPose(eyeY = sample(LookDown.eyeY, ms))
