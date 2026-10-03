package io.tezra.fermix.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.GroupPosition
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.Sender

/** The timeline's gutters, left and right of every bubble (design section 13.1). */
internal val TIMELINE_GUTTER = 12.dp

/** The list's content type of the thinking card and the bubble it becomes, one item that morphs. */
private const val MORPH_TYPE = "morph"

/** The space above an item: 2 dp within a group, 12 dp between groups (design section 13.1). */
private fun gapAbove(
    items: List<ChatItem>,
    index: Int,
): Dp {
    val message = (items[index] as? ChatItem.Message)?.message
    val above = (items.getOrNull(index + 1) as? ChatItem.Message)?.message
    val position = message?.position
    val joined = position == GroupPosition.Middle || position == GroupPosition.Last
    return if (joined && above != null &&
        above.sender == message.sender
    ) {
        FermixSpacing.withinGroup
    } else {
        FermixSpacing.betweenGroups
    }
}

/**
 * The timeline (design section 13.5): [items] newest first, drawn from the bottom, each keyed so a bubble
 * stays itself from the outbox to its row and from live to sealed, and the thinking card and the answer it
 * becomes are one item that morphs (Morph). What lands at the bottom rises in (Rising); what scrolls into view
 * does not. Under reduce-motion nothing moves.
 */
@Composable
internal fun Timeline(
    items: List<ChatItem>,
    context: TimelineContext,
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val fresh = rememberFreshKeys(items)
    LazyColumn(
        state = state,
        reverseLayout = true,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TIMELINE_GUTTER, top = 8.dp, end = TIMELINE_GUTTER, bottom = 12.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, item -> contentTypeOf(item) }) {
            index,
            item,
            ->
            val motion = if (reduced) Modifier else Modifier.animateItem()
            val gap = Modifier.padding(top = gapAbove(items, index))
            Rising(item.key in fresh, motion) {
                if (item.key.startsWith(CARD_KEY_PREFIX)) {
                    Morph(item) { shown -> TimelineItem(shown, context, gap) }
                } else {
                    TimelineItem(item, context, gap)
                }
            }
        }
    }
}

/** An item's content type: the card and its bubble share one, so the list reuses what morphs. */
private fun contentTypeOf(item: ChatItem): String =
    if (item.key.startsWith(CARD_KEY_PREFIX)) MORPH_TYPE else item::class.simpleName.orEmpty()

@Composable
private fun LazyItemScope.TimelineItem(
    item: ChatItem,
    context: TimelineContext,
    modifier: Modifier,
) {
    when (item) {
        is ChatItem.Day -> {
            DayPill(item.date, context, modifier)
        }

        ChatItem.Unread -> {
            Box(modifier = modifier.padding(top = 2.dp, bottom = 2.dp)) { UnreadDivider() }
        }

        ChatItem.Older -> {
            OlderSkeleton(modifier)
        }

        is ChatItem.Message -> {
            MessageItem(item, context, modifier)
        }

        is ChatItem.Thinking -> {
            ThinkingCard(
                item,
                context.nowMono,
                modifier.fillMaxWidth(FermixSpacing.AGENT_BUBBLE_MAX_WIDTH),
            )
        }

        is ChatItem.Error -> {
            ErrorItem(item.error, context, modifier)
        }

        is ChatItem.Pill -> {
            CentredPill(item.text, modifier)
        }
    }
}

@Composable
private fun ErrorItem(
    error: ShownError,
    context: TimelineContext,
    modifier: Modifier,
) {
    val user = error.side == Sender.User
    val width = if (user) FermixSpacing.USER_BUBBLE_MAX_WIDTH else FermixSpacing.AGENT_BUBBLE_MAX_WIDTH
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(modifier = Modifier.fillMaxWidth(width)) { ErrorCard(error, context.host, context.model, context.onError) }
    }
}

/** The empty chat (design section 13.9): "Say hello to {name}.", centred. */
@Composable
internal fun EmptyChat(
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.chat_empty, name),
            style = FermixType.body,
            color = LocalFermixColors.current.inkSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The unread divider (the canon's `.unread`): a 1 dp line in the accent ink at 70 %, across the timeline, its
 * gutters included (the canon's `margin: 0 -12px`).
 */
@Composable
internal fun UnreadDivider() {
    val label = stringResource(R.string.chat_unread_divider)
    Spacer(
        modifier =
            Modifier
                .fillMaxWidth()
                .acrossGutters()
                .padding(top = 2.dp, bottom = 2.dp)
                .height(FermixSpacing.hairline)
                .background(LocalFermixColors.current.accentInk.copy(alpha = UNREAD_ALPHA))
                .semantics { contentDescription = label },
    )
}

/** Widens the content by the timeline's gutter on each side, out of the list's padding. */
private fun Modifier.acrossGutters(): Modifier =
    layout { measurable, constraints ->
        val gutter = TIMELINE_GUTTER.roundToPx()
        require(constraints.hasBoundedWidth) { "The divider spans a bounded list." }
        val wide = constraints.maxWidth + 2 * gutter
        val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
    }

private const val UNREAD_ALPHA = 0.7f
