package io.tezra.fermix.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.GroupPosition
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.Sender
import io.tezra.fermix.design.bubbleShape

/** A queued bubble's opacity (the canon's `.b.q`). */
private const val QUEUED_ALPHA = 0.55f

/** A selected message's wash, the accent at 12 %. */
private const val SELECTED_ALPHA = 0.12f

/**
 * A message of the chat (design sections 13.1 and 13.5): the owner's in an accent bubble at the end of the
 * column, at most 78 % wide; the agent's as its parts (segmentsOf), prose in bubbles at most 88 % wide and its
 * fences and tables as cards grouped under them; one with blobs as its images, documents and voice note
 * (MediaMessage). A long-press opens the message's menu; a tap opens a queued, pending or refused item's own, or
 * selects while the chat is selecting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageItem(
    item: ChatItem.Message,
    context: TimelineContext,
    modifier: Modifier = Modifier,
) {
    val message = item.message
    val selected = item.key in context.selected
    val wash = if (selected) LocalFermixColors.current.accent.copy(alpha = SELECTED_ALPHA) else Color.Transparent
    val gestures =
        Modifier.combinedClickable(
            interactionSource = null,
            indication = null,
            onClick = { context.onTap(item) },
            onLongClick = { context.onLongPress(item) },
        )
    Box(modifier = modifier.fillMaxWidth().background(wash)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when {
                message.media.isNotEmpty() -> MediaMessage(item, context, gestures)
                message.sender == Sender.User -> UserMessage(message, context, gestures)
                else -> AgentMessage(item, context, gestures)
            }
            LinkPreviews(message.previews, message.sender, context)
        }
        if (context.menuFor == item.key) {
            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                OutboxMenu(
                    outboxMenuOf(message),
                    { context.onOutbox(message, it) },
                    { context.onOutbox(message, null) },
                )
            }
        }
    }
}

/**
 * The owner's message; [modifier], its tap and long-press, goes on the bubble, not on the row it sits in. Its
 * stamp floats on the text's last line when it fits (StampedText). Under it, a queued or pending item says so,
 * and a refused one says "Not sent. Tap to retry sending." in the error colour, its bubble keeping the clock.
 * The host's reaction hangs over the bubble's bottom-left, popping in only when it lands while it is shown.
 */
@Composable
private fun UserMessage(
    message: ShownMessage,
    context: TimelineContext,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    val firstReaction = remember { message.reaction }
    val ring = ringAlpha(context.highlight?.seq?.let { it == message.seq } == true)
    val shape = bubbleShape(Sender.User, message.position)
    val bubble = @Composable { UserBubble(message, context, modifier.pulseRing(ring, colors.accentInk, shape)) }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(
            modifier = Modifier.fillMaxWidth(FermixSpacing.USER_BUBBLE_MAX_WIDTH),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val reaction = message.reaction
            if (reaction == null) {
                bubble()
            } else {
                ReactedBubble(message.previews.isNotEmpty(), bubble) {
                    ReactionChip(reaction, pops = reaction != firstReaction)
                }
            }
        }
        when (message.delivery) {
            Delivery.QUEUED -> StateLine(stringResource(R.string.chat_queued_behind_reply), error = false)
            Delivery.PENDING -> StateLine(stringResource(R.string.chat_queued), error = false)
            Delivery.FAILED -> StateLine(stringResource(R.string.chat_not_sent), error = true)
            else -> Unit
        }
    }
}

/** The owner's bubble: the accent, the words and the stamp; [modifier] holds the gestures and the jump's ring. */
@Composable
private fun UserBubble(
    message: ShownMessage,
    context: TimelineContext,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    Box(
        modifier =
            modifier
                .alpha(if (message.delivery == Delivery.QUEUED) QUEUED_ALPHA else 1f)
                .clip(bubbleShape(Sender.User, message.position))
                .background(colors.accent)
                .padding(
                    horizontal = FermixSpacing.bubblePaddingHorizontal,
                    vertical = FermixSpacing.bubblePaddingVertical,
                ),
    ) {
        // The words search marks, washed as the canon's `.b mark` but in the bubble's own ink: the canon's
        // accentInk is the accent itself in the light theme, which would wash the bubble in its own colour.
        val wash = SpanStyle(background = colors.onAccent.copy(alpha = MARK_ALPHA))
        val text = withMarks(AnnotatedString(message.text), context.marksIn(message), wash)
        val words = @Composable { onLayout: (TextLayoutResult) -> Unit ->
            Text(text, style = FermixType.body, color = colors.onAccent, onTextLayout = onLayout)
        }
        if (message.delivery == Delivery.QUEUED) {
            words {}
        } else {
            StampedText(words) { Stamp(message, context, colors.onAccent) }
        }
    }
}

/**
 * The agent's message as its parts: the first part wears a job's tag, the last prose bubble the time, as the
 * canon times the bubble a card is grouped under, or the last card when the message has no prose; while it
 * streams, the cursor follows the last part, and only that part streams. Each part's corners follow its place
 * in the message's group.
 */
@Composable
private fun AgentMessage(
    item: ChatItem.Message,
    context: TimelineContext,
    modifier: Modifier,
) {
    val message = item.message
    val parts = rememberSegments(message.text, message.streaming)
    val shown = parts.ifEmpty { listOf(Segment.Prose(0, "")) }
    val timed = shown.indexOfLast { it is Segment.Prose }.takeIf { it >= 0 } ?: shown.lastIndex
    Column(
        modifier = modifier.fillMaxWidth(FermixSpacing.AGENT_BUBBLE_MAX_WIDTH),
        verticalArrangement = Arrangement.spacedBy(FermixSpacing.withinGroup),
    ) {
        shown.forEachIndexed { index, part ->
            val position = partPosition(message.position, index, shown.size)
            val place = PartPlace(index == 0, index == shown.lastIndex, index == timed, position)
            key(part.start) { Part(part, place, item, context) }
        }
    }
}

/** Where a part stands in its message: first, last, the one that shows the time, and its place in the group. */
private data class PartPlace(
    val first: Boolean,
    val last: Boolean,
    val timed: Boolean,
    val position: GroupPosition,
)

/** The parts an answer was last read into, and the text and mode they were read from. */
private class ReadParts {
    var text: String = ""
    var streaming: Boolean = false
    var parts: List<Segment> = emptyList()
}

/**
 * [markdown]'s parts (segmentsOf): while it streams and extends the text last read, from the last settled part
 * on (segmentsAfter), so a token past a settled card does not read the cards before it again; the prose before
 * an answer's first settled card, a whole prose-only answer included, is read whole on each token, and so is a
 * text that does not extend the last.
 */
@Composable
private fun rememberSegments(
    markdown: String,
    streaming: Boolean,
): List<Segment> {
    val read = remember { ReadParts() }
    return remember(markdown, streaming) {
        val extends = streaming && read.streaming && markdown.startsWith(read.text)
        val parts =
            if (extends) {
                segmentsAfter(
                    read.parts,
                    read.text.length,
                    markdown,
                    true,
                )
            } else {
                segmentsOf(markdown, streaming)
            }
        read.text = markdown
        read.streaming = streaming
        read.parts = parts
        parts
    }
}

@Composable
private fun Part(
    part: Segment,
    place: PartPlace,
    item: ChatItem.Message,
    context: TimelineContext,
) {
    val shape = cardShape(place.position)
    when (part) {
        is Segment.Prose -> {
            ProseBubble(part, place, item.message, context)
        }

        is Segment.Code -> {
            CardPart(place, item.message, context) {
                CodeCard(part.info, part.code, "${item.key}:${part.start}", context.text, shape)
            }
        }

        is Segment.Table -> {
            CardPart(place, item.message, context) { TableCard(part.table, shape) }
        }
    }
}

/**
 * A fence's or a table's card, with what a prose bubble would hold had it led or ended the message: a job's tag
 * above it when it is the first part, the cursor below it while it is the last of a streaming answer, and the
 * time below it, in the ink at 60 %, when the message has no prose bubble to show it.
 */
@Composable
private fun CardPart(
    place: PartPlace,
    message: ShownMessage,
    context: TimelineContext,
    card: @Composable () -> Unit,
) {
    val ring = ringAlpha(context.highlight?.seq?.let { it == message.seq } == true)
    val ink = LocalFermixColors.current.accentInk
    Column(modifier = Modifier.fillMaxWidth().pulseRing(ring, ink, FermixShapes.card)) {
        if (place.first) message.job?.let { JobTag(it) }
        card()
        if (place.last && message.streaming) BeamCursor(Modifier.padding(top = 2.dp))
        val stamp = place.timed && !message.streaming
        if (stamp) Stamp(message, context, LocalFermixColors.current.ink, Modifier.align(Alignment.End))
    }
}

@Composable
private fun ProseBubble(
    prose: Segment.Prose,
    place: PartPlace,
    message: ShownMessage,
    context: TimelineContext,
) {
    val colors = LocalFermixColors.current
    val streams = message.streaming && place.last
    val ring = ringAlpha(context.highlight?.seq?.let { it == message.seq } == true)
    Column(
        modifier =
            Modifier
                .pulseRing(ring, colors.accentInk, bubbleShape(Sender.Agent, place.position))
                .clip(bubbleShape(Sender.Agent, place.position))
                .background(colors.agentBubble)
                .padding(
                    horizontal = FermixSpacing.bubblePaddingHorizontal,
                    vertical = FermixSpacing.bubblePaddingVertical,
                ),
    ) {
        if (place.first) message.job?.let { JobTag(it) }
        Prose(prose.markdown, streams, message.resets, context.text, context.marksIn(message))
        // The renderer places the cursor after a paragraph's words and an open fence's chip; below anything else.
        val below = streams && remember(prose.markdown) { cursorHome(prose.markdown) == CursorHome.OTHER }
        if (below) BeamCursor(Modifier.padding(top = 2.dp))
        if (place.timed && !message.streaming) Stamp(message, context, colors.ink, Modifier.align(Alignment.End))
    }
}

/** A part's place in its message's group: the first takes the message's top, the last its bottom. */
private fun partPosition(
    message: GroupPosition,
    index: Int,
    count: Int,
): GroupPosition {
    val joinedAbove = index > 0 || message == GroupPosition.Middle || message == GroupPosition.Last
    val joinedBelow = index < count - 1 || message == GroupPosition.First || message == GroupPosition.Middle
    return when {
        joinedAbove && joinedBelow -> GroupPosition.Middle
        joinedAbove -> GroupPosition.Last
        joinedBelow -> GroupPosition.First
        else -> GroupPosition.Single
    }
}

/** A job's delivery wears its job (design section 8.4): "⏱ nightly-report · scheduled". */
@Composable
private fun JobTag(job: String) {
    Text(
        text = stringResource(R.string.chat_job, job),
        style = FermixType.labelSmall,
        color = LocalFermixColors.current.inkSecondary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}
