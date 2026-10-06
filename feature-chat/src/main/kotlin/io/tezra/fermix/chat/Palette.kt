package io.tezra.fermix.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.protocol.CommandDescriptor

/** A command's row at its least: 52 dp, a 48 dp target with room around its two lines. */
private val COMMAND_ROW = 52.dp

/**
 * How tall the palette's list grows before it scrolls: the daemon's usual seven commands whole, as the canon's
 * palette lists them, and half of an eighth, so a list that scrolls shows a row cut and faded, never a whole
 * one that reads as disabled.
 */
private val PALETTE_HEIGHT = COMMAND_ROW * 7.5f

/** A command's name, 500 14/20 mono (the canon's `.sr .cmd`). */
private val COMMAND_STYLE = FermixType.mono.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium)

/** A command's description, 400 13/18 (the canon's `.sr .mn span`). */
private val DESCRIPTION_STYLE = FermixType.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)

/** How far up from its end the palette's list fades while more of it lies below. */
private val PALETTE_FADE = 32.dp

/**
 * The slash palette (design section 13.6): the dock opens into a sheet, its grab bar on top, that lists
 * [commands], the daemon's `caps.commands` as the field filters them, `/stop` in the error colour, with the
 * footer "From {host} · updates automatically"; the composer is the sheet's foot (Dock). A pick puts "/name "
 * in the field. While more rows lie below, the list's end fades, so no row reads as cut.
 */
@Composable
internal fun Palette(
    commands: List<CommandDescriptor>,
    host: String,
    onPick: (CommandDescriptor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    val label = stringResource(R.string.chat_palette)
    val list = rememberLazyListState()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.tonalSolid, FermixShapes.sheet)
                .padding(bottom = 8.dp)
                .semantics { contentDescription = label },
    ) {
        GrabBar()
        LazyColumn(state = list, modifier = Modifier.heightIn(max = PALETTE_HEIGHT).fadedEnd(list.canScrollForward)) {
            items(commands, key = { it.name }) { command -> CommandRow(command, onPick) }
        }
        Text(
            text = stringResource(R.string.chat_palette_footer, host),
            style = FermixType.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp),
        )
    }
}

/** The sheet's grab bar (the canon's `.grab`): 32 × 4 dp in the third ink, 10 dp from the top, 6 dp above the list. */
@Composable
private fun GrabBar() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(width = 32.dp, height = 4.dp)
                    .background(LocalFermixColors.current.inkTertiary, RoundedCornerShape(2.dp)),
        )
    }
}

/** Fades the content's last [PALETTE_FADE] out while [faded]. */
private fun Modifier.fadedEnd(faded: Boolean): Modifier =
    if (!faded) {
        this
    } else {
        graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            val fade = PALETTE_FADE.toPx()
            val ends = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), size.height - fade, size.height)
            drawRect(ends, blendMode = BlendMode.DstIn)
        }
    }

@Composable
private fun CommandRow(
    command: CommandDescriptor,
    onPick: (CommandDescriptor) -> Unit,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = COMMAND_ROW)
                .clickable(role = Role.Button) { onPick(command) }
                .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val danger = command.name == STOP_COMMAND
        Text("/${command.name}", style = COMMAND_STYLE, color = if (danger) colors.errText else colors.ink)
        Text(
            command.description,
            style = DESCRIPTION_STYLE,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}
