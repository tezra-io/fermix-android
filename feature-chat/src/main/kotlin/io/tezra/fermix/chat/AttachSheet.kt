package io.tezra.fermix.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import java.util.Locale
import kotlin.math.roundToInt

/** The sheet's share of the window (design section 13.6, "modal sheet at 60%"). */
const val ATTACH_SHEET_SHARE = 0.6f

/**
 * The photos' grid at its shortest: a full row of the picker's tiles at a phone's width with room to scroll it.
 * Where the window's 60 % leaves the grid less, in a short window or in large type, the sheet grows past its share.
 */
private val GRID_FLOOR = 160.dp

/** A chip of the sheet (the canon's `.cchip`): 36 dp, 10 dp corners, a hairline on the canvas, 500 14/20. */
private val CHIP_TYPE = FermixType.label.copy(fontSize = 14.sp, lineHeight = 20.sp)

/** The inline size error (the canon's `.inl`, 400 13/18). */
private val INLINE_TYPE = FermixType.body.copy(fontSize = 13.sp, lineHeight = 18.sp)

/** The caption field (the canon's `.sfld`): 16 dp corners, a hairline on the canvas. */
private val FIELD_SHAPE = RoundedCornerShape(16.dp)

/** "Send as files"' box (the canon's `.cbx`): 20 dp, 6 dp corners, a 2 dp rule. */
private val BOX_SHAPE = RoundedCornerShape(6.dp)

/**
 * The attach sheet (design section 13.6, the canon's "Attach sheet"): a modal sheet at 60 % of the window, dragged
 * down to close; its body is [AttachSheetContent] over the [photos] grid, the embedded Photo Picker's on the phone
 * (ChatOutside.photos).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(
    attach: AttachUi,
    field: TextFieldValue,
    tooLong: Boolean,
    actions: ComposerActions,
    photos: @Composable (AttachUi, AttachActions, Modifier) -> Unit,
) {
    val colors = LocalFermixColors.current
    ModalBottomSheet(
        onDismissRequest = actions.attach.onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = FermixShapes.sheet,
        containerColor = colors.tonalSolid,
    ) {
        AttachSheetContent(attach, field, actions, tooLong) { photos(attach, actions.attach, it) }
    }
}

/**
 * The sheet's body: the chips Camera · Files · Paste, never tabs, scrolled sideways where large type outgrows the
 * row; the photos' grid with its numbered multi-select ([photos]), which draws the photos picked in it; the tray
 * with its ✕ for every other item the send holds, files, a paste, the keyboard's, so "Send {n}" counts nothing the
 * sheet does not show; the first item too big to go, inline, and a caption past what one message carries
 * ([tooLong]); the caption, one per send, which is the composer's words; "Send as files" and "Send {n}".
 */
@Composable
internal fun AttachSheetContent(
    attach: AttachUi,
    field: TextFieldValue,
    actions: ComposerActions,
    tooLong: Boolean = false,
    photos: @Composable (Modifier) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val others = attach.picked.filter { it.from != PickedFrom.PHOTOS }
    SheetBody(
        chips = { SheetChips(actions.attach) },
        grid = { photos(Modifier.fillMaxSize().padding(horizontal = 2.dp)) },
        footer = {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                if (others.isNotEmpty()) {
                    Box(modifier = Modifier.padding(horizontal = 10.dp)) {
                        Tray(others, actions.attach.thumbnail, actions.attach.onRemove)
                    }
                }
                attach.tooBig?.let { TooBigLine(it, locale) }
                if (tooLong) InlineLine(stringResource(R.string.chat_too_long))
                CaptionField(field, actions.attach.onCaption)
                SendRow(attach, actions)
            }
        },
    )
}

/**
 * The sheet's [chips], [grid] and [footer], top to bottom, its share of the window tall: the grid takes what the
 * chips and the footer leave of it, at least [GRID_FLOOR], the sheet growing past its share up to the whole window
 * where that is less. The window's height bounds it.
 */
@Composable
private fun SheetBody(
    chips: @Composable () -> Unit,
    grid: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    Layout(contents = listOf(chips, grid, footer), modifier = Modifier.fillMaxWidth()) { parts, constraints ->
        require(constraints.hasBoundedHeight) { "the attach sheet is measured in a window of a known height" }
        val (top, middle, bottom) = parts
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val head = top.single().measure(loose)
        val foot = bottom.single().measure(loose)
        val rest = head.height + foot.height
        val share = (constraints.maxHeight * ATTACH_SHEET_SHARE).roundToInt() - rest
        val height = maxOf(GRID_FLOOR.roundToPx(), share).coerceAtMost((constraints.maxHeight - rest).coerceAtLeast(0))
        val body = middle.single().measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(constraints.maxWidth, rest + height) {
            head.place(0, 0)
            body.place(0, head.height)
            foot.place(0, head.height + height)
        }
    }
}

/** The chips Camera · Files · Paste, scrolled sideways where large type outgrows the row. */
@Composable
private fun SheetChips(actions: AttachActions) {
    Row(
        modifier =
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetChip(R.drawable.ic_chat_camera, stringResource(R.string.chat_camera), actions.onCamera)
        SheetChip(R.drawable.ic_chat_file, stringResource(R.string.chat_files), actions.onFiles)
        SheetChip(R.drawable.ic_chat_paste, stringResource(R.string.chat_paste), actions.onPaste)
    }
}

/** A chip of the sheet: its glyph and its word, 36 dp tall in a 48 dp target. */
@Composable
private fun SheetChip(
    icon: Int,
    words: String,
    onClick: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .heightIn(min = 36.dp)
                .clip(FermixShapes.chip)
                .background(colors.canvas)
                .border(1.dp, colors.hairline, FermixShapes.chip)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.size(20.dp))
        Text(words, style = CHIP_TYPE, color = colors.ink, maxLines = 1)
    }
}

/** "{name} is {size} — the limit is {max}." (design section 13.9), under the grid. */
@Composable
private fun TooBigLine(
    tooBig: TooBig,
    locale: Locale,
) {
    val words =
        stringResource(
            R.string.chat_too_big,
            tooBig.name,
            sizeText(tooBig.sizeBytes, locale),
            sizeText(tooBig.maxBytes, locale),
        )
    InlineLine(words)
}

/** An inline line under the grid (the canon's `.inl`): what keeps the send from going. */
@Composable
private fun InlineLine(words: String) {
    Text(
        words,
        style = INLINE_TYPE,
        color = LocalFermixColors.current.ink,
        modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
    )
}

/** The caption (the canon's `.sfld`): the composer's words, "Caption" while there are none. */
@Composable
private fun CaptionField(
    field: TextFieldValue,
    onCaption: (TextFieldValue) -> Unit,
) {
    val colors = LocalFermixColors.current
    val placeholder = stringResource(R.string.chat_caption)
    BasicTextField(
        value = field,
        onValueChange = onCaption,
        textStyle = FermixType.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accentInk),
        maxLines = 3,
        modifier =
            Modifier
                .padding(start = 16.dp, top = 8.dp, end = 16.dp)
                .fillMaxWidth()
                .clip(FIELD_SHAPE)
                .background(colors.canvas)
                .border(1.dp, colors.hairline, FIELD_SHAPE)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box {
                if (field.text.isEmpty()) Text(placeholder, style = FermixType.body, color = colors.inkSecondary)
                inner()
            }
        },
    )
}

/** "Send as files" with its box, and "Send {n}", which sends every item but those too big. */
@Composable
private fun SendRow(
    attach: AttachUi,
    actions: ComposerActions,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .toggleable(attach.asFiles, role = Role.Checkbox, onValueChange = actions.attach.onAsFiles),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilesBox(attach.asFiles)
            Text(stringResource(R.string.chat_send_as_files), style = CHIP_TYPE, color = colors.ink)
        }
        Spacer(Modifier.weight(1f))
        val view = LocalView.current
        Button(
            onClick = { actions.onSend { HapticFeedback.perform(view, HapticUse.Send) } },
            enabled = attach.sendable > 0,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            shape = FermixShapes.button,
        ) {
            Text(stringResource(R.string.chat_send_count, attach.sendable), style = FermixType.label, maxLines = 1)
        }
    }
}

/** The box of "Send as files": a rule while off, the accent with its tick while on. */
@Composable
private fun FilesBox(on: Boolean) {
    val colors = LocalFermixColors.current
    val box = Modifier.size(20.dp).clip(BOX_SHAPE)
    if (!on) {
        Box(modifier = box.border(2.dp, colors.inkSecondary, BOX_SHAPE))
        return
    }
    Box(modifier = box.background(colors.accent), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_chat_check), null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
    }
}

/**
 * The grid's place where the embedded Photo Picker cannot draw (an SDK extension below 15): one tile that opens
 * the system Photo Picker ([AttachActions.onPhotos], PickMultipleVisualMedia), which needs no media permission, and
 * under its word the photos picked through it, each with its ✕, as the embedded picker would draw them.
 */
@Composable
internal fun PhotosTile(
    attach: AttachUi,
    actions: AttachActions,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    val words = stringResource(R.string.chat_photos)
    val photos = attach.picked.filter { it.from == PickedFrom.PHOTOS }
    Box(modifier = modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clip(FermixShapes.card)
                    .background(colors.agentBubble)
                    .clickable(role = Role.Button, onClick = actions.onPhotos),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Icon(painterResource(R.drawable.ic_chat_image), null, tint = colors.ink, modifier = Modifier.size(32.dp))
            Text(words, style = CHIP_TYPE, color = colors.ink)
            if (photos.isNotEmpty()) Tray(photos, actions.thumbnail, actions.onRemove)
        }
    }
}
