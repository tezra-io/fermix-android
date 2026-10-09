package io.tezra.fermix.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.Countdown
import io.tezra.fermix.design.ExpressiveMotion
import io.tezra.fermix.design.FermixMark
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.LookDown
import io.tezra.fermix.design.MarkPose
import io.tezra.fermix.design.countdownPulseAt
import io.tezra.fermix.design.idling
import io.tezra.fermix.design.lookingDownAt
import io.tezra.fermix.design.rememberMarkMoment
import io.tezra.fermix.design.rememberMoment
import kotlin.random.Random

// The M51 update's reference player's Verify above the title: the mark 56 dp across (7.3), 32 dp under the status bar,
// which the page's own 16 dp makes 16 here, centred as Pair centres its diagram, and the title 20 dp under it. Below
// the title, the visual canon's: the code 36 dp under it, the sentence 20 dp under the code, and the countdown 28 dp
// under that.
private val MARK = 56.dp
private val MARK_TOP = 16.dp
private val TITLE_TOP = 20.dp
private val SAS_TOP = 36.dp
private val BODY_TOP = 20.dp
private val COUNTDOWN_TOP = 28.dp

/** What Verify shows: the code, the seconds left of the pairing window, and the name the phone went as. */
@Immutable
data class VerifyUi(
    val sas: String,
    val secondsLeft: Int,
    val deviceName: String,
) {
    /** Leaves the SAS out, so no log line that prints the screen's state carries the code. */
    override fun toString(): String = "VerifyUi(secondsLeft=$secondsLeft, deviceName=$deviceName)"
}

/**
 * Step 5 (design section 13.3): the Fermix mark, idling, its eyes looking down at the code (the M51 update's 7.4), "Do
 * the codes match?", the code landing on the expressive scheme (section 13.1 allows it here), "Approve on your computer
 * if they match.", the countdown, the name `pair_request` carried, and "Cancel". The name is read-only here: it went
 * out as the handshake completed. The ring depletes between the seconds and marks 30 s and 10 s left. The mark's
 * blinks are drawn from [random].
 */
@Composable
fun VerifyScreen(
    state: VerifyUi,
    onCancel: () -> Unit,
    random: Random,
    modifier: Modifier = Modifier,
) {
    // No moment of its own for the mark: the idle alone, over the eyes' look.
    val idle = rememberMarkMoment(length = 0, played = true, random = random)
    val eyes = rememberVerifyEyes()
    val ring = rememberRing(state.secondsLeft)
    VerifyAt(pose = { eyes().idling(idle.idle) }, state = state, onCancel = onCancel, modifier = modifier, ring = ring)
}

/**
 * The mark's eyes on Verify: they look down at the code once, from 200 to 500 ms, played as the look starts, so they
 * are down after a rotation, even one mid-look.
 */
@Composable
internal fun rememberVerifyEyes(): () -> MarkPose {
    var looked by rememberSaveable { mutableStateOf(false) }
    val look = rememberMoment(LookDown.CLOCK_MILLIS, looked)
    LaunchedEffect(Unit) { looked = true }
    return { lookingDownAt(look.ms) }
}

/** The countdown ring as it moves: its [pose], read as it is drawn, and whether the clock is telling TalkBack now. */
@Stable
internal class RingState(
    val pose: () -> RingPose,
    val announcing: Boolean,
) {
    companion object {
        /** The ring standing at [secondsLeft], its stroke at rest, the clock telling TalkBack nothing. */
        fun standing(secondsLeft: Int): RingState = RingState(pose = { RingPose.at(secondsLeft) }, announcing = false)
    }
}

/**
 * The ring at [secondsLeft] (the M51 update's 7.4): it depletes linearly from each second to the next over the second,
 * or stands at each second under Remove animations; at 30 s and at 10 s left, as the clock shows them, it thickens and
 * thins once and the clock tells TalkBack, once each: the seconds already marked are saved, so a rotation marks none
 * again, and a screen that opens past a mark never marks it.
 */
@Composable
internal fun rememberRing(secondsLeft: Int): RingState {
    val reduced = LocalReducedMotion.current
    val sweep = remember { Animatable(RingPose.at(secondsLeft).left) }
    LaunchedEffect(secondsLeft, reduced) {
        sweep.snapTo(RingPose.at(secondsLeft).left)
        if (reduced || secondsLeft == 0) return@LaunchedEffect
        sweep.animateTo(RingPose.at(secondsLeft - 1).left, tween(Countdown.TICK_MILLIS, easing = Countdown.tickEasing))
    }
    var marked by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    // Read once a second, as the second turns: marking it below does not end it.
    val marking = remember(secondsLeft) { secondsLeft in Countdown.marks && secondsLeft !in marked }
    // Each second's pulse, played only on a second it marks, and over from the start on any other.
    val pulse = key(secondsLeft) { rememberMoment(Countdown.PULSE_MILLIS, played = !marking) }
    LaunchedEffect(secondsLeft) { if (marking) marked = marked + secondsLeft }
    return RingState(pose = { RingPose(sweep.value, thick = countdownPulseAt(pulse.ms)) }, announcing = marking)
}

/**
 * Verify with the mark in [pose] and the countdown's ring in [ring], as the screen or a preview gives them. The mark is
 * the shared one, Connecting's shrinking into it as Verify comes in (the M51 update's 7.3).
 */
@Composable
internal fun VerifyAt(
    pose: () -> MarkPose,
    state: VerifyUi,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    ring: RingState = RingState.standing(state.secondsLeft),
) {
    val colors = LocalFermixColors.current
    OnboardingPage(
        modifier = modifier,
        actions = {
            ShownAs(deviceName = state.deviceName, onRename = null)
            SecondaryAction(text = stringResource(R.string.onboarding_verify_cancel), onClick = onCancel)
        },
    ) {
        SharedMark { mark ->
            FermixMark(pose, MARK, Modifier.padding(top = MARK_TOP).align(Alignment.CenterHorizontally).then(mark))
        }
        Text(
            text = stringResource(R.string.onboarding_verify_title),
            style = FermixType.headline,
            color = colors.ink,
            modifier = Modifier.padding(top = TITLE_TOP),
        )
        ExpressiveMotion {
            SasCode(sas = state.sas, modifier = Modifier.padding(top = SAS_TOP))
        }
        Text(
            text = stringResource(R.string.onboarding_verify_body),
            style = FermixType.body,
            color = colors.ink,
            modifier = Modifier.padding(top = BODY_TOP),
        )
        CountdownRing(
            secondsLeft = state.secondsLeft,
            ring = ring.pose,
            announcing = ring.announcing,
            modifier = Modifier.padding(top = COUNTDOWN_TOP),
        )
    }
}
