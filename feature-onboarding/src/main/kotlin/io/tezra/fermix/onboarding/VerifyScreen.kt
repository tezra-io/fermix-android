package io.tezra.fermix.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.ExpressiveMotion
import io.tezra.fermix.design.FermixMark
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.MarkPose
import io.tezra.fermix.design.idling
import io.tezra.fermix.design.rememberMarkMoment
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
 * Step 5 (design section 13.3): the Fermix mark, resting and idling (the M51 update's 7.3; its eyes looking at the code
 * are 7.4's), "Do the codes match?", the code landing on the expressive scheme (section 13.1 allows it here), "Approve
 * on your computer if they match.", the countdown, the name `pair_request` carried, and "Cancel". The name is read-only
 * here: it went out as the handshake completed. The mark's blinks are drawn from [random].
 */
@Composable
fun VerifyScreen(
    state: VerifyUi,
    onCancel: () -> Unit,
    random: Random,
    modifier: Modifier = Modifier,
) {
    // No moment of its own: the idle alone.
    val idle = rememberMarkMoment(length = 0, played = true, random = random)
    VerifyAt(pose = { MarkPose.Rest.idling(idle.idle) }, state = state, onCancel = onCancel, modifier = modifier)
}

/** Verify with the mark in [pose], as the screen or a preview gives it. */
@Composable
internal fun VerifyAt(
    pose: () -> MarkPose,
    state: VerifyUi,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    OnboardingPage(
        modifier = modifier,
        actions = {
            ShownAs(deviceName = state.deviceName, onRename = null)
            SecondaryAction(text = stringResource(R.string.onboarding_verify_cancel), onClick = onCancel)
        },
    ) {
        FermixMark(pose, MARK, Modifier.padding(top = MARK_TOP).align(Alignment.CenterHorizontally))
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
        CountdownRing(secondsLeft = state.secondsLeft, modifier = Modifier.padding(top = COUNTDOWN_TOP))
    }
}
