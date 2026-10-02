package io.tezra.fermix.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.ExpressiveMotion
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors

/** The canon's title 28 dp under the mark, with the centred column's 12 dp gap. */
private val TITLE_TOP = 40.dp

/**
 * Step 6 (design section 13.3), on the expressive scheme: the dots merge with `CONFIRM`, "Paired with
 * [host]", "Continue". Nothing else: what was verified is on the Instance screen. The haptic plays once,
 * not again after a rotation.
 */
@Composable
fun PairedScreen(
    host: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HapticOnce(HapticUse.PairApproved)
    OnboardingPage(
        modifier = modifier,
        centred = true,
        actions = { PrimaryAction(text = stringResource(R.string.onboarding_continue), onClick = onContinue) },
    ) {
        ExpressiveMotion { TwoDotMark(motion = MarkMotion.MERGE) }
        Text(
            text = stringResource(R.string.onboarding_paired_title, host),
            style = FermixType.headline,
            color = LocalFermixColors.current.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = TITLE_TOP),
        )
    }
}
