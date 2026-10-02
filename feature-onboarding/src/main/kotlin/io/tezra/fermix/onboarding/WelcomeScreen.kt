package io.tezra.fermix.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.textButtonColors

// The visual canon's Welcome: the hero 120 dp down, its lines 8 dp apart and the mark 20 dp above them.
private val HERO_TOP = 120.dp
private val HERO_GAP = 8.dp
private val MARK_BELOW = 20.dp

/**
 * Step 1 (design section 13.3): the two-dot mark assembling, the name, the tagline, "Get started", and
 * "Don't have Fermix yet?", which opens the install page. The canon sets that link in 12 / 16, a size the
 * type scale does not have; it takes the scale's supporting text, 14 / 20.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onNoFermix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    OnboardingPage(
        modifier = modifier,
        actions = {
            PrimaryAction(text = stringResource(R.string.onboarding_welcome_get_started), onClick = onGetStarted)
            TextButton(
                onClick = onNoFermix,
                modifier = Modifier.fillMaxWidth().heightIn(min = FermixSpacing.minTarget),
                colors = textButtonColors(colors),
            ) {
                Text(text = stringResource(R.string.onboarding_welcome_no_fermix), style = FermixType.bodyMedium)
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = HERO_TOP),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HERO_GAP),
        ) {
            TwoDotMark(motion = MarkMotion.ASSEMBLE, modifier = Modifier.padding(bottom = MARK_BELOW))
            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                style = FermixType.display,
                color = colors.ink,
            )
            Text(
                text = stringResource(R.string.onboarding_welcome_tagline),
                style = FermixType.body,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
