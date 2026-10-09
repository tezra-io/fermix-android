package io.tezra.fermix.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FailureEntrance
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.failureDiscAt
import io.tezra.fermix.design.failureEdgeAt
import io.tezra.fermix.design.rememberMoment

// The visual canon's `.fail`: the icon's disc 72 dp down and 28 dp above the title, the body 12 dp under
// the title in the secondary text's grey; the security event's 4 dp red edge along the top of the screen, under
// the status bar.
private val DISC_TOP = 72.dp
private val DISC_BELOW = 28.dp
private val BODY_TOP = 12.dp
private val ALERT_EDGE = 4.dp

/**
 * One failure of design section 13.3's table, every one drawn from [FailureCase]: its icon, its title and
 * body with [host] where the copy names the computer, its primary action and its secondaries. The security
 * event, Wrong machine, is red: the icon on its disc and the title in the error text's colour, and the screen's
 * top edge in the error's fill, as the M51 update's reference player draws it. A secondary that opens a web page is
 * a link, underlined (pageOf). The disc settles in, and a refusal plays `REJECT` once as it lands (section 13.1,
 * "Haptics", and the M51 update's 7.4); the security event's edge draws across instead, its `REJECT` at once.
 */
@Composable
fun FailureScreen(
    case: FailureCase,
    host: String,
    onAction: (FailureAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entrance = rememberFailureEntrance(case)
    FailureAt(case, host, onAction, entrance, modifier)
}

/**
 * The entrance's clock, read as it is drawn: from 0 to 400 ms, once, with [case]'s `REJECT` when it is a refusal, as
 * the disc lands at 200 ms, or at once for the security event and under Remove animations. It is played as it starts,
 * so a rotation, even mid-way, shows the screen entered, and plays nothing again but a `REJECT` it had not reached.
 */
@Composable
internal fun rememberFailureEntrance(case: FailureCase): () -> Float {
    val view = LocalView.current
    var entered by rememberSaveable { mutableStateOf(false) }
    var refused by rememberSaveable { mutableStateOf(!case.refusal) }
    val lands = if (case.alert) 0 else FailureEntrance.REFUSAL_MILLIS
    val entrance =
        rememberMoment(FailureEntrance.CLOCK_MILLIS, entered) { ms ->
            if (!refused && ms >= lands) {
                refused = true
                HapticFeedback.perform(view, HapticUse.Refusal)
            }
        }
    LaunchedEffect(Unit) { entered = true }
    return { entrance.ms }
}

/** The disc's scale for [case] at [ms] into the entrance: settling, or whole for the security event. */
internal fun discScale(
    case: FailureCase,
    ms: Float,
): Float = if (case.alert) 1f else failureDiscAt(ms)

/** [case]'s screen at [entrance] on its clock, as the screen or a preview gives it. */
@Composable
internal fun FailureAt(
    case: FailureCase,
    host: String,
    onAction: (FailureAction) -> Unit,
    entrance: () -> Float,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(case.title, host)
    val body = case.body?.let { stringResource(it, host) }
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        if (case.alert) AlertEdge(drawn = { failureEdgeAt(entrance()) })
        val disc: @Composable (Modifier) -> Unit = { placed ->
            val settled =
                placed.graphicsLayer {
                    scaleX = discScale(case, entrance())
                    scaleY = scaleX
                }
            IconDisc(icon = case.icon, alert = case.alert, modifier = settled)
        }
        IconPage(disc = disc, alert = case.alert, title = title, body = body) {
            PrimaryAction(text = stringResource(case.primary.label), onClick = { onAction(case.primary) })
            for (secondary in case.secondaries) {
                SecondaryAction(
                    text = stringResource(secondary.label),
                    onClick = { onAction(secondary) },
                    link = pageOf(secondary) != null,
                )
            }
        }
    }
}

/** The security event's red edge along the top, [drawn] of it across from the start side, read as it is drawn. */
@Composable
private fun AlertEdge(drawn: () -> Float) {
    val start = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else 0f
    Spacer(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ALERT_EDGE)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(start, TransformOrigin.Center.pivotFractionY)
                    scaleX = drawn()
                }.background(LocalFermixColors.current.err),
    )
}

/**
 * The page a failure and the notifications step share (the canon draws both as `.fail`): an icon on its
 * disc, which [disc] draws where the modifier it is handed places it, a one-sentence title, at most one
 * sentence under it, and the actions at the foot.
 */
@Composable
internal fun IconPage(
    disc: @Composable (Modifier) -> Unit,
    alert: Boolean,
    title: String,
    body: String?,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFermixColors.current
    OnboardingPage(actions = actions) {
        disc(Modifier.padding(top = DISC_TOP, bottom = DISC_BELOW))
        Text(text = title, style = FermixType.headline, color = if (alert) colors.errText else colors.ink)
        if (body != null) {
            Text(
                text = body,
                style = FermixType.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = BODY_TOP),
            )
        }
    }
}
