package io.tezra.fermix.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.focusRing
import io.tezra.fermix.design.textButtonColors

/**
 * Section 9.2's question over [key]'s screen while it is on top, for a link whose daemon this phone is
 * paired with already; the answer reaches the ceremony driver once, from the screen on top.
 */
@Composable
internal fun AlreadyPairedQuestion(
    viewModel: OnboardingViewModel,
    key: OnboardingKey,
) {
    val ui by viewModel.ui.collectAsState()
    val stack by viewModel.stack.collectAsState()
    val title = ui.alreadyPaired
    if (title == null || topOf(stack) != key) return
    AlreadyPairedDialog(title) { yes ->
        val asked = viewModel.ui.value.alreadyPaired != null
        if (asked && topOf(viewModel.stack.value) == key) viewModel.ceremony.pairAgain.answer(yes)
    }
}

/**
 * "already paired; pair again to replace this phone's key?" (design section 9.2) about the row titled
 * [title]: "Pair again" answers yes, and "Cancel" or leaving the dialog answers no.
 */
@Composable
fun AlreadyPairedDialog(
    title: String,
    onAnswer: (Boolean) -> Unit,
) {
    val colors = LocalFermixColors.current
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(text = title, style = FermixType.headline) },
        text = {
            Text(
                text = stringResource(R.string.onboarding_already_paired),
                style = FermixType.bodyMedium,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAnswer(true) },
                modifier = Modifier.focusRing(FermixShapes.button),
                colors = textButtonColors(colors),
            ) {
                Text(text = stringResource(R.string.onboarding_already_paired_yes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAnswer(false) },
                modifier = Modifier.focusRing(FermixShapes.button),
                colors = textButtonColors(colors),
            ) {
                Text(text = stringResource(R.string.onboarding_already_paired_no))
            }
        },
        containerColor = colors.tonalSolid,
    )
}
