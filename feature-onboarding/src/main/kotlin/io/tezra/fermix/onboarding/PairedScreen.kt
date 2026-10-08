package io.tezra.fermix.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixMark
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.Hop
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.MarkPose
import io.tezra.fermix.design.hopAt
import io.tezra.fermix.design.idling
import io.tezra.fermix.design.rememberMarkMoment
import kotlin.random.Random

// The mark 88 dp across (the M51 update's 7.3), the title 40 dp under it, and 60 dp under the title, which raises the
// centred group by 30 dp, as the update's reference player has them.
private val MARK = 88.dp
private val TITLE_TOP = 40.dp
private val GROUP_BOTTOM = 60.dp

/**
 * Step 6 (design section 13.3, as the M51 update's section 5 changes it): the Fermix mark's happy hop, "Paired with
 * [host]", "Continue". Nothing else: what was verified is on the Instance screen. The hop is timed from the moment the
 * screen takes the approval, on one clock: the eyes turn into happy arcs, M51's `CONFIRM` (`PairApproved`) plays at
 * 90 ms as the mark leaves the ground, and the title rises in at 250 ms; the happy eyes stay, breathing, their blinks
 * drawn from [random]. It plays once, not again after a rotation; the approval is confirmed once, at once when the
 * hop is not drawn: with Remove animations on, where the mark stands happy at once, and when Paired is restored before
 * the mark left the ground.
 */
@Composable
fun PairedScreen(
    host: String,
    onContinue: () -> Unit,
    random: Random,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var played by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val hop =
        rememberMarkMoment(Hop.CLOCK_MILLIS, played, random) { ms ->
            if (!confirmed && ms >= Hop.LEAVES_GROUND_MILLIS) {
                confirmed = true
                HapticFeedback.perform(view, HapticUse.PairApproved)
            }
        }
    LaunchedEffect(Unit) { played = true }
    // The hop's last frame, made once: from the clock's end on, each frame only breathes and blinks it.
    val settled = remember { hopAt(Hop.CLOCK_MILLIS.toFloat()) }
    PairedAt(
        pose = { (if (hop.ms < Hop.CLOCK_MILLIS) hopAt(hop.ms) else settled).idling(hop.idle) },
        ms = { hop.ms },
        host = host,
        onContinue = onContinue,
        modifier = modifier,
    )
}

/**
 * Paired at one instant of its hop: the mark in [pose], the title where the hop's clock at [ms] has it, the two
 * centred over the actions and raised by half the 60 dp under them, as the reference player stands them.
 */
@Composable
internal fun PairedAt(
    pose: () -> MarkPose,
    ms: () -> Float,
    host: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingPage(
        modifier = modifier,
        centred = true,
        actions = { PrimaryAction(text = stringResource(R.string.onboarding_continue), onClick = onContinue) },
    ) {
        FermixMark(pose, MARK)
        Text(
            text = stringResource(R.string.onboarding_paired_title, host),
            style = FermixType.headline,
            color = LocalFermixColors.current.ink,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .padding(top = TITLE_TOP, bottom = GROUP_BOTTOM)
                    .risingIn(ms, Hop.TITLE_MILLIS, Hop.titleRise),
        )
    }
}
