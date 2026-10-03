package io.tezra.fermix.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.instance.Link
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How close to the bottom, in pixels, the list still counts as there: a pinned list follows what lands. */
private const val PINNED_SLACK = 8

/** How many items from the top the list asks for older ones. */
private const val NEAR_TOP_ITEMS = 3

/** Whether the list shows its newest item at the bottom (a reversed list's first). */
internal val LazyListState.atBottom: Boolean
    get() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= PINNED_SLACK

/**
 * Whether the chat is on screen as design section 10 and onboarding's §5 mean it: resumed, with its window
 * focused. Only then does the announcer answer ON_SCREEN for a row its list holds, and only then is a row read
 * at the bottom; paused, stopped, or in split screen beside the app the owner is using, it is not.
 */
fun onScreen(
    lifecycle: Lifecycle.State,
    windowFocused: Boolean,
): Boolean = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && windowFocused

/** Whether the chat is on screen now (onScreen), as the screen's lifecycle and window say. */
@Composable
internal fun rememberOnScreen(): Boolean {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return onScreen(lifecycle, LocalWindowInfo.current.isWindowFocused)
}

/**
 * What the list's items are drawn with, from the screen's [state] and [actions]: the owner's zone and today,
 * the locale as the device reads it, the host, the model an error card names, and an outbox item's tap
 * opening its menu once the screen adds that.
 */
@Composable
internal fun timelineBase(
    state: ChatScreenState,
    actions: ChatScreenActions,
): TimelineContext {
    val locale = LocalConfiguration.current.locales[0]
    val model = state.chosenModel ?: stringResource(R.string.chat_error_this_model)
    return TimelineContext(
        zone = state.zone,
        locale = locale,
        today = state.today,
        host = state.header.record.host,
        model = model,
        nowMono = actions.nowMono,
        text = actions.text,
        selected = emptySet(),
        menuFor = null,
        onTap = {},
        onLongPress = {},
        onError = actions.onError,
        onOutbox = { _, _ -> },
        cards = actions.cards,
        linkUp = state.header.link is Link.Up,
    )
}

/**
 * The list's effects (design sections 10 and 13.5): while the chat is [shown] (onScreen) it reports the newest
 * row its list holds, and none otherwise or once it leaves, so the announcer answers ON_SCREEN only for a row
 * the list holds; a list pinned to the bottom follows what lands; at the bottom, while shown, the newest row is
 * read, asked again as the [link] changes, so a session that comes after the bottom was reached reads it too;
 * near the top, older rows are asked for, again as the link comes up.
 */
@Composable
internal fun ListEffects(
    state: ChatScreenState,
    shown: Boolean,
    listState: LazyListState,
    list: ListActions,
) {
    val newestSeq = state.newestSeq
    val link = state.header.link
    LaunchedEffect(shown, newestSeq) { list.onListed(if (shown) newestSeq ?: 0uL else null) }
    DisposableEffect(list) { onDispose { list.onListed(null) } }
    Pinning(state.items.firstOrNull()?.key, listState)
    val bottom = listState.atBottom
    LaunchedEffect(bottom, shown, newestSeq, link) { if (bottom && shown) list.onBottom() }
    val nearTop =
        (
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        ) >= state.items.size - NEAR_TOP_ITEMS
    LaunchedEffect(nearTop, state.items.size, link is Link.Up) { if (nearTop) list.onTop() }
}

/**
 * Keeps a list the owner left at the bottom there as items land (autoscroll only when pinned, design section
 * 13.5): whether it is pinned is read as each of the owner's scrolls ends, so a landing item does not unpin it,
 * and starts as where the list opens, so a position kept across a rotation is not scrolled away.
 */
@Composable
private fun Pinning(
    newestKey: String?,
    listState: LazyListState,
) {
    var pinned by remember { mutableStateOf(listState.atBottom) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) pinned = listState.atBottom
        }
    }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(newestKey) {
        if (!pinned || newestKey == null) return@LaunchedEffect
        if (reduced) listState.scrollToItem(0) else listState.animateScrollToItem(0)
    }
}

/** How long a jump waits for its row to reach the list (the cache's page, then the list's growth). */
private const val JUMP_WAIT_MS = 5_000L

/** The index of the message of row [seq] among [items], -1 while the list does not hold it. */
internal fun indexOfRow(
    items: List<ChatItem>,
    seq: ULong,
): Int = items.indexOfFirst { (it as? ChatItem.Message)?.message?.seq == seq }

/**
 * A search hit's jump (design section 13.7): once the list holds its row, the list scrolls to it, at once
 * under reduce-motion, and the row pulses for [FermixMotion.JUMP_HIGHLIGHT_MILLIS]; the jump it returns is the
 * one pulsing, none after. Each jump plays once, a rotation included; one whose row never comes is let go
 * after [JUMP_WAIT_MS].
 */
@Composable
internal fun jumpHighlight(
    ui: ChatUi,
    listState: LazyListState,
): Jump? {
    var lit by remember { mutableStateOf<Jump?>(null) }
    var handled by rememberSaveable { mutableIntStateOf(0) }
    val items by rememberUpdatedState(ui.state.items)
    val reduced = LocalReducedMotion.current
    val jump = ui.jump
    LaunchedEffect(jump) {
        if (jump == null || jump.nonce == handled) return@LaunchedEffect
        handled = jump.nonce
        val index =
            withTimeoutOrNull(JUMP_WAIT_MS) { snapshotFlow { indexOfRow(items, jump.seq) }.first { it >= 0 } }
                ?: return@LaunchedEffect
        if (reduced) listState.scrollToItem(index) else listState.animateScrollToItem(index)
        lit = jump
        delay(FermixMotion.JUMP_HIGHLIGHT_MILLIS.toLong())
        lit = null
    }
    return lit
}

/** Ctrl+F on a hardware keyboard opens search (design section 13.7), wherever the focus is in the chat. */
internal fun findKey(search: SearchActions): (KeyEvent) -> Boolean =
    { event ->
        val find = event.key == Key.F && event.isCtrlPressed
        if (find && event.type == KeyEventType.KeyDown) search.onOpen()
        find
    }
