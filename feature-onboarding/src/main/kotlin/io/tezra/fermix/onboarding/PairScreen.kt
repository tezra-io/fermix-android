package io.tezra.fermix.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors

// The visual canon's Pair: the diagram's 40 dp devices and 24 dp lock 10 dp from 36 dp dashed lines
// drawn 1.5 dp wide; the title 32 dp below it (the diagram's 8 dp margin and the title's 24 dp), the body
// and the terminal line 20 dp apart, the command 8 dp under its line, in the code card's grey with the
// canon's light ink, on 16 dp corners.
private val DEVICE = 40.dp
private val LOCK = 24.dp
private val DIAGRAM_GAP = 10.dp
private val DIAGRAM_TOP = 12.dp
private val LINK = 36.dp
private val LINK_STROKE = 1.5.dp
private val TITLE_TOP = 32.dp
private val BODY_TOP = 20.dp
private val COMMAND_TOP = 8.dp
private val COMMAND_CORNER = 16.dp
private val COMMAND_INK = Color(0xFFE6E7EB)
private val SHOWN_AS_BELOW = 8.dp
private val PENCIL = 20.dp
private val DASH = floatArrayOf(4f, 4f)

/** Pair's actions: back, copying the command, renaming the phone, and the two ways to bring a link. */
@Immutable
data class PairActions(
    val onBack: () -> Unit,
    val onCopy: () -> Unit,
    val onRename: (String) -> Unit,
    val onScan: () -> Unit,
    val onPaste: () -> Unit,
)

/**
 * Step 2 (design section 13.3): phone, lock, computer; the two ways to open pairing on the computer, the
 * pane or `fermix pair` with its copy button; "Scan the code" and "Paste a pairing link". Above them,
 * "Shown as **[deviceName]**" with the pencil that opens the rename sheet: core-session takes the name as
 * the handshake completes, before Verify shows, so the name is set here (the design draws it on Verify).
 */
@Composable
fun PairScreen(
    deviceName: String,
    actions: PairActions,
    modifier: Modifier = Modifier,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    val colors = LocalFermixColors.current
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(onBack = actions.onBack, ink = colors.ink)
        OnboardingPage(
            modifier = Modifier.weight(1f),
            actions = {
                ShownAs(deviceName = deviceName, onRename = { renaming = true })
                PrimaryAction(text = stringResource(R.string.onboarding_pair_scan), onClick = actions.onScan)
                SecondaryAction(text = stringResource(R.string.onboarding_paste_link), onClick = actions.onPaste)
            },
        ) {
            PairDiagram(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = DIAGRAM_TOP))
            Text(
                text = stringResource(R.string.onboarding_pair_title),
                style = FermixType.headline,
                color = colors.ink,
                modifier = Modifier.padding(top = TITLE_TOP),
            )
            Text(
                text = AnnotatedString.fromHtml(stringResource(R.string.onboarding_pair_body)),
                style = FermixType.body,
                color = colors.ink,
                modifier = Modifier.padding(top = BODY_TOP),
            )
            Text(
                text = stringResource(R.string.onboarding_pair_terminal),
                style = FermixType.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = BODY_TOP),
            )
            CommandLine(onCopy = actions.onCopy, modifier = Modifier.padding(top = COMMAND_TOP))
        }
    }
    if (renaming) {
        RenameSheet(
            deviceName = deviceName,
            onRename = { name ->
                renaming = false
                actions.onRename(name)
            },
            onDismiss = { renaming = false },
        )
    }
}

/** Phone ⟷ lock ⟷ computer (the canon's `.dia`), drawn for the eye alone. */
@Composable
private fun PairDiagram(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(DIAGRAM_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiagramIcon(R.drawable.ic_onboarding_phone, DEVICE)
        DashedLink()
        DiagramIcon(R.drawable.ic_onboarding_lock, LOCK)
        DashedLink()
        DiagramIcon(R.drawable.ic_onboarding_laptop, DEVICE)
    }
}

@Composable
private fun DiagramIcon(
    @DrawableRes icon: Int,
    size: Dp,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = LocalFermixColors.current.ink,
    )
}

@Composable
private fun DashedLink() {
    val ink = LocalFermixColors.current.inkTertiary
    Canvas(modifier = Modifier.width(LINK).height(LINK_STROKE)) {
        val y = size.height / 2f
        val dash = PathEffect.dashPathEffect(DASH.map { it * density }.toFloatArray())
        drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = LINK_STROKE.toPx(), pathEffect = dash)
    }
}

/** `fermix pair` in mono on the code card, with its copy button (the canon's `.cmdl`). */
@Composable
private fun CommandLine(
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.codeCard, RoundedCornerShape(COMMAND_CORNER))
                .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.onboarding_pair_command),
            style = FermixType.mono,
            color = COMMAND_INK,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy) {
            Icon(
                painter = painterResource(R.drawable.ic_onboarding_copy),
                contentDescription = stringResource(R.string.onboarding_copy),
                modifier = Modifier.size(PENCIL),
                tint = COMMAND_INK,
            )
        }
    }
}

/**
 * "Shown as **Pixel 9 Pro**" (the canon's `.dev`), centred above the actions, with the pencil that
 * renames the phone when [onRename] is given: Pair's line has it, Verify's, once the name went out, not.
 */
@Composable
internal fun ShownAs(
    deviceName: String,
    onRename: (() -> Unit)?,
) {
    val colors = LocalFermixColors.current
    val bold = SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = SHOWN_AS_BELOW),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = boldArgument(stringResource(R.string.onboarding_shown_as), deviceName, bold),
            style = FermixType.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onRename != null) Pencil(onRename)
    }
}

@Composable
private fun Pencil(onRename: () -> Unit) {
    IconButton(onClick = onRename) {
        Icon(
            painter = painterResource(R.drawable.ic_onboarding_pencil),
            contentDescription = stringResource(R.string.onboarding_rename),
            modifier = Modifier.size(PENCIL),
            tint = LocalFermixColors.current.textSecondary,
        )
    }
}
