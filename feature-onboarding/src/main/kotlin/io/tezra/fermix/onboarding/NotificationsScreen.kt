package io.tezra.fermix.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * Step 7 (design section 13.3), when the daemon has push: "Know when Fermix finishes." and the one line
 * that says how, "Allow notifications", which asks the system, and "Not now". It is asked once.
 */
@Composable
fun NotificationsScreen(
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        IconPage(
            icon = R.drawable.ic_onboarding_bell,
            alert = false,
            title = stringResource(R.string.onboarding_notifications_title),
            body = stringResource(R.string.onboarding_notifications_body),
        ) {
            PrimaryAction(text = stringResource(R.string.onboarding_notifications_allow), onClick = onAllow)
            SecondaryAction(text = stringResource(R.string.onboarding_notifications_not_now), onClick = onNotNow)
        }
    }
}
