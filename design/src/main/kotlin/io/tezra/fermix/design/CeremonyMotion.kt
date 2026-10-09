package io.tezra.fermix.design

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

// Onboarding's motion from the ceremony on as data (the M51 update's 7.4), as OnboardingMotion.kt holds the screens
// before it: Connecting, Verify, Name, Notifications, the way out to the Chats list and the failures, one table per
// moment, its times in ms on the moment's clock. OnboardingMotionTest holds every key to the update, and to its
// reference player where the update names no number.

/** Connecting's line changes by a vertical fade through, on [ScreenChange]'s times; the current step is a pill. */
object ConnectingLine {
    /** The old line rises this far as it fades out; the new one rises from this far below. */
    val rise: Dp = 8.dp

    /** The pill's width moves on the standard scheme's fastSpatial. */
    val stepSpring: MotionSpring = FermixMotionScheme.Standard.fastSpatial
}

/** Verify's countdown ring: it depletes second by second, and thickens once at each of [marks]. */
object Countdown {
    /** The sweep moves from one second's to the next's over this long, linearly. */
    const val TICK_MILLIS = 1_000
    val tickEasing: Easing = LinearEasing

    /** The seconds left at which the stroke thickens and TalkBack says the time left. */
    val marks = listOf(30, 10)
    val thickTo: Dp = 5.dp
    const val PULSE_MILLIS = 300
}

/** How far the stroke has thickened at [ms] into a pulse, from 0 to 1 at its middle and back. */
fun countdownPulseAt(ms: Float): Float {
    if (ms < 0f || ms >= Countdown.PULSE_MILLIS) return 0f
    return sin(progress(ms, 0, Countdown.PULSE_MILLIS) * PI.toFloat())
}

/** Name: the tinted avatar takes a new tint over this long. */
object NameMotion {
    const val TINT_MILLIS = 200
}

/** Notifications' bell swings once from its top as the page lands; a grant turns it into a check. */
object BellSwing {
    /** The bell's angle in degrees, in-out on each swing. */
    val angle =
        listOf(
            MarkKey(0, 0f, MarkEasing.Hold),
            MarkKey(300, 0f),
            MarkKey(420, 14f),
            MarkKey(560, -10f),
            MarkKey(700, 6f),
            MarkKey(840, -3f),
            MarkKey(1_000, 0f),
        )

    /** The swing's clock runs to its last key. */
    val CLOCK_MILLIS: Int = angle.last().ms

    /** A grant: the bell cross-fades to a check, and onboarding ends this long after the cross-fade. */
    const val CHECK_MILLIS = 200
    val checkEasing = MarkEasing.Standard
    const val END_AFTER_MILLIS = 400
}

/** Leaving onboarding: the new Fermix's row on the Chats list rises in. */
object Arrival {
    val rise: Dp = FermixMotion.bubbleInsertRise
    const val DELAY_MILLIS = 250
    const val MILLIS = 300
}

/** How far the new Fermix's row has risen in at [ms], on emphasized decelerate. */
fun arrivalAt(ms: Float): Float =
    MarkEasing.EmphasizedDecelerate.transform(progress(ms, Arrival.DELAY_MILLIS, Arrival.MILLIS))

/** A failure's entrance: the disc settles; the security event's edge draws across instead. */
object FailureEntrance {
    const val DISC_FROM = 0.94f
    const val DISC_START = 90
    const val DISC_END = 350

    /** A refusal's `REJECT`, as the disc lands (the reference player's time). */
    const val REFUSAL_MILLIS = 200
    const val EDGE_START = 100
    const val EDGE_END = 400

    /** The entrance's clock runs to the edge's end. */
    const val CLOCK_MILLIS = EDGE_END
}

/** The failure's disc's scale at [ms]. */
fun failureDiscAt(ms: Float): Float {
    val length = FailureEntrance.DISC_END - FailureEntrance.DISC_START
    val settled = MarkEasing.EmphasizedDecelerate.transform(progress(ms, FailureEntrance.DISC_START, length))
    return FailureEntrance.DISC_FROM + (1f - FailureEntrance.DISC_FROM) * settled
}

/** How much of the security event's edge is drawn at [ms], from the start side. */
fun failureEdgeAt(ms: Float): Float {
    val length = FailureEntrance.EDGE_END - FailureEntrance.EDGE_START
    return MarkEasing.EmphasizedDecelerate.transform(progress(ms, FailureEntrance.EDGE_START, length))
}
