package io.tezra.fermix.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.CommandDescriptor
import io.tezra.fermix.protocol.Route
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The screen as it reads: its state, the composer's field, whether the palette shows, search while it is open,
 * the "Model" sheet while it is, and the latest jump to a search hit, which the list scrolls to and pulses.
 */
data class ChatUi(
    val state: ChatScreenState,
    val field: TextFieldValue,
    val palette: Boolean,
    val search: SearchUi? = null,
    val sheet: ModelSheet? = null,
    val jump: Jump? = null,
)

/**
 * What the screen's owner does for it: leave, open the Instance screen (its title, and the unreachable banner
 * at the Connection section), the composer, the palette's pick and close, an error card's action, an outbox
 * item's tap menu, the list reaching its ends, and the newest row it lists while on screen; [info] and
 * [modelOf] read what Info shows; [nowMono] is the indicator's clock; [text] copies and shares; [cards] answers
 * approvals and opens previews; [models] runs the model chip's sheet, and [search] search.
 */
data class ChatScreenActions(
    val onBack: () -> Unit,
    val onInstance: () -> Unit,
    val composer: ComposerActions,
    val onPick: (CommandDescriptor) -> Unit,
    val onClosePalette: () -> Unit,
    val onError: (ShownError) -> Unit,
    val onOutbox: (ShownMessage, OutboxEntry) -> Unit,
    val list: ListActions,
    val info: (ShownMessage) -> ShownInfo,
    val modelOf: (Route) -> String,
    val nowMono: () -> Long,
    val text: TextActions,
    val cards: CardActions = CardActions(),
    val models: ModelActions = ModelActions(),
    val search: SearchActions = SearchActions(),
)

/**
 * What the list draws its items with: the owner's zone, locale and today; the host and the model an error
 * card names; the monotonic clock the indicator reads; what Copy and Share do; the messages selected and the
 * outbox item whose tap menu is open; and what a tap, a long-press, an error card's action and an outbox menu's
 * pick (none when it closes) do; what the cards do; whether a connection is up, which a thumbnail is asked
 * again on; the row jumped to last, which pulses; and while search steps through the chat, the words it marks
 * in the row stepped to.
 */
data class TimelineContext(
    val zone: ZoneId,
    val locale: Locale,
    val today: LocalDate,
    val host: String,
    val model: String,
    val nowMono: () -> Long,
    val text: TextActions,
    val selected: Set<String>,
    val menuFor: String?,
    val onTap: (ChatItem.Message) -> Unit,
    val onLongPress: (ChatItem.Message) -> Unit,
    val onError: (ShownError) -> Unit,
    val onOutbox: (ShownMessage, OutboxEntry?) -> Unit,
    val cards: CardActions = CardActions(),
    val linkUp: Boolean = false,
    val highlight: Jump? = null,
    val marks: InChatMarks? = null,
) {
    /** The words search marks in [message] while it steps through the chat at its row; none otherwise. */
    fun marksIn(message: ShownMessage): List<String> = marks?.takeIf { it.seq == message.seq }?.words.orEmpty()
}

/**
 * Where the chat goes: back to the Chats list, to its Instance screen; [showing] says whether the chat is on
 * top, and an action reaches the model only then.
 */
data class ChatNavigation(
    val onBack: () -> Unit,
    val onInstance: () -> Unit,
    val showing: () -> Boolean,
)

/** What the list's ends and its presence on screen do (ChatViewModel's reachedBottom, reachedTop and listed). */
data class ListActions(
    val onBottom: () -> Unit,
    val onTop: () -> Unit,
    val onListed: (ULong?) -> Unit,
)

/**
 * What the owner has opened over the list: a lifted message, its Info, its words to select, an outbox item's tap
 * menu, and the messages selected, each by its item's key, kept across a rotation.
 */
internal class Overlays(
    lifted: MutableState<String?>,
    info: MutableState<String?>,
    selecting: MutableState<String?>,
    outbox: MutableState<String?>,
    selected: MutableState<Set<String>>,
) {
    var lifted by lifted
    var info by info
    var selecting by selecting
    var outbox by outbox
    var selected by selected
}

@Composable
private fun rememberOverlays(): Overlays {
    val keys = listSaver<MutableState<Set<String>>, String>({ it.value.toList() }, { mutableStateOf(it.toSet()) })
    return Overlays(
        rememberSaveable { mutableStateOf(null) },
        rememberSaveable { mutableStateOf(null) },
        rememberSaveable { mutableStateOf(null) },
        rememberSaveable { mutableStateOf(null) },
        rememberSaveable(saver = keys) { mutableStateOf(emptySet()) },
    )
}

/**
 * The Chat screen (design sections 13.5 to 13.7, 13.11): the bar, or the selection's while messages are
 * selected; the banner; the timeline in the 640 dp column, with the scroll pill and, during a fast scroll, the
 * date pill ([datePillHeld] keeps it up for a preview); the palette as a sheet the dock opens into, over a scrim
 * on the whole window; a lifted message with its menu; Info, Select text and the "Model" sheet as sheets; search
 * (design section 13.7), its bar over its list or over the chat it steps through, opened from the bar or with
 * Ctrl+F; a hit jumped to pulses, and [pulsing] holds a preview's pulse as it stands. Back puts down what is open
 * first. An answer's arrival plays its haptic and is announced only while the chat is on screen.
 */
@Composable
fun ChatScreen(
    ui: ChatUi,
    actions: ChatScreenActions,
    listState: LazyListState = rememberLazyListState(),
    datePillHeld: Boolean = false,
    pulsing: Jump? = null,
) {
    val overlays = rememberOverlays()
    val highlight = jumpHighlight(ui, listState) ?: pulsing
    val context =
        timelineContext(ui.state, actions, overlays).copy(highlight = highlight, marks = inChatMarksOf(ui.search))
    val shown = rememberOnScreen()
    val opened = overlays.lifted != null || overlays.selected.isNotEmpty() || ui.palette || ui.search != null
    BackHandler(enabled = opened) {
        when {
            overlays.lifted != null -> overlays.lifted = null
            overlays.selected.isNotEmpty() -> overlays.selected = emptySet()
            ui.palette -> actions.onClosePalette()
            else -> actions.search.onBack()
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    LocalFermixColors.current.canvas,
                ).onPreviewKeyEvent(findKey(actions.search)),
    ) {
        ChatFrame(
            body = {
                Column(modifier = Modifier.fillMaxSize()) {
                    Top(ui, actions, overlays, context)
                    Box(modifier = Modifier.weight(1f)) { Body(ui, context, listState, datePillHeld, actions) }
                }
            },
            scrim = { if (ui.palette) Scrim(actions.onClosePalette) },
            sheet = { if (ui.palette) PaletteSheet(ui, actions) },
            dock = { Dock(ui, actions) },
        )
        Overlaid(ui, context, overlays, actions)
        Arrivals(ui.state.arrived, shown)
    }
    ListEffects(ui.state, shown, listState, actions.list)
}

@Composable
private fun Top(
    ui: ChatUi,
    actions: ChatScreenActions,
    overlays: Overlays,
    context: TimelineContext,
) {
    val state = ui.state
    val top = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Column(modifier = Modifier.background(LocalFermixColors.current.tonal).windowInsetsPadding(top)) {
        val search = ui.search
        if (search != null) {
            SearchBar(search, actions.search)
        } else if (overlays.selected.isEmpty()) {
            ChatBar(state.header, actions.onBack, actions.onInstance, actions.search.onOpen)
        } else {
            val chosen = state.items.filterIsInstance<ChatItem.Message>().filter { it.key in overlays.selected }
            val words = transcriptOf(chosen.asReversed().map { it.message }, state.header.record.title, context)
            SelectionBar(
                count = chosen.size,
                onClose = { overlays.selected = emptySet() },
                onCopy = { actions.text.copy(words) },
                onShare = { actions.text.share(words) },
            )
        }
    }
    state.banner?.let { BannerLine(it, state.header.record.host, actions.onInstance) }
}

@Composable
private fun Body(
    ui: ChatUi,
    context: TimelineContext,
    listState: LazyListState,
    datePillHeld: Boolean,
    actions: ChatScreenActions,
) {
    val search = ui.search
    FermixColumn(ColumnWidth.Wide) {
        if (search != null && search.mode == SearchMode.LIST) {
            SearchList(search, context, actions.search)
        } else if (ui.state.items.isEmpty()) {
            EmptyChat(ui.state.name)
        } else {
            Timeline(ui.state.items, context, listState)
            TimelineOverlays(ui.state, context, listState, datePillHeld)
        }
    }
}

/** A lifted message and its menu, Info, Select text and the "Model" sheet, over everything. */
@Composable
private fun Overlaid(
    ui: ChatUi,
    context: TimelineContext,
    overlays: Overlays,
    actions: ChatScreenActions,
) {
    ui.sheet?.let { ModelSheetView(it, ui.state, actions.models) }
    val messages = ui.state.items.filterIsInstance<ChatItem.Message>()
    messages.find { it.key == overlays.lifted }?.let { item ->
        LiftedMessage(
            item = item,
            context =
                context.copy(onTap = {
                    overlays.lifted = null
                    overlays.selected = setOf(it.key)
                }),
            onPick = { entry ->
                overlays.lifted = null
                picked(entry, item, overlays, actions)
            },
            onDismiss = { overlays.lifted = null },
        )
    }
    messages.find { it.key == overlays.info }?.let { item ->
        InfoSheet(actions.info(item.message), context, actions.modelOf) { overlays.info = null }
    }
    messages.find { it.key == overlays.selecting }?.let { item ->
        SelectTextSheet(item.message.text) { overlays.selecting = null }
    }
}

/** What a long-press menu's [entry] does to [item]. */
private fun picked(
    entry: MenuEntry,
    item: ChatItem.Message,
    overlays: Overlays,
    actions: ChatScreenActions,
) {
    when (entry) {
        MenuEntry.COPY -> actions.text.copy(item.message.text)
        MenuEntry.SELECT_TEXT -> overlays.selecting = item.key
        MenuEntry.COPY_CODE -> actions.text.copy(codeOf(item.message))
        MenuEntry.SHARE -> actions.text.share(item.message.text)
        MenuEntry.INFO -> overlays.info = item.key
        MenuEntry.RETRY -> actions.onOutbox(item.message, OutboxEntry.TRY_AGAIN)
    }
}

@Composable
private fun timelineContext(
    state: ChatScreenState,
    actions: ChatScreenActions,
    overlays: Overlays,
): TimelineContext {
    val view = LocalView.current
    val base = timelineBase(state, actions)
    return base.copy(
        selected = overlays.selected,
        menuFor = overlays.outbox,
        onTap = { item -> tapped(item, overlays) },
        onLongPress = { item ->
            HapticFeedback.perform(view, HapticUse.LongPress)
            overlays.lifted = item.key
        },
        onOutbox = { message, entry ->
            overlays.outbox = null
            entry?.let { actions.onOutbox(message, it) }
        },
    )
}

/** A tap on a message: it toggles its selection while selecting, else opens an outbox item's own menu. */
private fun tapped(
    item: ChatItem.Message,
    overlays: Overlays,
) {
    val selected = overlays.selected
    if (selected.isNotEmpty()) {
        overlays.selected = if (item.key in selected) selected - item.key else selected + item.key
    } else if (outboxMenuOf(item.message).isNotEmpty()) {
        overlays.outbox = item.key
    }
}

/** The copy of [messages] as a transcript, the owner's as "You" and the agent's under [agent]'s name. */
@Composable
private fun transcriptOf(
    messages: List<ShownMessage>,
    agent: String,
    context: TimelineContext,
): String {
    val you = stringResource(R.string.chat_you)
    return transcript(messages, { if (it == Sender.User) you else agent }, { timeOf(it, context) })
}
