package io.tezra.fermix.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.ConnectingLine
import io.tezra.fermix.design.FermixMark
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LEAST_ALPHA
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.MarkPose
import io.tezra.fermix.design.Moment
import io.tezra.fermix.design.Narrow
import io.tezra.fermix.design.Open
import io.tezra.fermix.design.ScreenChange
import io.tezra.fermix.design.checkingAt
import io.tezra.fermix.design.idling
import io.tezra.fermix.design.rememberLoop
import io.tezra.fermix.design.rememberMarkMoment
import io.tezra.fermix.design.rememberMoment
import io.tezra.fermix.design.searchingAt
import io.tezra.fermix.design.securingAt
import kotlin.random.Random

// The visual canon's Connecting, centred: the line 40 dp under the mark, 88 dp across (the M51 update's 7.3), in a box
// two of its lines tall, the reference player's 64 dp, or as tall as its tallest line when that is taller, so that
// the mark and the steps hold still as it changes (7.4); then, the player's 12 dp under the box, the three steps, 8 dp
// tall and 8 dp apart: the current one a 20 dp pill, the others 8 dp dots, done in the secondary text's grey, the
// current one in the ink, those to come in the hairline grey (7.4).
private val MARK = 88.dp
private val LINE_TOP = 40.dp
private val LINE_BOX = 64.dp
private val STEP = 8.dp
private val PILL = 20.dp
private val STEPS_TOP = 12.dp

/** The three steps the dots count: reaching (Tailscale among it), checking, securing. */
private const val STEPS = 3

/** The steps' row, tagged for the tests, which hold it still as the line changes; a screen reader passes over it. */
internal const val STEPS_KEY = "connecting-steps"

/**
 * Step 4 (design section 13.3): the Fermix mark, idling, its eyes searching for the computer, then checking, then
 * opening (the M51 update's 7.4), over one line that advances, "Reaching suj-mbp…", "Checking it's really your
 * machine…", "Securing the line…", or "Trying Tailscale…" once reaching has taken 4 s, with the three steps under it.
 * The line is a polite live region, so TalkBack reads each. Back, the gesture, ends the attempt; the screen has no
 * button. The mark's blinks are drawn from [random].
 */
@Composable
fun ConnectingScreen(
    phase: ConnectingPhase,
    host: String,
    random: Random,
    modifier: Modifier = Modifier,
) {
    // No moment of its own: the idle alone, over the eyes.
    val idle = rememberMarkMoment(length = 0, played = true, random = random)
    val eyes = rememberConnectingEyes(phase)
    ConnectingAt(pose = { eyes().idling(idle.idle) }, phase = phase, host = host, modifier = modifier)
}

/** The eyes of the latest composition, as a function of their clocks, which the next phase's eyes set off from. */
private class LatestEyes {
    var eyes: () -> MarkPose = { MarkPose.Rest }
}

/**
 * The mark's eyes as Connecting's [phase] goes on (the M51 update's 7.4), read as they are drawn: sweeping side to side
 * while it reaches and tries Tailscale, the search a loop that ends with it; back from where they were to the centre
 * and narrowed at Checking; open again with one blink at Securing. Each turn is played as it starts: a rotation, even
 * mid-turn, shows it done. Under Remove animations they hold still at the centre, open.
 */
@Composable
internal fun rememberConnectingEyes(phase: ConnectingPhase): () -> MarkPose {
    val searching = phase == ConnectingPhase.REACHING || phase == ConnectingPhase.TRYING_TAILSCALE
    val search = rememberLoop(running = searching)
    val latest = remember { LatestEyes() }
    // Where the eyes are as the phase changes: the last phase's eyes, read now, where its clocks stopped.
    val from = remember(phase) { latest.eyes() }
    val turn = if (searching) null else key(phase) { rememberTurn(phase) }
    val reduced = LocalReducedMotion.current
    val eyes = { eyesAt(phase, reduced, search.ms, turn?.ms, from) }
    SideEffect { latest.eyes = eyes }
    return eyes
}

/** The eyes [searchMs] into the search or [turnMs] into the phase's turn from [from], or at rest when [reduced]. */
private fun eyesAt(
    phase: ConnectingPhase,
    reduced: Boolean,
    searchMs: Float,
    turnMs: Float?,
    from: MarkPose,
): MarkPose =
    when {
        reduced -> MarkPose.Rest
        turnMs == null -> searchingAt(searchMs)
        phase == ConnectingPhase.CHECKING -> checkingAt(turnMs, from)
        else -> securingAt(turnMs, from)
    }

/**
 * The eyes' turn at [phase]: Checking's narrowing or Securing's opening, played once, as it starts; the search has
 * none.
 */
@Composable
private fun rememberTurn(phase: ConnectingPhase): Moment {
    val length = if (phase == ConnectingPhase.SECURING) Open.MILLIS else Narrow.MILLIS
    var played by rememberSaveable { mutableStateOf(false) }
    val turn = rememberMoment(length, played)
    LaunchedEffect(Unit) { played = true }
    return turn
}

/**
 * Connecting with the mark in [pose], as the screen or a preview gives it. The mark is the shared one, which moves on
 * into Verify's (the M51 update's 7.3).
 */
@Composable
internal fun ConnectingAt(
    pose: () -> MarkPose,
    phase: ConnectingPhase,
    host: String,
    modifier: Modifier = Modifier,
) {
    OnboardingPage(modifier = modifier, centred = true, actions = {}) {
        SharedMark { mark -> FermixMark(pose, MARK, mark) }
        LineBox(phase = phase, host = host, modifier = Modifier.padding(top = LINE_TOP))
        StepDots(current = stepOf(phase), modifier = Modifier.padding(top = STEPS_TOP).testTag(STEPS_KEY))
    }
}

/**
 * The line in its box: every phase's line laid out unseen and unread, so that the box stands as tall as the tallest at
 * any font size, and the line on top, changing by a vertical fade through, or simply changing under Remove animations.
 */
@Composable
private fun LineBox(
    phase: ConnectingPhase,
    host: String,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val rise = with(LocalDensity.current) { ConnectingLine.rise.roundToPx() }
    Box(modifier = modifier.fillMaxWidth().heightIn(min = LINE_BOX), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.alpha(0f).clearAndSetSemantics {}) {
            for (each in ConnectingPhase.entries) Line(each, host)
        }
        val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        if (reduced) {
            Line(phase, host, live)
            return@Box
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = { lineThrough(rise) },
            contentAlignment = Alignment.TopCenter,
            label = "connecting line",
        ) { shown ->
            Line(shown, host, live)
        }
    }
}

@Composable
private fun Line(
    phase: ConnectingPhase,
    host: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = connectingLine(phase, host),
        style = FermixType.headline,
        color = LocalFermixColors.current.ink,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The line's vertical fade through, on the screen changes' times: the old line rises [rise] px as it fades out, and the
 * new one rises from [rise] px below as it fades in after it, drawn from the first frame so TalkBack reaches it. The
 * box holds its size, and clips neither.
 */
private fun lineThrough(rise: Int): ContentTransform {
    val coming = tween<Float>(ScreenChange.IN_MILLIS, ScreenChange.OUT_MILLIS, ScreenChange.inEasing)
    val going = tween<Float>(ScreenChange.OUT_MILLIS, easing = ScreenChange.outEasing)
    val comingUp = tween<IntOffset>(ScreenChange.IN_MILLIS, ScreenChange.OUT_MILLIS, ScreenChange.inEasing)
    val goingUp = tween<IntOffset>(ScreenChange.OUT_MILLIS, easing = ScreenChange.outEasing)
    val enter = fadeIn(coming, initialAlpha = LEAST_ALPHA) + slideInVertically(comingUp) { rise }
    val exit = fadeOut(going) + slideOutVertically(goingUp) { -rise }
    return ContentTransform(enter, exit, sizeTransform = null)
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

/**
 * The canon's `.steps`, for the eye: the line says the same to TalkBack. The current step's pill grows from a dot, and
 * the last one's shrinks back, on the standard scheme's fastSpatial, or at once under Remove animations.
 */
@Composable
internal fun StepDots(
    current: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    val reduced = LocalReducedMotion.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(STEP)) {
        repeat(STEPS) { step ->
            val target = if (step == current) PILL else STEP
            val moving by animateDpAsState(target, ConnectingLine.stepSpring.spec(reduced), label = "step")
            val width = if (reduced) target else moving
            val fill =
                when {
                    step < current -> colors.textSecondary
                    step == current -> colors.ink
                    else -> colors.hairline
                }
            Box(modifier = Modifier.size(width, STEP).background(fill, CircleShape))
        }
    }
}
