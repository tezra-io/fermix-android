package io.tezra.fermix.chat

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.Sender
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A hit older than this many days shows its date, not its weekday. */
private const val WEEKDAY_DAYS = 6L

/** Hits from the list's end at which the next older page is asked for. */
private const val MORE_AT = 3

/** The search bar (the canon's `.sbar`) and the stepping bar (`.stepb`), and the field's 400 16/24. */
private val SEARCH_BAR_HEIGHT = 64.dp
private val STEP_BAR_HEIGHT = 56.dp
private val QUERY_STYLE = FermixType.body.copy(fontSize = 16.sp, lineHeight = 24.sp)

/**
 * The search bar in place of the chat's (design section 13.7): back, the query, and ✕ once there is one to
 * clear. The field takes the focus as search opens in its list; nothing of what it holds is logged. The field
 * keeps its caret and selection across a rotation; a query it did not type itself (the one kept as the screen
 * comes back, a clear, the 256-scalar cut) puts the caret after the query's last character. What it saves is the
 * query search took, never more: a paste of any length is cut before it is kept, as a saved state goes through the
 * binder as the app stops.
 */
@Composable
internal fun SearchBar(
    search: SearchUi,
    actions: SearchActions,
) {
    val colors = LocalFermixColors.current
    val focus = remember { FocusRequester() }
    val label = stringResource(R.string.chat_search)
    val atEnd = TextFieldValue(search.query, TextRange(search.query.length))
    var typed by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(atEnd) }
    val field = if (typed.text == search.query) typed else atEnd
    Column(modifier = Modifier.background(colors.tonal)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = SEARCH_BAR_HEIGHT).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(
                    painterResource(R.drawable.ic_chat_back),
                    stringResource(R.string.chat_search_close),
                    tint = colors.ink,
                )
            }
            BasicTextField(
                value = field,
                onValueChange = { next ->
                    val query = boundedQuery(next.text)
                    typed = if (query == next.text) next else TextFieldValue(query, TextRange(query.length))
                    if (next.text != search.query) actions.onQuery(next.text)
                },
                textStyle = QUERY_STYLE.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.ink),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box {
                        if (search.query.isEmpty()) Text(label, style = QUERY_STYLE, color = colors.textSecondary)
                        inner()
                    }
                },
            )
            if (search.query.isNotEmpty()) {
                IconButton(onClick = { actions.onQuery("") }) {
                    Icon(
                        painterResource(R.drawable.ic_chat_x),
                        stringResource(R.string.chat_search_clear),
                        tint = colors.ink,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.fillMaxWidth().height(FermixSpacing.hairline).background(colors.hairline))
    }
    LaunchedEffect(search.mode) { if (search.mode == SearchMode.LIST) focus.requestFocus() }
}

/**
 * Search's list (design section 13.7, list mode): the pinned line while the hits are the phone's cache alone,
 * the chips, then the hits newest first, the next older page asked for as the list nears its end, and after
 * them, once the daemon's search failed, "Couldn't search {host}" with "Try again". A hit's tap opens the chat
 * at it.
 */
@Composable
internal fun SearchList(
    search: SearchUi,
    context: TimelineContext,
    actions: SearchActions,
) {
    val list = rememberLazyListState()
    LaunchedEffect(list, search.hits.size) {
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.distinctUntilChanged()
            .filter { it >= search.hits.size - MORE_AT }
            .collect { actions.onMore() }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (search.cachedOnly) PinnedLine()
        Chips(search.chip, actions.onChip)
        LazyColumn(state = list, modifier = Modifier.weight(1f).navigationBarsPadding().imePadding()) {
            itemsIndexed(search.hits, key = { index, hit -> "${hit.serverSeq}:$index" }) { index, hit ->
                HitRow(hit, context) { actions.onPick(index) }
            }
            if (search.failed) item(key = "failed") { FailedLine(context.host, actions.onRetry) }
        }
    }
}

/** "Cached messages only — connect to search everything" (the canon's `.pin`). */
@Composable
private fun PinnedLine() {
    val colors = LocalFermixColors.current
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        colors.agentBubble,
                    ).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_chat_info),
                null,
                tint = colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.chat_search_cached_only),
                style = FermixType.labelSmall,
                color = colors.textSecondary,
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth().height(FermixSpacing.hairline).background(colors.hairline))
    }
}

/**
 * "Couldn't search {host}" and "Try again", underlined, as the M51 update's reference player draws a text button:
 * the daemon's search, or its older page, came to nothing.
 */
@Composable
private fun FailedLine(
    host: String,
    onRetry: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.chat_search_failed, host),
            style = FermixType.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(
                stringResource(R.string.chat_try_again),
                style = FermixType.label,
                color = colors.ink,
                textDecoration = TextDecoration.Underline,
            )
        }
    }
}

/** All · Media · Files · Links (the canon's `.cchips`): the chosen one in the ink. */
@Composable
private fun Chips(
    chosen: SearchChip,
    onChip: (SearchChip) -> Unit,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchChip.entries.forEach { chip ->
            val on = chip == chosen
            Text(
                chipWords(chip),
                style = FermixType.label,
                color = if (on) colors.onInk else colors.ink,
                modifier =
                    Modifier
                        .heightIn(min = 36.dp)
                        .border(FermixSpacing.hairline, if (on) colors.ink else colors.hairline, FermixShapes.chip)
                        .background(if (on) colors.ink else colors.canvas, FermixShapes.chip)
                        .selectable(selected = on, role = Role.RadioButton) { onChip(chip) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun chipWords(chip: SearchChip): String =
    when (chip) {
        SearchChip.ALL -> stringResource(R.string.chat_search_all)
        SearchChip.MEDIA -> stringResource(R.string.chat_search_media)
        SearchChip.FILES -> stringResource(R.string.chat_search_files)
        SearchChip.LINKS -> stringResource(R.string.chat_search_links)
    }

/** One hit (the canon's `.hit`): whose and when, then its words with the matches washed. */
@Composable
private fun HitRow(
    hit: ShownHit,
    context: TimelineContext,
    onPick: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val who = if (hit.sender == Sender.User) R.string.chat_you else R.string.chat_search_agent
    Column(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onPick)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(who),
                    style = FermixType.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                hit.wallMs?.let {
                    Text(
                        hitTime(it, context),
                        style = FermixType.labelSmall,
                        color = colors.textSecondary,
                    )
                }
            }
            Text(
                marked(hit, SpanStyle(background = colors.selection)),
                style = FermixType.bodyMedium,
                color = colors.ink,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth().height(FermixSpacing.hairline).background(colors.hairline))
    }
}

/** [hit]'s excerpt with each of its marks in [style]; a mark past the excerpt's end is cut at it. */
internal fun marked(
    hit: ShownHit,
    style: SpanStyle,
): AnnotatedString =
    buildAnnotatedString {
        append(hit.excerpt)
        hit.marks.forEach { range ->
            val end = minOf(range.last + 1, hit.excerpt.length)
            if (range.first in 0 until end) addStyle(style, range.first, end)
        }
    }

/** When a hit was, as the canon's list says it: the time today, the weekday this week, else the day and month. */
private fun hitTime(
    wallMs: Long,
    context: TimelineContext,
): String {
    val day = Instant.ofEpochMilli(wallMs).atZone(context.zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, context.today)
    if (days == 0L) return timeOf(wallMs, context)
    val skeleton = if (days in 1..WEEKDAY_DAYS) "EEE" else "dMMM"
    return DateTimeFormatter
        .ofPattern(
            DateFormat.getBestDateTimePattern(context.locale, skeleton),
            context.locale,
        ).format(day)
}

/**
 * The stepping bar (the canon's `.stepb`) in place of the composer while search shows the chat, its tone across
 * the window and its row in the 640 dp column as the composer's: "3 / 12", ▲ to the older hit, the next page
 * asked for at the loaded end, and ▼ to the newer; a step there is none of is greyed, as it takes no press.
 */
@Composable
internal fun StepBar(
    search: SearchUi,
    actions: SearchActions,
) {
    val colors = LocalFermixColors.current
    val older = search.step < search.hits.lastIndex || search.more
    val newer = search.step > 0
    Column(modifier = Modifier.fillMaxWidth().background(colors.tonal).navigationBarsPadding()) {
        Spacer(modifier = Modifier.fillMaxWidth().height(FermixSpacing.hairline).background(colors.hairline))
        FermixColumn(ColumnWidth.Wide) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = STEP_BAR_HEIGHT).padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.chat_search_step, search.step + 1, search.hits.size),
                    style = FermixType.mono.copy(fontSize = 14.sp),
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                StepButton(R.drawable.ic_chat_up, R.string.chat_search_older, older) { actions.onStep(true) }
                StepButton(R.drawable.ic_chat_down, R.string.chat_search_newer, newer) { actions.onStep(false) }
            }
        }
    }
}

/** ▲ or ▼: in the ink while it steps, greyed at the chip's off opacity while there is no hit that way. */
@Composable
private fun StepButton(
    icon: Int,
    label: Int,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val ink = LocalFermixColors.current.ink
    IconButton(onClick = onStep, enabled = enabled) {
        Icon(painterResource(icon), stringResource(label), tint = if (enabled) ink else ink.copy(alpha = OFF_ALPHA))
    }
}
