package io.tezra.fermix.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.CopyCheck
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.PairBuild
import io.tezra.fermix.design.RingOn
import io.tezra.fermix.design.deviceShown
import io.tezra.fermix.design.iconFocusRing
import io.tezra.fermix.design.linkDrawn
import io.tezra.fermix.design.lockScale
import io.tezra.fermix.design.lockShownAt
import io.tezra.fermix.design.rememberMoment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * The diagram builds once, and Copy shows a check for a moment (the M51 update's 7.4).
 */
@Composable
fun PairScreen(
    deviceName: String,
    actions: PairActions,
    modifier: Modifier = Modifier,
) {
    var copies by remember { mutableIntStateOf(0) }
    val build = rememberPairBuild()
    val check = rememberCopyCheck(copies)
    val counted =
        actions.copy(
            onCopy = {
                copies++
                actions.onCopy()
            },
        )
    PairAt(deviceName, counted, build, check, modifier = modifier)
}

/**
 * The diagram's build clock, read as it is drawn: from 0 to 900 ms on a forward entry, once. It is played as it
 * starts, so a rotation, and the way back from Scan, under which the back stack keeps Pair's saved state, show it
 * built, even one that came mid-build, as Remove animations does.
 */
@Composable
internal fun rememberPairBuild(): () -> Float {
    var built by rememberSaveable { mutableStateOf(false) }
    val build = rememberMoment(PairBuild.CLOCK_MILLIS, built)
    LaunchedEffect(Unit) { built = true }
    return { build.ms }
}

/**
 * How far Copy's icon has turned into a check after the [copies]th copy, read as it is drawn: in over 200 ms, standing
 * until 1,500 ms, out over 200 ms; under Remove animations it stands the 1,500 ms with no fade. The time it stands is
 * waited, not animated, so the check shows its time whatever the animator's scale.
 */
@Composable
internal fun rememberCopyCheck(copies: Int): () -> Float {
    val reduced = LocalReducedMotion.current
    val check = remember { Animatable(0f) }
    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        val fade = if (reduced) snap() else tween<Float>(CopyCheck.FADE_MILLIS, easing = CopyCheck.easing)
        launch { check.animateTo(1f, fade) }
        delay(CopyCheck.SHOWN_MILLIS.toLong())
        check.animateTo(0f, fade)
    }
    return { check.value }
}

/**
 * Pair with the diagram at [build] on its clock and Copy's check at [check], as the screen or a preview gives them;
 * the pencil opens the rename sheet, which a rotation keeps open.
 */
@Composable
internal fun PairAt(
    deviceName: String,
    actions: PairActions,
    build: () -> Float,
    check: () -> Float,
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
            PairDiagram(build, Modifier.align(Alignment.CenterHorizontally).padding(top = DIAGRAM_TOP))
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
            CommandLine(onCopy = actions.onCopy, check = check, modifier = Modifier.padding(top = COMMAND_TOP))
        }
    }
    if (!renaming) return
    RenameSheet(
        deviceName = deviceName,
        onRename = { name ->
            renaming = false
            actions.onRename(name)
        },
        onDismiss = { renaming = false },
    )
}

/**
 * Phone ⟷ lock ⟷ computer (the canon's `.dia`), drawn for the eye alone, at [build] on its clock (the M51 update's
 * 7.4): the phone slides in from the start side as it fades in, the computer from the end side, the link draws from
 * the phone to the computer, the lock's place left to it, and the lock pops at the link's centre.
 */
@Composable
internal fun PairDiagram(
    build: () -> Float,
    modifier: Modifier = Modifier,
) {
    // The start side is the left, or the right in a right-to-left layout, where the row puts the phone.
    val towardsEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(DIAGRAM_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiagramIcon(
            R.drawable.ic_onboarding_phone,
            DEVICE,
            Modifier.slidingIn(build, PairBuild.PHONE_START, -towardsEnd),
        )
        DashedLink(drawn = { (linkDrawn(build()) * 2f).coerceIn(0f, 1f) })
        DiagramIcon(
            R.drawable.ic_onboarding_lock,
            LOCK,
            Modifier.graphicsLayer {
                val ms = build()
                scaleX = lockScale(ms)
                scaleY = scaleX
                alpha = lockShownAt(ms)
            },
        )
        DashedLink(drawn = { (linkDrawn(build()) * 2f - 1f).coerceIn(0f, 1f) })
        DiagramIcon(
            R.drawable.ic_onboarding_laptop,
            DEVICE,
            Modifier.slidingIn(build, PairBuild.COMPUTER_START, towardsEnd),
        )
    }
}

/** A device fading in from [start] on the clock [build], from [PairBuild.slide] towards [from], -1 or 1 along x. */
private fun Modifier.slidingIn(
    build: () -> Float,
    start: Int,
    from: Float,
): Modifier =
    graphicsLayer {
        val shown = deviceShown(build(), start)
        alpha = shown
        translationX = (1f - shown) * PairBuild.slide.toPx() * from
    }

@Composable
private fun DiagramIcon(
    @DrawableRes icon: Int,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = modifier.size(size),
        tint = LocalFermixColors.current.ink,
    )
}

/** A dashed stretch of the link, [drawn] of it from the phone's side, read as it is drawn. */
@Composable
private fun DashedLink(drawn: () -> Float) {
    val ink = LocalFermixColors.current.inkTertiary
    Canvas(modifier = Modifier.width(LINK).height(LINK_STROKE)) {
        val length = size.width * drawn()
        if (length <= 0f) return@Canvas
        val y = size.height / 2f
        val dash = PathEffect.dashPathEffect(DASH.map { it * density }.toFloatArray())
        val rtl = layoutDirection == LayoutDirection.Rtl
        val from = if (rtl) size.width else 0f
        val to = if (rtl) size.width - length else length
        drawLine(ink, Offset(from, y), Offset(to, y), strokeWidth = LINK_STROKE.toPx(), pathEffect = dash)
    }
}

/**
 * `fermix pair` in mono on the code card, with its copy button (the canon's `.cmdl`), whose icon turns into a check as
 * far as [check] says, read as it is drawn; the button keeps its label throughout.
 */
@Composable
private fun CommandLine(
    onCopy: () -> Unit,
    check: () -> Float,
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
        val label = stringResource(R.string.onboarding_copy)
        IconButton(
            onClick = onCopy,
            modifier = Modifier.semantics { contentDescription = label }.iconFocusRing(RingOn.Dark),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_onboarding_copy),
                contentDescription = null,
                modifier = Modifier.size(PENCIL).graphicsLayer { alpha = 1f - check() },
                tint = COMMAND_INK,
            )
            Icon(
                painter = painterResource(R.drawable.ic_onboarding_check),
                contentDescription = null,
                modifier = Modifier.size(PENCIL).graphicsLayer { alpha = check() },
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
    IconButton(onClick = onRename, modifier = Modifier.iconFocusRing()) {
        Icon(
            painter = painterResource(R.drawable.ic_onboarding_pencil),
            contentDescription = stringResource(R.string.onboarding_rename),
            modifier = Modifier.size(PENCIL),
            tint = LocalFermixColors.current.textSecondary,
        )
    }
}
