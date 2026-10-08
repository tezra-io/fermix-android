package io.tezra.fermix.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The design module's screenshot tests: the tokens as four sheets, each drawn at the twelve windows of
// @FermixPreviews and each short enough to fit whole in the shortest of them, the expanded window at font
// scale 2.0 (673 dp tall), so that every token is drawn in all twelve images. Each places its content as
// a screen does, through FermixColumn with no modifier. Their words are the tokens' and components' names
// or the visual canon's own illustration, not product copy, so they stay out of strings.xml.

/** The colours with their names, the six tints as avatars, and Material's components in the theme. */
@FermixPreviews
@Composable
fun SpecimenColour() {
    FermixPreviewTheme {
        val colors = LocalFermixColors.current
        FermixColumn(ColumnWidth.Wide) {
            Column(modifier = Modifier.padding(SHEET_PADDING), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Swatches(colors)
                Tints(colors)
                MaterialComponents(colors)
            }
        }
    }
}

/** The type scale, each style with its size over its line height. */
@FermixPreviews
@Composable
fun SpecimenType() {
    FermixPreviewTheme {
        val colors = LocalFermixColors.current
        FermixColumn(ColumnWidth.Wide) {
            Column(modifier = Modifier.padding(SHEET_PADDING)) { TypeScale(colors) }
        }
    }
}

/** A group of bubbles from each sender, between the two control-plane surfaces. */
@FermixPreviews
@Composable
fun SpecimenShape() {
    FermixPreviewTheme {
        val colors = LocalFermixColors.current
        Column(modifier = Modifier.fillMaxSize()) {
            AppBarSample(colors)
            FermixColumn(ColumnWidth.Wide, Modifier.weight(1f)) {
                // The canon's timeline padding (.tl): 8 dp above, the gutter at the sides and below.
                val timeline =
                    Modifier.padding(
                        start = FermixSpacing.gutter,
                        top = TIMELINE_TOP_PADDING,
                        end = FermixSpacing.gutter,
                        bottom = FermixSpacing.gutter,
                    )
                Column(modifier = timeline) { Bubbles(colors) }
            }
            FermixColumn(ColumnWidth.Wide) { DockSample(colors) }
        }
    }
}

/**
 * Material's controls the app uses, as the theme hands them, where the ink is both a fill and the words beside it
 * (the M51 update's 1.3 and 1.4): a switch on beside one off, a chip chosen beside one not, a focused field, its
 * border and label in the ink and its words selected, and a progress bar. The field's caret is the ink too, but a
 * field with words selected draws none, so no reference shows it. The focus is asked for once the field is first
 * composed, and the reference is taken after it lands.
 */
@FermixPreviews
@Composable
fun SpecimenControls() {
    FermixPreviewTheme {
        FermixColumn(ColumnWidth.Wide) {
            Column(modifier = Modifier.padding(SHEET_PADDING), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Toggles()
                FocusedField()
                LinearProgressIndicator(progress = { PROGRESS }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private const val PROGRESS = 0.6f

@Composable
private fun Toggles() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = true, onCheckedChange = null)
        Switch(checked = false, onCheckedChange = null)
        FilterChip(selected = true, onClick = {}, label = { Text(text = "FilterChip") })
        FilterChip(selected = false, onClick = {}, label = { Text(text = "FilterChip") })
    }
}

/** A field that takes the focus as it is first drawn, with its first word selected. */
@Composable
private fun FocusedField() {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    OutlinedTextField(
        value = TextFieldValue(text = "selected words", selection = TextRange(0, "selected".length)),
        onValueChange = {},
        label = { Text(text = "OutlinedTextField") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

private val SHEET_PADDING = 16.dp
private val TIMELINE_TOP_PADDING = 8.dp

/** The app bar's height: design section 13.5, "App bar (64 dp)", and the canon's `.ab`. */
private val APP_BAR_HEIGHT = 64.dp

/** A control-plane surface above content, the app bar, with an instance's tint line under it. */
@Composable
private fun AppBarSample(colors: FermixColors) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = APP_BAR_HEIGHT)
                    .controlPlane(Edge.Bottom, colors)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(Tint.Slate, FermixSpacing.avatarSmall)
            Text(text = "controlPlane(Edge.Bottom)", style = FermixType.title, color = colors.ink)
        }
        Box(modifier = Modifier.fillMaxWidth().height(FermixSpacing.tintLine).background(Tint.Slate.color))
    }
}

/**
 * The other control-plane surface, the floating dock, which content passes on every side. Its margin is
 * the canon's (.cmp): the gutter at the sides and below, none above, where the timeline's own gutter is.
 */
@Composable
private fun DockSample(colors: FermixColors) {
    Box(
        modifier =
            Modifier
                .padding(start = FermixSpacing.gutter, end = FermixSpacing.gutter, bottom = FermixSpacing.gutter)
                .fillMaxWidth()
                .controlPlane(Edge.Around, colors)
                .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(text = "controlPlane(Edge.Around)", style = FermixType.body, color = colors.textSecondary)
    }
}

/** A group from each sender, as wide as a bubble may be: 78 % and 88 % of the column. */
@Composable
private fun Bubbles(colors: FermixColors) {
    val user =
        listOf(
            GroupPosition.First to "Why did the nightly job fail?",
            GroupPosition.Middle to "Did it retry?",
            GroupPosition.Last to "User: first, middle, last",
        )
    val agent =
        listOf(
            GroupPosition.First to "It timed out at the export step: report_export, after 120 s.",
            GroupPosition.Last to "Agent: first, last",
        )
    Column(verticalArrangement = Arrangement.spacedBy(FermixSpacing.withinGroup)) {
        for ((position, text) in user) {
            Bubble(Sender.User, position, text, colors)
        }
        Spacer(Modifier.height(FermixSpacing.betweenGroups - FermixSpacing.withinGroup))
        for ((position, text) in agent) {
            Bubble(Sender.Agent, position, text, colors)
        }
    }
}

@Composable
private fun Bubble(
    sender: Sender,
    position: GroupPosition,
    text: String,
    colors: FermixColors,
) {
    val user = sender == Sender.User
    val share = if (user) FermixSpacing.USER_BUBBLE_MAX_WIDTH else FermixSpacing.AGENT_BUBBLE_MAX_WIDTH
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .align(if (user) Alignment.CenterEnd else Alignment.CenterStart)
                    .widthIn(max = maxWidth * share)
                    .clip(bubbleShape(sender, position))
                    .background(if (user) colors.ink else colors.agentBubble)
                    .padding(
                        horizontal = FermixSpacing.bubblePaddingHorizontal,
                        vertical = FermixSpacing.bubblePaddingVertical,
                    ),
        ) {
            Text(text = text, style = FermixType.body, color = if (user) colors.onInk else colors.ink)
        }
    }
}

/**
 * Material's own components, which read the theme rather than the design's tokens: a Button (primary,
 * onPrimary, labelLarge), a TextButton in [textButtonColors], and a filled Card (surfaceContainerHighest,
 * shapes.medium) holding a Text in the theme's default style, bodyLarge, and its content colour.
 */
@Composable
private fun MaterialComponents(colors: FermixColors) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = {}) { Text(text = "Button") }
        TextButton(onClick = {}, colors = textButtonColors(colors)) { Text(text = "TextButton") }
        Card { Text(text = "Card", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
    }
}

@Composable
private fun Tints(colors: FermixColors) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tint in Tint.entries) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Avatar(tint, FermixSpacing.avatar)
                Text(text = tint.name, style = FermixType.labelSmall, color = colors.textSecondary)
            }
        }
    }
}

/** An instance's avatar as the M51 update's reference player draws the Chats row's: a plain disc in the tint. */
@Composable
private fun Avatar(
    tint: Tint,
    size: Dp,
) {
    Box(modifier = Modifier.size(size).background(tint.color, CircleShape))
}

@Composable
private fun Swatches(colors: FermixColors) {
    val swatches =
        listOf(
            "canvas" to colors.canvas,
            "ink" to colors.ink,
            "onInk" to colors.onInk,
            "textSecondary" to colors.textSecondary,
            "inkTertiary" to colors.inkTertiary,
            "agentBubble" to colors.agentBubble,
            "hairline" to colors.hairline,
            "selection" to colors.selection,
            "ok" to colors.ok,
            "warn" to colors.warn,
            "err" to colors.err,
            "errText" to colors.errText,
            "signal" to colors.signal,
            "onSignal" to colors.onSignal,
            "tonal" to colors.tonal,
            "tonalSolid" to colors.tonalSolid,
            "scrim" to colors.scrim,
            "codeCard" to colors.codeCard,
        )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, color) in swatches) {
            Swatch(name, color, colors)
        }
    }
}

@Composable
private fun Swatch(
    name: String,
    color: Color,
    colors: FermixColors,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color)
                .border(FermixSpacing.hairline, colors.hairline, CircleShape),
        )
        Text(text = name, style = FermixType.labelSmall, color = colors.textSecondary)
    }
}

@Composable
private fun TypeScale(colors: FermixColors) {
    val styles =
        listOf(
            "display" to FermixType.display,
            "headline" to FermixType.headline,
            "title" to FermixType.title,
            "body" to FermixType.body,
            "bodyMedium" to FermixType.bodyMedium,
            "label" to FermixType.label,
            "labelSmall 09:41" to FermixType.labelSmall,
            "mono 4F2A 9C71" to FermixType.mono,
            "sas 481 062" to FermixType.sas,
        )
    for ((name, style) in styles) {
        Text(text = "$name ${metrics(style)}", style = style, color = colors.ink)
    }
}

/** A style's size over its line height, in sp, as section 13.1 writes them: 13.5/20, 16/24. */
private fun metrics(style: TextStyle): String {
    val size =
        style.fontSize.value
            .toString()
            .removeSuffix(".0")
    return "$size/${style.lineHeight.value.toInt()}"
}
