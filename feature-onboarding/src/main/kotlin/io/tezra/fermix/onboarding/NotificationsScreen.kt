package io.tezra.fermix.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import io.tezra.fermix.design.BellSwing
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.rememberMoment
import io.tezra.fermix.design.sample

/** The bell's top, where it hangs and swings from: the top of its dome on ic_onboarding_bell's 24 grid, (12, 5). */
private val BELL_TOP = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 5f / 24f)

/**
 * Step 7 (design section 13.3), when the daemon has push: "Know when Fermix finishes." and the one line
 * that says how, "Allow notifications", which asks the system, and "Not now". It is asked once. The bell
 * swings once as the page lands; once the system says the owner [granted] it, the bell turns into a check (the
 * M51 update's 7.4), and the entry ends onboarding 400 ms after it has. "Not now" ends at once.
 */
@Composable
fun NotificationsScreen(
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    granted: Boolean = false,
) {
    val swing = rememberBellSwing()
    val check = rememberBellCheck(granted)
    NotificationsAt(swing, check, onAllow, onNotNow, modifier)
}

/**
 * The bell's angle in degrees, read as it is drawn: one swing as the page lands, played as it starts, so a rotation,
 * even mid-swing, shows it hanging still.
 */
@Composable
internal fun rememberBellSwing(): () -> Float {
    var swung by rememberSaveable { mutableStateOf(false) }
    val swing = rememberMoment(BellSwing.CLOCK_MILLIS, swung)
    LaunchedEffect(Unit) { swung = true }
    return { sample(BellSwing.angle, swing.ms) }
}

/** How far the bell has turned into the check once [granted]: over 200 ms, on the standard easing, or at once. */
@Composable
internal fun rememberBellCheck(granted: Boolean): () -> Float {
    val target = if (granted) 1f else 0f
    val turning by animateFloatAsState(
        target,
        tween(BellSwing.CHECK_MILLIS, easing = BellSwing.checkEasing),
        label = "check",
    )
    val reduced = LocalReducedMotion.current
    return { if (reduced) target else turning }
}

/**
 * Notifications with the bell at [swing] degrees and turned into the check as far as [check] says, as the screen or a
 * preview gives them.
 */
@Composable
internal fun NotificationsAt(
    swing: () -> Float,
    check: () -> Float,
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        IconPage(
            disc = { placed -> BellDisc(swing, check, placed) },
            alert = false,
            title = stringResource(R.string.onboarding_notifications_title),
            body = stringResource(R.string.onboarding_notifications_body),
        ) {
            PrimaryAction(text = stringResource(R.string.onboarding_notifications_allow), onClick = onAllow)
            SecondaryAction(text = stringResource(R.string.onboarding_notifications_not_now), onClick = onNotNow)
        }
    }
}

/** The bell on its disc, swinging from its top, and the check it turns into. */
@Composable
private fun BellDisc(
    swing: () -> Float,
    check: () -> Float,
    modifier: Modifier,
) {
    IconDisc(
        icon = R.drawable.ic_onboarding_bell,
        alert = false,
        modifier = modifier,
        iconModifier =
            Modifier.graphicsLayer {
                transformOrigin = BELL_TOP
                rotationZ = swing()
                alpha = 1f - check()
            },
        over = {
            DiscIcon(
                R.drawable.ic_onboarding_check,
                alert = false,
                modifier = Modifier.graphicsLayer { alpha = check() },
            )
        },
    )
}
