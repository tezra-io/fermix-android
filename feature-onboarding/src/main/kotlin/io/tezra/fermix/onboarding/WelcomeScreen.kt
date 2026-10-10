package io.tezra.fermix.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.Drop
import io.tezra.fermix.design.FermixMark
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.FermixWordmark
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.MarkPose
import io.tezra.fermix.design.dropAt
import io.tezra.fermix.design.focusRing
import io.tezra.fermix.design.idling
import io.tezra.fermix.design.rememberMarkMoment
import io.tezra.fermix.design.textButtonColors
import kotlin.random.Random

// The update's reference player's Welcome: the mark 112 dp across and 120 dp under the status bar, which the page's
// own 16 dp makes 104 here, the title 20 dp under it, the tagline 8 dp under the title. The title is the Fermix
// wordmark (the owner, 2026-10-10), 40 dp tall with the file's own margin, its letters 34.5 dp: macOS's welcome draws
// the wordmark's letters 40 pt tall.
private val MARK = 112.dp
private val MARK_TOP = 104.dp
private val TITLE_TOP = 20.dp
private val TITLE = 40.dp
private val TAGLINE_TOP = 8.dp

// The player's 120 dp is a portrait phone's. On a window under 480 dp tall, window-core's compact height (a phone on
// its side), the mark stands no lower than the drop's first dot needs, so "Get started" stays in view: the dot starts
// 21 of the mark's units above its box, 23.5 dp at 112 dp, and the page's 16 dp and these 8 hold it whole.
private val SHORT_WINDOW = 480.dp
private val SHORT_MARK_TOP = 8.dp

/** Welcome's two actions. */
internal data class WelcomeActions(
    val onGetStarted: () -> Unit,
    val onNoFermix: () -> Unit,
)

/**
 * Step 1 (design section 13.3, as the M51 update's section 3 changes it): the Fermix mark dropping in, the name as the
 * Fermix wordmark (the owner, 2026-10-10), the tagline, "Get started", and "Don't have Fermix yet?", which opens the
 * install page. The drop runs on one clock: the dot lands at 380 ms with the update's optional `CLOCK_TICK`, the words
 * rise in at 1,100, 1,210 and 1,320 ms, and the idle follows the clock's end, its blinks drawn from [random]. It plays
 * once in an onboarding run: after a rotation, a fold, a return from Pair or the process's restoration the mark rests
 * and the words are there at once; with Remove animations on it opens on the drop's last frame and nothing breathes.
 * The tick plays only on a frame that draws the landing, so never while the mark rests or stands still, nor when Remove
 * animations is turned off again.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onNoFermix: () -> Unit,
    random: Random,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var played by rememberSaveable { mutableStateOf(false) }
    var landed by remember { mutableStateOf(false) }
    val drop =
        rememberMarkMoment(Drop.CLOCK_MILLIS, played, random) { ms ->
            if (!landed && Drop.drawsLanding(ms)) {
                landed = true
                HapticFeedback.perform(view, HapticUse.MarkLands)
            }
        }
    LaunchedEffect(Unit) { played = true }
    // The drop's last frame, made once: from the clock's end on, each frame only breathes and blinks it.
    val settled = remember { dropAt(Drop.CLOCK_MILLIS.toFloat()) }
    WelcomeAt(
        pose = { (if (drop.ms < Drop.CLOCK_MILLIS) dropAt(drop.ms) else settled).idling(drop.idle) },
        ms = { drop.ms },
        actions = WelcomeActions(onGetStarted, onNoFermix),
        modifier = modifier,
    )
}

/**
 * Welcome at one instant of its drop: the mark in [pose], the words where the drop's clock at [ms] has them, each read
 * as the screen draws, so the drop moves them without composing the screen again; a preview passes a fixed instant.
 * On a window under 480 dp tall the mark stands near the top, so the primary action stays in view.
 * "Don't have Fermix yet?" is a link, in the ink and underlined (the update's 1.3); the canon sets it in 12 / 16, a
 * size the type scale does not have, so it takes the scale's supporting text, 14 / 20.
 */
@Composable
internal fun WelcomeAt(
    pose: () -> MarkPose,
    ms: () -> Float,
    actions: WelcomeActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    val (title, tagline, buttons) = Drop.words
    val short = LocalWindowInfo.current.containerDpSize.height < SHORT_WINDOW
    OnboardingPage(
        modifier = modifier,
        actions = {
            PrimaryAction(
                text = stringResource(R.string.onboarding_welcome_get_started),
                onClick = actions.onGetStarted,
                modifier = Modifier.risingIn(ms, buttons, Drop.wordsRise),
            )
            TextButton(
                onClick = actions.onNoFermix,
                modifier =
                    Modifier
                        .risingIn(ms, buttons, Drop.wordsRise)
                        .fillMaxWidth()
                        .heightIn(min = FermixSpacing.minTarget)
                        .focusRing(FermixShapes.button),
                colors = textButtonColors(colors),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_welcome_no_fermix),
                    style = FermixType.bodyMedium,
                    textDecoration = TextDecoration.Underline,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = if (short) SHORT_MARK_TOP else MARK_TOP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FermixMark(pose, MARK)
            FermixWordmark(TITLE, Modifier.padding(top = TITLE_TOP).risingIn(ms, title, Drop.wordsRise))
            Text(
                text = stringResource(R.string.onboarding_welcome_tagline),
                style = FermixType.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = TAGLINE_TOP).risingIn(ms, tagline, Drop.wordsRise),
            )
        }
    }
}
