package io.tezra.fermix.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

private const val CLOSE = 1e-3f

/**
 * The rest of onboarding's motion as data (the M51 update's 7.2 and 7.4), one table per moment, each key held to the
 * update's number, written here as a literal with the row it comes from; where the update names no number, the
 * reference player's (MILESTONE_51_ANDROID_MONOCHROME_AND_WELCOME_MOTION.html), with its line.
 */
class OnboardingMotionTest {
    @Test
    fun `emphasized accelerate is the update's curve, as the reference player computes it`() {
        // 7.2: "emphasized accelerate, CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)"; the player's accel (line 115) run
        // through its own cubic-bezier (line 110) at 0.1, 0.25, 0.5, 0.75 and 0.9.
        val at = listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)
        val expected = listOf(0.0055f, 0.0353f, 0.154f, 0.4056f, 0.6833f)
        for ((t, value) in at.zip(expected)) {
            assertEquals(value, MarkEasing.EmphasizedAccelerate.transform(t), CLOSE, "at $t")
        }
    }

    @Test
    fun `the shared axis and the fade through are 7_2's`() {
        // 7.2, forward and back: "slides in from 30 dp ... while the outgoing one slides 30 dp the other way".
        assertEquals(30.dp, ScreenChange.axisShift)
        // "Position moves on the scheme's defaultSpatial spring (standard scheme: damping 0.9, stiffness 700)."
        assertEquals(MotionSpring(dampingRatio = 0.9f, stiffness = 700f), ScreenChange.position)
        // "the outgoing screen fades out in 90 ms (emphasized accelerate); the incoming one fades in over 210 ms after
        // a 90 ms delay", in both patterns; the fade through's incoming "scales from 92% to 100%".
        assertEquals(90, ScreenChange.OUT_MILLIS)
        assertEquals(210, ScreenChange.IN_MILLIS)
        assertSame(MarkEasing.EmphasizedAccelerate, ScreenChange.outEasing)
        // 7.5: "emphasized decelerate for what enters".
        assertSame(MarkEasing.EmphasizedDecelerate, ScreenChange.inEasing)
        assertEquals(0.92f, ScreenChange.FADE_THROUGH_FROM)
    }

    @Test
    fun `Pair's diagram builds as 7_4's table has it`() {
        // 7.4, Pair: Phone "slides in 12 dp from the start side as it fades in, 0 to 350 ms, emphasized decelerate";
        // Computer "the same from the end side, 80 to 430 ms".
        assertEquals(12.dp, PairBuild.slide)
        assertEquals(listOf(0, 350), listOf(PairBuild.PHONE_START, PairBuild.PHONE_START + PairBuild.DEVICE_MILLIS))
        assertEquals(
            listOf(80, 430),
            listOf(PairBuild.COMPUTER_START, PairBuild.COMPUTER_START + PairBuild.DEVICE_MILLIS),
        )
        // Link: "draws from the phone to the computer, 300 to 650 ms, emphasized decelerate".
        assertEquals(listOf(300, 650), listOf(PairBuild.LINK_START, PairBuild.LINK_START + PairBuild.LINK_MILLIS))
        // Lock: "pops from 60% at the link's centre, 600 to 900 ms, with a small overshoot (fastSpatial on the
        // expressive scheme)".
        assertEquals(600, PairBuild.LOCK_START)
        assertEquals(900, PairBuild.CLOCK_MILLIS)
        assertEquals(0.6f, PairBuild.LOCK_FROM)
        assertEquals(FermixMotionScheme.Expressive.fastSpatial, PairBuild.lockSpring)
        assertEquals(0f, deviceShown(0f, PairBuild.PHONE_START))
        assertEquals(MarkEasing.EmphasizedDecelerate.transform(0.5f), deviceShown(175f, PairBuild.PHONE_START), CLOSE)
        assertEquals(1f, deviceShown(350f, PairBuild.PHONE_START))
        assertEquals(0f, deviceShown(80f, PairBuild.COMPUTER_START))
        assertEquals(1f, deviceShown(430f, PairBuild.COMPUTER_START))
        assertEquals(0f, linkDrawn(300f))
        // Link, at the middle of its 300 to 650 ms: "emphasized decelerate".
        assertEquals(MarkEasing.EmphasizedDecelerate.transform(0.5f), linkDrawn(475f), CLOSE)
        assertEquals(1f, linkDrawn(650f))
        assertEquals(0.6f, lockScale(600f), CLOSE)
        assertEquals(1f, lockScale(900f))
        // The overshoot, small: the expressive fastSpatial spring passes 1 on its way.
        val peak = (600..900).maxOf { lockScale(it.toFloat()) }
        assertTrue(peak > 1.01f && peak < 1.1f, "the lock peaks at $peak")
        // The update gives the lock no fade; the player's opacity is clamp01(lk * 1.6), lk the pop's progress
        // (line 290).
        assertEquals(1.6f, PairBuild.LOCK_SHOWN_PACE)
        assertEquals(0f, lockShownAt(0f))
        assertEquals(0f, lockShownAt(600f))
        val popped = (lockScale(650f) - 0.6f) / 0.4f
        assertEquals((popped * 1.6f).coerceIn(0f, 1f), lockShownAt(650f), CLOSE)
        assertEquals(1f, lockShownAt(900f))
    }

    @Test
    fun `Copy shows its check for 1,500 ms, cross-fading 200 ms either way`() {
        // 7.4, Pair, Copy: "the icon cross-fades to a check for 1,500 ms (200 ms fades either way)".
        assertEquals(1_500, CopyCheck.SHOWN_MILLIS)
        assertEquals(200, CopyCheck.FADE_MILLIS)
        // The update names no curve for the cross-fade; the standard easing, as for the grant's check below.
        assertSame(MarkEasing.Standard, CopyCheck.easing)
    }

    @Test
    fun `Scan's reticle settles, breathes, locks on and flashes as 7_4's table has it`() {
        // 7.4, Scan, Enter: "the reticle settles from 108% to 100% as it fades in, 300 ms, emphasized decelerate".
        assertEquals(1.08f, ReticleMotion.ENTER_FROM)
        assertEquals(300, ReticleMotion.ENTER_MILLIS)
        assertEquals(1.08f, reticleEnterAt(0f), CLOSE)
        assertEquals(1f + 0.08f * (1f - MarkEasing.EmphasizedDecelerate.transform(0.5f)), reticleEnterAt(150f), CLOSE)
        assertEquals(1f, reticleEnterAt(300f))
        // "as it fades in": over the same 300 ms, as the player's opacity follows enterK (line 336).
        assertEquals(0f, reticleShownAt(0f))
        assertEquals(MarkEasing.EmphasizedDecelerate.transform(0.5f), reticleShownAt(150f), CLOSE)
        assertEquals(1f, reticleShownAt(300f))
        // Searching: "breathes between 100% and 102%, 1,600 ms a cycle, in-out".
        assertEquals(listOf(0 to 1f, 800 to 1.02f, 1_600 to 1f), ReticleMotion.breath.map { it.ms to it.value })
        assertTrue(ReticleMotion.breath.dropLast(1).all { it.easing === MarkEasing.InOut })
        assertEquals(1f, reticleBreathAt(0f))
        assertEquals(1.02f, reticleBreathAt(800f), CLOSE)
        assertEquals(1f, reticleBreathAt(1_600f), CLOSE)
        assertEquals(1.02f, reticleBreathAt(1_600f * 37 + 800f), CLOSE)
        // A Fermix code: "to 66% when the bounds are not known, 180 ms on fastSpatial".
        assertEquals(0.66f, ReticleMotion.LOCKED)
        assertEquals(FermixMotionScheme.Standard.fastSpatial, ReticleMotion.lockSpring)
        // "A white fill flashes inside it to 14% and out (up in 60 ms, gone by 260 ms)"; the player's (line 338):
        // [[0, 0, "std"], [60, 0.14, "io"], [260, 0]].
        assertEquals(listOf(0 to 0f, 60 to 0.14f, 260 to 0f), ReticleMotion.flash.map { it.ms to it.value })
        assertEquals(listOf(MarkEasing.Standard, MarkEasing.InOut), ReticleMotion.flash.dropLast(1).map { it.easing })
        // "Connecting follows by fade through 250 ms later".
        assertEquals(250, ReticleMotion.LEAVE_AFTER_MILLIS)
    }

    @Test
    fun `a refused code's hint cross-fades and shakes once, 6 dp, three cycles dying over 300 ms`() {
        // 7.4, Scan, Not a Fermix code: "(200 ms cross-fade) and shakes once: 6 dp, three cycles, dying away over 300
        // ms"; the player's shake (line 340): sin(u · 6π) · (1 − u) · 6 dp, u over 300 ms.
        assertEquals(200, HintShake.FADE_MILLIS)
        assertEquals(6.dp, HintShake.reach)
        assertEquals(300, HintShake.MILLIS)
        assertEquals(3, HintShake.CYCLES)
        for (ms in 0..300 step 10) {
            val u = ms / 300f
            assertEquals(sin(u * PI.toFloat() * 6f) * (1f - u), hintShakeAt(ms.toFloat()), CLOSE, "at $ms ms")
        }
        assertEquals(0f, hintShakeAt(300f))
        assertEquals(0f, hintShakeAt(5_000f))
        // Three cycles: six times past the rest, one at each half cycle's end but the last.
        val crossings = (1..299).count { hintShakeAt(it - 1f) * hintShakeAt(it.toFloat()) < 0f }
        assertEquals(5, crossings)
    }

    @Test
    fun `Connecting's line changes by a vertical fade through, and its pill moves on fastSpatial`() {
        // 7.4, Connecting: "the old line rises 8 dp and fades out in 90 ms (emphasized accelerate), the new one rises
        // from 8 dp below and fades in over 210 ms after 90 ms (emphasized decelerate)".
        assertEquals(8.dp, ConnectingLine.rise)
        assertEquals(90, ScreenChange.OUT_MILLIS)
        assertEquals(210, ScreenChange.IN_MILLIS)
        // "the width moving on fastSpatial".
        assertEquals(FermixMotionScheme.Standard.fastSpatial, ConnectingLine.stepSpring)
    }

    @Test
    fun `Verify's ring depletes second by second and thickens at 30 s and at 10 s left`() {
        // 7.4, Verify, the ring: "animate the sweep linearly between the second ticks"; "At 30 s and at 10 s left the
        // stroke thickens from 3 to 5 dp and back once (300 ms)".
        assertEquals(1_000, Countdown.TICK_MILLIS)
        assertSame(LinearEasing, Countdown.tickEasing)
        assertEquals(listOf(30, 10), Countdown.marks)
        assertEquals(5.dp, Countdown.thickTo)
        assertEquals(300, Countdown.PULSE_MILLIS)
        // The player's (line 424): 3 + 2 · sin(π u), u over 300 ms.
        for (ms in 0..300 step 25) {
            val u = ms / 300f
            assertEquals(sin(u * PI.toFloat()), countdownPulseAt(ms.toFloat()), CLOSE, "at $ms ms")
        }
        assertEquals(1f, countdownPulseAt(150f), CLOSE)
        assertEquals(0f, countdownPulseAt(300f), CLOSE)
        assertEquals(0f, countdownPulseAt(1_000f))
    }

    @Test
    fun `Name's avatar takes a new tint over 200 ms`() {
        // 7.4, Name: "the tinted avatar's colour cross-fading in 200 ms when the tint changes".
        assertEquals(200, NameMotion.TINT_MILLIS)
    }

    @Test
    fun `the bell swings once from its top, 14, -10, 6, -3 degrees between 300 and 1,000 ms`() {
        // 7.4, Notifications, Enter: "swings once from its top: 0, 14, −10, 6, −3, 0 degrees between 300 and 1,000
        // ms, in-out on each swing"; the swings' times are the player's (line 464): 420, 560, 700, 840.
        val keys = BellSwing.angle.map { it.ms to it.value }
        assertEquals(listOf(0 to 0f, 300 to 0f, 420 to 14f, 560 to -10f, 700 to 6f, 840 to -3f, 1_000 to 0f), keys)
        assertSame(MarkEasing.Hold, BellSwing.angle.first().easing)
        assertTrue(
            BellSwing.angle
                .drop(1)
                .dropLast(1)
                .all { it.easing === MarkEasing.InOut },
        )
        // The swing's clock runs to the table's last key, read from it.
        assertEquals(1_000, BellSwing.CLOCK_MILLIS)
        assertEquals(BellSwing.angle.last().ms, BellSwing.CLOCK_MILLIS)
        // Granted: "the bell cross-fades to a check (200 ms, standard) and onboarding ends 400 ms later".
        assertEquals(200, BellSwing.CHECK_MILLIS)
        assertSame(MarkEasing.Standard, BellSwing.checkEasing)
        assertEquals(400, BellSwing.END_AFTER_MILLIS)
    }

    @Test
    fun `the new Fermix's row rises 12 dp as it fades in, 300 ms after 250`() {
        // 7.4, Leaving onboarding: "rises 12 dp as it fades in (the app's bubble-insert rise,
        // FermixMotion.bubbleInsertRise), 300 ms, emphasized decelerate, after 250 ms".
        assertEquals(12.dp, Arrival.rise)
        assertEquals(FermixMotion.bubbleInsertRise, Arrival.rise)
        assertEquals(250, Arrival.DELAY_MILLIS)
        assertEquals(300, Arrival.MILLIS)
        assertEquals(0f, arrivalAt(250f))
        assertEquals(MarkEasing.EmphasizedDecelerate.transform(0.5f), arrivalAt(400f), CLOSE)
        assertEquals(1f, arrivalAt(550f))
    }

    @Test
    fun `a failure's disc settles from 94 percent, and the security edge draws across`() {
        // 7.4, Failures, the disc: "settles from 94% to 100%, 90 to 350 ms, emphasized decelerate".
        assertEquals(0.94f, FailureEntrance.DISC_FROM)
        assertEquals(listOf(90, 350), listOf(FailureEntrance.DISC_START, FailureEntrance.DISC_END))
        assertEquals(0.94f, failureDiscAt(90f))
        assertEquals(0.94f + 0.06f * MarkEasing.EmphasizedDecelerate.transform(0.5f), failureDiscAt(220f), CLOSE)
        assertEquals(1f, failureDiscAt(350f))
        // "Refusal (REJECT) for a refusal, as today, as it lands": the update gives no time; the player's (line 507)
        // is 200 ms, where emphasized decelerate has the disc all but landed.
        assertEquals(200, FailureEntrance.REFUSAL_MILLIS)
        assertTrue(failureDiscAt(200f) > 0.995f)
        // Wrong machine: "The 4 dp red edge along the top draws across from the start side, 100 to 400 ms, emphasized
        // decelerate."
        assertEquals(listOf(100, 400), listOf(FailureEntrance.EDGE_START, FailureEntrance.EDGE_END))
        assertEquals(0f, failureEdgeAt(100f))
        assertEquals(MarkEasing.EmphasizedDecelerate.transform(0.5f), failureEdgeAt(250f), CLOSE)
        assertEquals(1f, failureEdgeAt(400f))
        // The entrance's clock runs to the edge's end, read from it.
        assertEquals(400, FailureEntrance.CLOCK_MILLIS)
        assertEquals(FailureEntrance.EDGE_END, FailureEntrance.CLOCK_MILLIS)
    }

    @Test
    fun `a spring sampled on a clock starts where it is told and ends at its target`() {
        val spring = FermixMotionScheme.Standard.fastSpatial
        assertEquals(1f, spring.valueAt(0f, from = 1f, to = 0.66f))
        assertEquals(1f, spring.valueAt(-50f, from = 1f, to = 0.66f))
        assertEquals(0.66f, spring.valueAt(2_000f, from = 1f, to = 0.66f), CLOSE)
        // Damped at 0.9 it comes within a hundredth of the way in about 180 ms, the update's "180 ms on fastSpatial".
        assertEquals(0.66f, spring.valueAt(180f, from = 1f, to = 0.66f), 0.01f)
    }
}
