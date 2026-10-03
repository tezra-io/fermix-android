package io.tezra.fermix.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.instance.Link

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
