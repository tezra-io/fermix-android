package io.tezra.fermix.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.rowFocusRing

/**
 * The screen's frame (design sections 13.5 and 13.6): [body] fills the window above the dock, so the timeline
 * never scrolls under it; [scrim] covers the whole window, the app bar and the dock's row included; [sheet],
 * the palette's head, stands on the dock over the body, so opening it covers the timeline without shortening
 * it; and [dock] sits at the window's bottom over the scrim. Drawn in that order.
 */
@Composable
internal fun ChatFrame(
    body: @Composable () -> Unit,
    scrim: @Composable () -> Unit,
    sheet: @Composable () -> Unit,
    dock: @Composable () -> Unit,
) {
    Layout(contents = listOf(body, scrim, sheet, dock)) { slots, constraints ->
        val (bodies, scrims, sheets) = slots
        val docks = slots[3]
        require(constraints.hasBoundedHeight && constraints.hasBoundedWidth) { "The chat fills its window." }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val docked = docks.map { it.measure(Constraints(maxWidth = width, maxHeight = height)) }
        val dockTop = (height - (docked.maxOfOrNull { it.height } ?: 0)).coerceAtLeast(0)
        val bodyPlaced = bodies.map { it.measure(Constraints.fixed(width, dockTop)) }
        val scrimPlaced = scrims.map { it.measure(Constraints.fixed(width, height)) }
        val sheetPlaced = sheets.map { it.measure(Constraints(maxWidth = width, maxHeight = dockTop)) }
        layout(width, height) {
            bodyPlaced.forEach { it.place(0, 0) }
            scrimPlaced.forEach { it.place(0, 0) }
            sheetPlaced.forEach { it.place(0, dockTop - it.height) }
            docked.forEach { it.place(0, dockTop) }
        }
    }
}

/** The scrim under the palette's sheet; a tap on it closes the palette. */
@Composable
internal fun Scrim(onClose: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(LocalFermixColors.current.scrim)
                .rowFocusRing()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
    )
}

/**
 * The palette's head (the canon's `.sheet` above its `.cmp`): the grab bar, the commands the field filters and
 * the footer, in the 640 dp column, the sheet's tone with its 28 dp top corners.
 */
@Composable
internal fun PaletteSheet(
    ui: ChatUi,
    actions: ChatScreenActions,
) {
    FermixColumn(ColumnWidth.Wide) {
        val query = paletteQuery(ui.field.text).orEmpty()
        Palette(paletteCommands(ui.state.commands, query), ui.state.header.record.host, actions.onPick)
    }
}

/**
 * The dock (design section 13.6): the composer in the 640 dp column above the navigation bar and the keyboard,
 * the model's hint over it (ComposerHint). While the palette is open it is the sheet's foot: the sheet's tone
 * runs behind it to the window's bottom edge and the field sits on the canvas inside it (the canon's
 * `.sheet .cmp`), the scrim beside it. While search is open it is search's: nothing in its list, and the
 * stepping bar in the chat (design section 13.7).
 */
@Composable
internal fun Dock(
    ui: ChatUi,
    actions: ChatScreenActions,
) {
    val search = ui.search
    if (search != null) {
        if (search.mode == SearchMode.IN_CHAT) StepBar(search, actions.search)
        return
    }
    val sheet = if (ui.palette) Modifier.background(LocalFermixColors.current.tonalSolid) else Modifier
    val state = ui.state
    FermixColumn(ColumnWidth.Wide) {
        Column(modifier = sheet.navigationBarsPadding().imePadding()) {
            // The palette hides the model's hint, never why Send sends nothing: one word after a "/" opens it.
            if (!ui.palette || ui.tooLong) ComposerHint(state.model, state.switchPending, ui.tooLong)
            val nothingToSend =
                ui.field.text.isBlank() &&
                    ui.media.attach.picked
                        .isEmpty()
            val look =
                ComposerLook(
                    state.header.record.title,
                    stops = state.turnRuns && nothingToSend,
                    chip = state.model,
                    media = ui.media,
                    written = ui.written,
                )
            Composer(ui.field, look, actions.composer, inSheet = ui.palette)
        }
    }
}
