package io.tezra.fermix.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixMark
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.MarkPose
import io.tezra.fermix.design.idling
import io.tezra.fermix.design.rememberMarkMoment
import kotlin.random.Random

// The visual canon's Connecting, centred: the line 40 dp under the mark, 88 dp across (the M51 update's 7.3), then
// 20 dp lower three 8 dp step dots 8 dp apart: done in the secondary text's grey, the current one in the ink, those to
// come in the hairline grey (the M51 update's 7.4; their shape is Task 19's).
private val MARK = 88.dp
private val LINE_TOP = 40.dp
private val STEP = 8.dp
private val STEPS_TOP = 20.dp

/** The three steps the dots count: reaching (Tailscale among it), checking, securing. */
private const val STEPS = 3

/**
 * Step 4 (design section 13.3): the Fermix mark, resting and idling (the M51 update's 7.3; its eyes searching and
 * checking are 7.4's), over one line that advances, "Reaching suj-mbp…", "Checking it's really your machine…",
 * "Securing the line…", or "Trying Tailscale…" once reaching has taken 4 s, with the three step dots under it. The
 * line is a polite live region, so TalkBack reads each. Back, the gesture, ends the attempt; the screen has no button.
 * The mark's blinks are drawn from [random].
 */
@Composable
fun ConnectingScreen(
    phase: ConnectingPhase,
    host: String,
    random: Random,
    modifier: Modifier = Modifier,
) {
    // No moment of its own: the idle alone.
    val idle = rememberMarkMoment(length = 0, played = true, random = random)
    ConnectingAt(pose = { MarkPose.Rest.idling(idle.idle) }, phase = phase, host = host, modifier = modifier)
}

/** Connecting with the mark in [pose], as the screen or a preview gives it. */
@Composable
internal fun ConnectingAt(
    pose: () -> MarkPose,
    phase: ConnectingPhase,
    host: String,
    modifier: Modifier = Modifier,
) {
    OnboardingPage(modifier = modifier, centred = true, actions = {}) {
        FermixMark(pose, MARK)
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                val fade = tween<Float>(FermixMotion.INDICATOR_CROSS_FADE_MILLIS)
                fadeIn(fade) togetherWith fadeOut(fade)
            },
            label = "connecting line",
        ) { shown ->
            Text(
                text = connectingLine(shown, host),
                style = FermixType.headline,
                color = LocalFermixColors.current.ink,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .padding(top = LINE_TOP)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        StepDots(current = stepOf(phase), modifier = Modifier.padding(top = STEPS_TOP))
    }
}

@Composable
private fun connectingLine(
    phase: ConnectingPhase,
    host: String,
): String =
    when (phase) {
        ConnectingPhase.REACHING -> stringResource(R.string.onboarding_connecting_reaching, host)
        ConnectingPhase.TRYING_TAILSCALE -> stringResource(R.string.onboarding_connecting_tailscale)
        ConnectingPhase.CHECKING -> stringResource(R.string.onboarding_connecting_checking)
        ConnectingPhase.SECURING -> stringResource(R.string.onboarding_connecting_securing)
    }

/** The step [phase] is: Tailscale is still the first step, reaching. */
fun stepOf(phase: ConnectingPhase): Int =
    when (phase) {
        ConnectingPhase.REACHING, ConnectingPhase.TRYING_TAILSCALE -> 0
        ConnectingPhase.CHECKING -> 1
        ConnectingPhase.SECURING -> 2
    }

/** The canon's `.steps`, for the eye: the line says the same to TalkBack. */
@Composable
private fun StepDots(
    current: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(STEP)) {
        repeat(STEPS) { step ->
            val fill =
                when {
                    step < current -> colors.textSecondary
                    step == current -> colors.ink
                    else -> colors.hairline
                }
            Box(modifier = Modifier.size(STEP).background(fill, CircleShape))
        }
    }
}
