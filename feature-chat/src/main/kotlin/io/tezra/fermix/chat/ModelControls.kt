package io.tezra.fermix.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors

/** The chip at the config's default (the canon's `.mc.def`), and disabled with no connection (`.mc.off`). */
private const val DEFAULT_ALPHA = 0.7f
internal const val OFF_ALPHA = 0.38f

/** The canon's `.mc`, 500 13/20, and its glyph's `.gl`, 600 10/15 (set in dp: GLYPH_SIZE). */
private val CHIP_STYLE = FermixType.label.copy(fontSize = 13.sp, lineHeight = 20.sp)
private val GLYPH_STYLE =
    FermixType.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 15.sp,
    )

/** The glyph's ring, and the letter's size and line in dp: in sp, 200 % type would spill it out of its ring. */
private val GLYPH_RING = 18.dp
private val GLYPH_SIZE = 10.dp
private val GLYPH_LINE = 15.dp

/** The sheet's title (`.sheet h5`, 600 16/24) and its sentence (`.subt`, 400 13/18). */
private val SHEET_TITLE = FermixType.title.copy(fontSize = 16.sp, lineHeight = 24.sp)
private val SHEET_SUBTITLE = FermixType.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)

/** Where the sheet's sentence holds the Fermix's name, which [italicName] sets in italic: a private-use char. */
internal const val NAME_MARK = "\uE000"

/** The sheet's sentence, [marked] with [NAME_MARK] where the Fermix's [name] goes, the name in italic (`<i>`). */
internal fun italicName(
    marked: String,
    name: String,
): AnnotatedString {
    val parts = marked.split(NAME_MARK, limit = 2)
    return buildAnnotatedString {
        append(parts.first())
        if (parts.size < 2) return@buildAnnotatedString
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(name) }
        append(parts.last())
    }
}

/**
 * The model chip in the composer's second row (design section 8.6; the canon's `.mc`): the provider's glyph in a
 * ring, the model's short name and ▾; at 70 % while the chat runs the config's default, tonal-filled with the
 * accent's dot once it has its own, and at 38 %, taking no press, with no connection: the one control that is
 * disabled, the hint above the composer saying why.
 */
@Composable
internal fun ModelChipButton(
    chip: ModelChip,
    onOpen: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val alpha =
        when {
            !chip.enabled -> OFF_ALPHA
            chip.overridden -> 1f
            else -> DEFAULT_ALPHA
        }
    val label = stringResource(R.string.chat_model_chip, chip.label)
    val offline = stringResource(R.string.chat_connect_to_change_model)
    Row(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .alpha(alpha)
                .clip(FermixShapes.chip)
                .background(if (chip.overridden) colors.hairline else Color.Transparent)
                .clickable(enabled = chip.enabled, role = Role.Button, onClick = onOpen)
                .heightIn(min = 32.dp)
                .padding(start = 6.dp, end = 8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    if (!chip.enabled) stateDescription = offline
                },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyph =
            with(LocalDensity.current) {
                GLYPH_STYLE.copy(fontSize = GLYPH_SIZE.toSp(), lineHeight = GLYPH_LINE.toSp())
            }
        Box(
            modifier = Modifier.size(GLYPH_RING).border(1.5.dp, colors.ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(chip.glyph, style = glyph, color = colors.ink, textAlign = TextAlign.Center) }
        Text(chip.label, style = CHIP_STYLE, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chip.overridden) Box(Modifier.size(6.dp).background(colors.accentInk, CircleShape))
        Icon(painterResource(R.drawable.ic_chat_down), null, tint = colors.ink, modifier = Modifier.size(16.dp))
    }
}

/**
 * The line above the composer (the canon's `.hint`): that the field holds more than one message carries while it
 * does ([tooLong]), which is why Send sends nothing; else "Connect to change the model" while the chip is disabled,
 * else "Switches after this reply" while a model picked during the turn waits for it; nothing otherwise.
 */
@Composable
internal fun ComposerHint(
    chip: ModelChip?,
    switchPending: Boolean,
    tooLong: Boolean = false,
) {
    val words =
        when {
            tooLong -> stringResource(R.string.chat_too_long)
            chip != null && !chip.enabled -> stringResource(R.string.chat_connect_to_change_model)
            chip != null && switchPending -> stringResource(R.string.chat_switches_after_reply)
            else -> return
        }
    Text(
        words,
        style = FermixType.labelSmall,
        color = LocalFermixColors.current.inkSecondary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
    )
}

/**
 * The "Model" sheet (design section 8.6, verbatim): its title and sentence, then its rows (sheetRowsOf), the
 * default first, each provider's models under its name with "no live typing" for a route that does not
 * stream, and ✓ on the chat's. A provider the daemon could not list is greyed with its one sentence. A pick
 * closes the sheet and sends; the row already chosen only closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheetView(
    sheet: ModelSheet,
    state: ChatScreenState,
    actions: ModelActions,
) {
    ModalBottomSheet(onDismissRequest = actions.onClose, containerColor = LocalFermixColors.current.tonalSolid) {
        ModelSheetContent(sheet, state, actions)
    }
}

/** What the "Model" sheet holds: its title, its sentence and its rows, scrolling as the window needs. */
@Composable
internal fun ModelSheetContent(
    sheet: ModelSheet,
    state: ChatScreenState,
    actions: ModelActions,
) {
    val colors = LocalFermixColors.current
    val record = state.header.record
    val rows = sheetRowsOf(sheet, record.caps?.modelState, state.model?.overridden == true)
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(
            stringResource(R.string.chat_model_title),
            style = SHEET_TITLE,
            color = colors.ink,
            modifier = Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp),
        )
        Text(
            italicName(stringResource(R.string.chat_model_subtitle, NAME_MARK), record.title),
            style = SHEET_SUBTITLE,
            color = colors.inkSecondary,
            modifier = Modifier.padding(start = 24.dp, top = 2.dp, end = 24.dp, bottom = 8.dp),
        )
        if (sheet == ModelSheet.Loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
        }
        rows.forEach { row -> SheetRow(row, record.host, actions) }
    }
}

@Composable
private fun SheetRow(
    row: ModelRow,
    host: String,
    actions: ModelActions,
) {
    when (row) {
        is ModelRow.Default -> {
            Choice(stringResource(R.string.chat_model_default, row.label), null, row.active, actions) { row }
        }

        is ModelRow.Choice -> {
            val label = row.trait?.let { stringResource(R.string.chat_model_trait, row.label, it) } ?: row.label
            val under = if (row.liveTyping) null else stringResource(R.string.chat_model_no_live_typing)
            Choice(label, under, row.active, actions) { row }
        }

        is ModelRow.Group -> {
            GroupLine(row)
        }

        ModelRow.Unlisted -> {
            Text(
                stringResource(R.string.chat_model_unlisted, host),
                style = FermixType.body,
                color = LocalFermixColors.current.inkTertiary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun GroupLine(group: ModelRow.Group) {
    val colors = LocalFermixColors.current
    Text(
        group.name,
        style = FermixType.labelSmall,
        color = if (group.unavailable) colors.inkTertiary else colors.inkSecondary,
        modifier = Modifier.padding(start = 24.dp, top = 10.dp, end = 24.dp, bottom = 2.dp),
    )
}

/** One row to pick (the canon's `.sr`): its words, the line under them, and ✓ on the chosen one. */
@Composable
private fun Choice(
    words: String,
    under: String?,
    active: Boolean,
    actions: ModelActions,
    pick: () -> ModelRow,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = active, role = Role.RadioButton) {
                    if (active) actions.onClose() else actions.onPick(pick())
                }.heightIn(min = 52.dp)
                .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(words, style = FermixType.body, color = colors.ink)
            under?.let { Text(it, style = SHEET_SUBTITLE, color = colors.inkSecondary) }
        }
        if (active) Icon(painterResource(R.drawable.ic_chat_check), null, tint = colors.accentInk)
    }
}
