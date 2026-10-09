package io.tezra.fermix.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

// The focus ring (the M51 update's 1.3) as a specimen, drawn at the twelve windows of @FermixPreviews: a pill, a text
// button, a chip, a card, a switch's row and a row, ringed on each ground a control lies on. The chip is ringed at its
// own 36 dp edge, as the chips the app draws itself are (search's, the model's, a voice note's speed); Material's
// FilterChip, on Name, is ringed around its 48 dp target. A preview cannot focus six controls at once, so each ring is
// drawn shown (Modifier.ring, internal to design); the app's rings show only on the focus. Its words are the
// controls' names, not product copy, so they stay out of strings.xml.

/** A pill, a text button, a chip, a card, a switch's row and a row, ringed on the canvas, the agent bubble, the ink. */
@FermixPreviews
@Composable
fun SpecimenFocus() {
    FermixPreviewTheme {
        val colors = LocalFermixColors.current
        FermixColumn(ColumnWidth.Wide) {
            Column(modifier = Modifier.padding(SHEET_PADDING), verticalArrangement = Arrangement.spacedBy(GROUND_GAP)) {
                Ground(colors.canvas, colors.ink, RingOn.Surface)
                Ground(colors.agentBubble, colors.ink, RingOn.Surface)
                Ground(colors.ink, colors.onInk, RingOn.Ink)
            }
        }
    }
}

private val SHEET_PADDING = 16.dp
private val GROUND_GAP = 12.dp
private val GROUND_PADDING = 12.dp
private val CONTROL_GAP = 12.dp
private val CHIP_HEIGHT = 36.dp
private val ROW_HEIGHT = 48.dp

/** One ground, [fill], with each ringed control on it, its words in [ink]. */
@Composable
private fun Ground(
    fill: Color,
    ink: Color,
    on: RingOn,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(fill).padding(GROUND_PADDING),
        verticalArrangement = Arrangement.spacedBy(CONTROL_GAP),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP),
            verticalArrangement = Arrangement.spacedBy(CONTROL_GAP),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(fill, ink, on)
            TextAction(ink, on)
            Chip(ink, on)
            Card(fill, ink, on)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP)) {
            SwitchRow(fill, ink, on, Modifier.weight(1f))
            PlainRow(ink, on, Modifier.weight(1f))
        }
    }
}

/** A screen's primary pill, at least 48 dp tall: the ink with onInk on it, or on the ink, the reverse. */
@Composable
private fun Pill(
    fill: Color,
    ink: Color,
    on: RingOn,
) {
    Button(
        onClick = {},
        modifier =
            Modifier
                .heightIn(min = FermixSpacing.minTarget)
                .ring(FermixShapes.button, on, within = false, shown = true),
        colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = fill),
    ) { Text(text = "Button") }
}

/** A text action, underlined, at least 48 dp tall. */
@Composable
private fun TextAction(
    ink: Color,
    on: RingOn,
) {
    TextButton(
        onClick = {},
        modifier =
            Modifier
                .heightIn(min = FermixSpacing.minTarget)
                .ring(FermixShapes.button, on, within = false, shown = true),
        colors = ButtonDefaults.textButtonColors(contentColor = ink),
    ) { Text(text = "TextButton", textDecoration = TextDecoration.Underline) }
}

/** A chip, 36 dp in its 48 dp target, ringed at its own edge. */
@Composable
private fun Chip(
    ink: Color,
    on: RingOn,
) {
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .ring(FermixShapes.chip, on, within = false, shown = true)
                .heightIn(min = CHIP_HEIGHT)
                .border(FermixSpacing.hairline, ink, FermixShapes.chip)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text = "Chip", style = FermixType.label, color = ink) }
}

/** A card with an action, at the card's corner. */
@Composable
private fun Card(
    fill: Color,
    ink: Color,
    on: RingOn,
) {
    val colors = LocalFermixColors.current
    val card = if (fill == colors.agentBubble) colors.canvas else colors.agentBubble
    Box(
        modifier =
            Modifier
                .ring(FermixShapes.card, on, within = false, shown = true)
                .background(if (on == RingOn.Ink) ink else card, FermixShapes.card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) { Text(text = "Card", style = FermixType.body, color = if (on == RingOn.Ink) fill else colors.ink) }
}

/** A switch's row, the row the switch toggles, ringed inside its bounds. */
@Composable
private fun SwitchRow(
    fill: Color,
    ink: Color,
    on: RingOn,
    modifier: Modifier,
) {
    Row(
        modifier =
            modifier
                .heightIn(min = ROW_HEIGHT)
                .ring(ROW, on, within = true, shown = true)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Switch", style = FermixType.body, color = ink, modifier = Modifier.weight(1f))
        Switch(
            checked = true,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = ink, checkedThumbColor = fill),
        )
    }
}

/** A row, ringed inside its bounds. */
@Composable
private fun PlainRow(
    ink: Color,
    on: RingOn,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = ROW_HEIGHT)
                .ring(ROW, on, within = true, shown = true)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text = "Row", style = FermixType.body, color = ink) }
}

private val ROW = RoundedCornerShape(0.dp)
