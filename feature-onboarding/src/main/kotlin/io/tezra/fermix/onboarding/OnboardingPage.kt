package io.tezra.fermix.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.RingOn
import io.tezra.fermix.design.focusRing
import io.tezra.fermix.design.iconFocusRing
import io.tezra.fermix.design.textButtonColors

// The visual canon's onboarding page (.ob, .ft, .fail .ic and .ab): 24 dp at the sides and 16 dp above
// and below, the actions 6 dp apart at the foot, a 72 dp disc behind a failure's icon, a 64 dp bar. The
// canon's pages all fit, so it gives no gap above the actions; at font scale 2.0 they follow the content
// 24 dp below it, the canon's gap between a title and what is under it.
private val PAGE_SIDES = 24.dp
private val PAGE_ENDS = 16.dp
private val ACTION_GAP = 6.dp
private val ACTIONS_TOP = 24.dp
private val DISC = 72.dp
private val DISC_ICON = 32.dp
private val BAR_HEIGHT = 64.dp
private val BAR_SIDES = 4.dp

/**
 * One onboarding page in design section 13.11's 480 dp column (rule 2): [content] from the top, or
 * [centred] in the space above the actions (the canon's `.ctr`), and [actions] at the foot, so they sit
 * at the bottom of a page that fits and follow the content of one that scrolls, as at font scale 2.0.
 * The page keeps clear of the system bars and the cutout, as the app draws edge to edge; a screen that
 * pads them itself, above the page, leaves it none to pad.
 */
@Composable
internal fun OnboardingPage(
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    centred: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    FermixColumn(ColumnWidth.Narrow, modifier.safeDrawingPadding()) {
        BoxWithConstraints {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = PAGE_SIDES, vertical = PAGE_ENDS),
            ) {
                // The spacers share what the window leaves, and nothing once the content outgrows it.
                if (centred) Spacer(modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
                    content = content,
                )
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.padding(top = ACTIONS_TOP),
                    verticalArrangement = Arrangement.spacedBy(ACTION_GAP),
                    content = actions,
                )
            }
        }
    }
}

/**
 * Plays [use] as its screen first shows, and not again when a rotation or a fold draws the screen anew
 * (design section 13.1, "Haptics").
 */
@Composable
internal fun HapticOnce(use: HapticUse) {
    val view = LocalView.current
    var played by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!played) HapticFeedback.perform(view, use)
        played = true
    }
}

/**
 * A screen's one action, the canon's `.btn.p`: the ink's pill with its label in onInk (the M51 update's 1.3), as
 * Material's Button draws the scheme's primary, at least 48 dp tall, with the focus ring (the update's 1.3). [dark] is
 * for a screen dark in both modes, Scan's camera, whose pill is the dark mode's ink in light mode as well, and so is
 * its ring: light mode's ink, near-black, would lie on the frame's near-black at about 1.1 : 1, and read as less than
 * "Paste a pairing link" under it.
 */
@Composable
internal fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dark: Boolean = false,
) {
    val colors =
        if (dark) {
            ButtonDefaults.buttonColors(containerColor = FermixColors.Dark.ink, contentColor = FermixColors.Dark.onInk)
        } else {
            ButtonDefaults.buttonColors()
        }
    Button(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = FermixSpacing.minTarget)
                .focusRing(FermixShapes.button, if (dark) RingOn.Dark else RingOn.Surface),
        enabled = enabled,
        colors = colors,
    ) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}

/**
 * A secondary action, the canon's `.btn.x`: the ink's words on no fill, at least 48 dp tall, with the focus ring;
 * underlined when it is a [link], one that opens a web page (pageOf), as the M51 update's 1.3 underlines every link.
 */
@Composable
internal fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    link: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = FermixSpacing.minTarget).focusRing(FermixShapes.button),
        colors = textButtonColors(LocalFermixColors.current),
    ) {
        val decoration = if (link) TextDecoration.Underline else null
        Text(text = text, textAlign = TextAlign.Center, textDecoration = decoration)
    }
}

/** The bar above Pair and Scan (the canon's `.ab`), spanning the window, with the back button in [ink]. */
@Composable
internal fun BackBar(
    onBack: () -> Unit,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = BAR_SIDES),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.iconFocusRing()) {
            Icon(
                painter = painterResource(R.drawable.ic_onboarding_back),
                contentDescription = stringResource(R.string.onboarding_back),
                tint = ink,
            )
        }
    }
}

/**
 * A failure's or the notifications step's icon on its disc (the canon's `.fail .ic`): the agent bubble's grey
 * behind the ink, or for the security event, [alert], the error text's colour, as the M51 update's reference player
 * draws it: the update leaves no white for an icon on the error's fill in dark mode, where onInk is near-black. The
 * icon takes [iconModifier], and [over] draws on it: the bell's check.
 */
@Composable
internal fun IconDisc(
    @DrawableRes icon: Int,
    alert: Boolean,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    over: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier.size(DISC).clip(CircleShape).background(LocalFermixColors.current.agentBubble),
        contentAlignment = Alignment.Center,
    ) {
        DiscIcon(icon = icon, alert = alert, modifier = iconModifier)
        over()
    }
}

/** [icon] as a disc draws it, at its icon size, in the ink, or for the security event, [alert], the error text's. */
@Composable
internal fun DiscIcon(
    @DrawableRes icon: Int,
    alert: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    val tint = if (alert) colors.errText else colors.ink
    Icon(painterResource(icon), contentDescription = null, modifier = modifier.size(DISC_ICON), tint = tint)
}
