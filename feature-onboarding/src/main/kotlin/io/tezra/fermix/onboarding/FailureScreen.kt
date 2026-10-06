package io.tezra.fermix.onboarding

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors

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
 * a link, underlined (pageOf). A refusal plays `REJECT` once (section 13.1, "Haptics").
 */
@Composable
fun FailureScreen(
    case: FailureCase,
    host: String,
    onAction: (FailureAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (case.refusal) HapticOnce(HapticUse.Refusal)
    val title = stringResource(case.title, host)
    val body = case.body?.let { stringResource(it, host) }
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        if (case.alert) {
            Spacer(modifier = Modifier.fillMaxWidth().height(ALERT_EDGE).background(LocalFermixColors.current.err))
        }
        IconPage(icon = case.icon, alert = case.alert, title = title, body = body) {
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

/**
 * The page a failure and the notifications step share (the canon draws both as `.fail`): an icon on its
 * disc, a one-sentence title, at most one sentence under it, and the actions at the foot.
 */
@Composable
internal fun IconPage(
    @DrawableRes icon: Int,
    alert: Boolean,
    title: String,
    body: String?,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFermixColors.current
    OnboardingPage(actions = actions) {
        IconDisc(icon = icon, alert = alert, modifier = Modifier.padding(top = DISC_TOP, bottom = DISC_BELOW))
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
