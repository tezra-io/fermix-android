package io.tezra.fermix.instance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.transport.Candidate

// The visual canon's Instance page: `.sh` section headers 18 dp above and 4 below, 24 at the sides; `.kv`
// rows at least 48 dp, 4 dp and 24 dp of padding, 16 dp between label and value; `.cand` candidates, 4 dp
// apart under 2 dp, over 6 dp, with 8 dp dots and an 11/16 scope tag (`.cand em`), 5 dp inside its hairline
// at the sides and none above or below; the `.log` on the code card's dark, 16 dp corners, 4 dp under its
// header and 24 at the sides, 10 dp and 12 dp inside, in #C9CCD4.
private val SIDES = 24.dp
private val HEADER_TOP = 18.dp
private val HEADER_BOTTOM = 4.dp
private val ROW_ENDS = 4.dp
private val ROW_GAP = 16.dp
private val CANDIDATE_GAP = 4.dp
private val CANDIDATE_TOP = 2.dp
private val CANDIDATE_BOTTOM = 6.dp
private val CANDIDATE_DOT = 8.dp
private val CANDIDATE_INSET = 8.dp
private val TAG_CORNER = 6.dp
private val TAG_SIDES = 5.dp
private val LOG_CORNER = 16.dp
private val LOG_TOP = 4.dp
private val LOG_ENDS = 10.dp
private val LOG_SIDES = 12.dp
private val LOG_FADE = 24.dp
private val LOG_INK = Color(0xFFC9CCD4)
private val BAR_HEIGHT = 64.dp
private val BAR_SIDES = 4.dp

/** The canon's candidate line, mono 13/20. */
private val CANDIDATE_STYLE = FermixType.mono.copy(fontSize = 13.sp, lineHeight = 20.sp)

/** An action's label in a row (KvRow): the body, underlined. */
internal val ACTION_LABEL = FermixType.body.copy(textDecoration = TextDecoration.Underline)

/** The canon's log, mono 12/18. */
private val LOG_STYLE = FermixType.mono.copy(fontSize = 12.sp, lineHeight = 18.sp)

/** The canon's scope tag, 11/16 medium on a hairline. */
private val TAG_STYLE = FermixType.labelSmall.copy(fontFeatureSettings = null)

/** The bar above a page (the canon's `.ab`) with the back button alone. */
@Composable
fun BackBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = BAR_SIDES),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_instance_back),
                contentDescription = stringResource(R.string.instance_back),
                tint = LocalFermixColors.current.ink,
            )
        }
    }
}

/** A section's header in the ink. */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = FermixType.label,
        color = LocalFermixColors.current.ink,
        modifier = Modifier.padding(start = SIDES, end = SIDES, top = HEADER_TOP, bottom = HEADER_BOTTOM),
    )
}

/**
 * A row: [label] in [labelStyle], the body in the ink unless the style names a colour, and its [value] on the right
 * in the secondary text's grey, mono where the value is an id; tappable when [onClick] is given. An action the canon
 * drew in the accent ("Test connection", "Clear media cache") is in [ACTION_LABEL]: in the ink, as a fact's words
 * are, it is told from the facts by its underline, as the M51 update's reference player draws a text button.
 */
@Composable
internal fun KvRow(
    label: String,
    value: String? = null,
    mono: Boolean = false,
    labelStyle: TextStyle = FermixType.body,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalFermixColors.current
    val valueStyle = if (mono) CANDIDATE_STYLE else FermixType.bodyMedium
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = FermixSpacing.minTarget)
                .padding(horizontal = SIDES, vertical = ROW_ENDS),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label keeps its width and the value takes the rest, its end on the row's, as the canon's
        // space-between puts it; a row with no value lets the label wrap across the whole row.
        Text(text = label, style = labelStyle, color = labelStyle.color.takeOrElse { colors.ink })
        if (value != null) {
            Text(
                text = value,
                style = valueStyle,
                color = colors.textSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A row with a switch; the whole row toggles it, as one control. */
@Composable
internal fun SwitchRow(
    label: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
                .heightIn(min = FermixSpacing.minTarget)
                .padding(horizontal = SIDES, vertical = ROW_ENDS),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FermixType.body,
            color = LocalFermixColors.current.ink,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = on, onCheckedChange = null)
    }
}

/** The record's candidates, each with its port, its scope and whether it is [reached] (reachableCandidates). */
@Composable
internal fun Candidates(
    candidates: List<Candidate>,
    port: Int,
    reached: (Candidate) -> Boolean,
) {
    Column(
        modifier = Modifier.padding(start = SIDES, end = SIDES, top = CANDIDATE_TOP, bottom = CANDIDATE_BOTTOM),
        verticalArrangement = Arrangement.spacedBy(CANDIDATE_GAP),
    ) {
        for (candidate in candidates) CandidateLine(candidate, port, reached(candidate))
    }
}

@Composable
private fun CandidateLine(
    candidate: Candidate,
    port: Int,
    reached: Boolean,
) {
    val colors = LocalFermixColors.current
    val scope =
        when (candidate.scope) {
            Candidate.Scope.LAN -> R.string.instance_scope_lan
            Candidate.Scope.TAILNET -> R.string.instance_scope_tailnet
        }
    // The dot stands apart, on the middle of the address's line, and the address and its tag flow beside it:
    // at a large font the scope tag drops below the address, under it rather than under the dot, and never
    // splits it.
    val firstLine = with(LocalDensity.current) { CANDIDATE_STYLE.lineHeight.toDp() }
    Row(horizontalArrangement = Arrangement.spacedBy(CANDIDATE_INSET)) {
        Box(modifier = Modifier.height(firstLine), contentAlignment = Alignment.Center) {
            Box(
                modifier =
                    Modifier
                        .size(CANDIDATE_DOT)
                        .background(if (reached) colors.ok else colors.inkTertiary, CircleShape),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CANDIDATE_INSET),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.instance_candidate, candidate.host, port),
                style = CANDIDATE_STYLE,
                color = colors.textSecondary,
            )
            Tag(stringResource(scope), TAG_STYLE, ends = 0.dp)
        }
    }
}

/** The canon's `.tag` and `.cand em`: words on a hairline border, [ends] inside it above and below. */
@Composable
fun Tag(
    text: String,
    style: TextStyle,
    ends: Dp,
) {
    val colors = LocalFermixColors.current
    Text(
        text = text,
        style = style,
        color = colors.textSecondary,
        modifier =
            Modifier
                .border(FermixSpacing.hairline, colors.hairline, RoundedCornerShape(TAG_CORNER))
                .padding(horizontal = TAG_SIDES, vertical = ends),
    )
}

/**
 * The connection log, oldest first, on the code card's dark, panning sideways as the canon's `pre` does,
 * inside the card's padding, so a line wider than the card is cut 12 dp in from its edge, not at it, and
 * fades out there while more of it lies past the cut, which says the card pans.
 */
@Composable
internal fun Log(lines: List<String>) {
    val scroll = rememberScrollState()
    Text(
        text = lines.joinToString("\n"),
        style = LOG_STYLE,
        color = LOG_INK,
        softWrap = false,
        modifier =
            Modifier
                .padding(start = SIDES, end = SIDES, top = LOG_TOP)
                .fillMaxWidth()
                .background(LocalFermixColors.current.codeCard, RoundedCornerShape(LOG_CORNER))
                .padding(horizontal = LOG_SIDES, vertical = LOG_ENDS)
                .fadedEnd(more = scroll.canScrollForward)
                .horizontalScroll(scroll),
    )
}

/** Fades the content's last [LOG_FADE] at its end side to nothing while there is [more] past it. */
private fun Modifier.fadedEnd(more: Boolean): Modifier {
    if (!more) return this
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val fade = LOG_FADE.toPx()
        val rtl = layoutDirection == LayoutDirection.Rtl
        val from = if (rtl) fade else size.width - fade
        val to = if (rtl) 0f else size.width
        drawRect(
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), from, to),
            blendMode = BlendMode.DstIn,
        )
    }
}
