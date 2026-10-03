package io.tezra.fermix.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors

/** Columns a table fits in the card's width; a wider one pans (design section 13.5). */
private const val FIT_COLUMNS = 4

/** A panned column's words run between these widths, and never narrower than their longest word, up to the last. */
private val PANNED_MIN = 64.dp
private val PANNED_MAX = 220.dp
private val PANNED_WORD_MAX = 320.dp

/** A cell's padding on each side. */
private val CELL_PADDING = 12.dp

/** A cell shows this many lines at most; a tap shows the rest. */
private const val CELL_LINES = 3

/** A figure: digits with their separators and a time's colons, a sign, a currency, a percent or a short unit. */
private val FIGURE = Regex("""^[-+−]?[$€£]?\d[\d.,:  ]*(%|\s?[A-Za-zµ]{1,3})?$""")

/** Whether a cell is a figure, set in mono and to the right (design section 13.5): "2184", "02:14", "60 s", "12.5%". */
fun isFigure(text: String): Boolean = FIGURE.matches(text.trim())

/**
 * A table card (design section 13.5): a flat grid on the canvas inside a hairline, its header 11/16 in the
 * second ink over a hairline, figures in mono and to the right; past four columns its columns take their words'
 * widths, filling the card when they need less and panning when they need more; a cell longer than three lines
 * opens whole on a tap.
 */
@Composable
internal fun TableCard(
    table: TableCells,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val measured = if (table.header.size > FIT_COLUMNS) pannedWidths(table) else null
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .border(FermixSpacing.hairline, colors.hairline, shape)
                .background(colors.canvas),
    ) {
        val widths = measured?.let { fittedWidths(it, maxWidth) }
        val grid = if (widths != null) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
        // A panning grid has no width to fill: its rules run the columns' width.
        val rule =
            widths?.let { Modifier.width(it.fold(0.dp) { sum, width -> sum + width }) } ?: Modifier.fillMaxWidth()
        Column(modifier = grid) {
            TableLine(table.header, header = true, widths = widths) { opened = it }
            table.rows.forEach { row ->
                Spacer(rule.height(FermixSpacing.hairline).background(colors.hairline))
                TableLine(row, header = false, widths = widths) { opened = it }
            }
        }
    }
    opened?.let { text -> WholeCell(text) { opened = null } }
}

/**
 * A wide table's column [widths] in a card [room] wide: as they are when they need the room or more, and each
 * given an equal share of what is left otherwise, so the grid and its rules fill the card (the canon's
 * `.tbl table{width:100%}`).
 */
fun fittedWidths(
    widths: List<Dp>,
    room: Dp,
): List<Dp> {
    require(room >= 0.dp) { "a card is never narrower than nothing" }
    val total = widths.fold(0.dp) { sum, width -> sum + width }
    if (widths.isEmpty() || total >= room) return widths
    val share = (room - total) / widths.size
    return widths.map { it + share }
}

/**
 * A panned table's column widths, from their words: each column as wide as its widest cell on one line, kept
 * between [PANNED_MIN] and [PANNED_MAX], and never narrower than its longest word (up to [PANNED_WORD_MAX]), so
 * a word never breaks; measured at the font scale the owner reads at.
 */
@Composable
private fun pannedWidths(table: TableCells): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(table, measurer, density) {
        table.header.indices.map { column ->
            val cells = listOf(table.header[column] to true) + table.rows.map { it.getOrElse(column) { "" } to false }
            val widest = cells.maxOf { (text, header) -> measurer.measure(text, cellStyle(text, header)).size.width }
            val word =
                cells.maxOf { (text, header) ->
                    text.split(' ').maxOf { measurer.measure(it, cellStyle(text, header)).size.width }
                }
            with(density) {
                val words = widest.toDp().coerceIn(PANNED_MIN, PANNED_MAX)
                maxOf(words, word.toDp().coerceAtMost(PANNED_WORD_MAX)) + CELL_PADDING * 2
            }
        }
    }
}

/** A cell's type: the header's 11/16, a figure's mono, a word's body. Colours are the cell's own. */
private fun cellStyle(
    text: String,
    header: Boolean,
): TextStyle =
    when {
        header -> FermixType.labelSmall
        isFigure(text) -> FermixType.mono
        else -> FermixType.bodyMedium
    }

@Composable
private fun TableLine(
    cells: List<String>,
    header: Boolean,
    widths: List<Dp>?,
    onOpen: (String) -> Unit,
) {
    Row(modifier = if (widths != null) Modifier else Modifier.fillMaxWidth()) {
        cells.forEachIndexed { index, text ->
            // A row with more cells than its header gives the extra ones the narrowest column.
            val width = widths?.let { Modifier.width(it.getOrElse(index) { PANNED_MIN + CELL_PADDING * 2 }) }
            Cell(text, header, width ?: Modifier.weight(1f), onOpen)
        }
    }
}

@Composable
private fun Cell(
    text: String,
    header: Boolean,
    modifier: Modifier,
    onOpen: (String) -> Unit,
) {
    val colors = LocalFermixColors.current
    var cut by remember(text) { mutableStateOf(false) }
    val figure = !header && isFigure(text)
    val ink = if (header) colors.inkSecondary else colors.ink
    Text(
        text = text,
        style = cellStyle(text, header).copy(color = ink),
        textAlign = if (figure) TextAlign.End else TextAlign.Start,
        maxLines = CELL_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { cut = it.hasVisualOverflow },
        modifier =
            modifier
                .then(if (cut) Modifier.clickable(role = Role.Button) { onOpen(text) } else Modifier)
                .padding(horizontal = CELL_PADDING, vertical = 8.dp),
    )
}

@Composable
private fun WholeCell(
    text: String,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.chat_cell_close)) } },
        text = { Text(text, style = FermixType.body) },
    )
}
