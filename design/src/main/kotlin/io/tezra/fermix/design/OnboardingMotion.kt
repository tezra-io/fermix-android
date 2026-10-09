package io.tezra.fermix.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

// The rest of onboarding's motion as data (the M51 update's 7.2 and 7.4), beside the mark's own tables (MarkMotion.kt,
// MarkEyes.kt): one table per moment, its times in ms on the moment's clock, and the small functions that read them.
// Here the screen changes and the screens before the ceremony, Pair and Scan; CeremonyMotion.kt holds the rest.
// OnboardingMotionTest holds every key to the update, and to its reference player where the update names no number.

/** How far [ms] is from [start] into a stretch of [length] ms, from 0 before it to 1 once it is over. */
internal fun progress(
    ms: Float,
    start: Int,
    length: Int,
): Float = ((ms - start) / length).coerceIn(0f, 1f)

/** Moving between onboarding's screens (7.2): Material's shared axis X between the flow's steps, and fade through. */
object ScreenChange {
    /** The shared axis: the incoming screen comes this far from its side, the outgoing goes this far the other way. */
    val axisShift: Dp = 30.dp

    /** Position moves on the standard scheme's defaultSpatial spring. */
    val position: MotionSpring = FermixMotionScheme.Standard.defaultSpatial

    /** The outgoing screen fades out over this long, on [outEasing]. */
    const val OUT_MILLIS = 90
    val outEasing = MarkEasing.EmphasizedAccelerate

    /** The incoming screen fades in over this long once the outgoing has gone, on [inEasing]. */
    const val IN_MILLIS = 210
    val inEasing = MarkEasing.EmphasizedDecelerate

    /** Fade through: the incoming screen scales from this to its size as it fades in. */
    const val FADE_THROUGH_FROM = 0.92f
}

/** Pair's diagram builds once (7.4): the phone, then the computer, the link draws, the lock pops. */
object PairBuild {
    /** Each device slides in this far as it fades in: the phone from the start side, the computer from the end. */
    val slide: Dp = 12.dp
    const val PHONE_START = 0
    const val COMPUTER_START = 80
    const val DEVICE_MILLIS = 350
    const val LINK_START = 300
    const val LINK_MILLIS = 350

    /** The lock pops from [LOCK_FROM] of its size at the link's centre, on [lockSpring], with a small overshoot. */
    const val LOCK_START = 600
    const val LOCK_FROM = 0.6f
    val lockSpring: MotionSpring = FermixMotionScheme.Expressive.fastSpatial

    /** The build's clock runs to the lock's end. */
    const val CLOCK_MILLIS = 900

    /** The lock is whole once it has popped 1 / this of the way: the reference player's opacity. */
    const val LOCK_SHOWN_PACE = 1.6f
}

/** How far the device starting at [start] has come in at [ms], on emphasized decelerate. */
fun deviceShown(
    ms: Float,
    start: Int,
): Float = MarkEasing.EmphasizedDecelerate.transform(progress(ms, start, PairBuild.DEVICE_MILLIS))

/** How much of the link from the phone to the computer is drawn at [ms]. */
fun linkDrawn(ms: Float): Float =
    MarkEasing.EmphasizedDecelerate.transform(progress(ms, PairBuild.LINK_START, PairBuild.LINK_MILLIS))

/** The lock's scale at [ms]: from 60 % on the spring, whole once the clock is over. */
fun lockScale(ms: Float): Float =
    if (ms >= PairBuild.CLOCK_MILLIS) {
        1f
    } else {
        PairBuild.lockSpring.valueAt(ms - PairBuild.LOCK_START, from = PairBuild.LOCK_FROM, to = 1f)
    }

/** How much of the lock is shown at [ms]: none before it pops, whole a little before it has. */
fun lockShownAt(ms: Float): Float {
    val popped = (lockScale(ms) - PairBuild.LOCK_FROM) / (1f - PairBuild.LOCK_FROM)
    return (popped * PairBuild.LOCK_SHOWN_PACE).coerceIn(0f, 1f)
}

/** Pair's Copy: the icon cross-fades to a check, on [easing], and back once the check has shown so long. */
object CopyCheck {
    const val SHOWN_MILLIS = 1_500
    const val FADE_MILLIS = 200
    val easing = MarkEasing.Standard
}

/** Scan's reticle (7.4): it settles as it enters, breathes while searching, locks on and flashes. */
object ReticleMotion {
    const val ENTER_FROM = 1.08f
    const val ENTER_MILLIS = 300

    /** One breath, in-out each way, and again, for as long as the scan searches. */
    val breath = listOf(MarkKey(0, 1f), MarkKey(800, 1.02f), MarkKey(1_600, 1f))

    /** A Fermix code: the reticle locks to this, the analyser giving no bounds, on [lockSpring]. */
    const val LOCKED = 0.66f
    val lockSpring: MotionSpring = FermixMotionScheme.Standard.fastSpatial

    /** The white fill's alpha inside the reticle, from the code's reading. */
    val flash = listOf(MarkKey(0, 0f, MarkEasing.Standard), MarkKey(60, 0.14f), MarkKey(260, 0f))

    /** Connecting follows the reading by this long. */
    const val LEAVE_AFTER_MILLIS = 250
}

/** The reticle's scale at [ms] into its entrance. */
fun reticleEnterAt(ms: Float): Float {
    val settled = MarkEasing.EmphasizedDecelerate.transform(progress(ms, 0, ReticleMotion.ENTER_MILLIS))
    return ReticleMotion.ENTER_FROM - (ReticleMotion.ENTER_FROM - 1f) * settled
}

/** How far the reticle has faded in at [ms] into its entrance, as it settles. */
fun reticleShownAt(ms: Float): Float =
    MarkEasing.EmphasizedDecelerate.transform(progress(ms, 0, ReticleMotion.ENTER_MILLIS))

/** The reticle's breath at [ms] into the search. */
fun reticleBreathAt(ms: Float): Float = sample(ReticleMotion.breath, ms % ReticleMotion.breath.last().ms)

/** Scan's hint, for a code that is not a Fermix code: the line cross-fades, and it shakes once. */
object HintShake {
    const val FADE_MILLIS = 200
    val reach: Dp = 6.dp
    const val MILLIS = 300
    const val CYCLES = 3
}

/** The hint's shake at [ms], a share of [HintShake.reach]: [HintShake.CYCLES] cycles dying away, then still. */
fun hintShakeAt(ms: Float): Float {
    if (ms >= HintShake.MILLIS) return 0f
    val u = progress(ms, 0, HintShake.MILLIS)
    return sin(u * PI.toFloat() * 2f * HintShake.CYCLES) * (1f - u)
}
