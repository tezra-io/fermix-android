package io.tezra.fermix.chat

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.Sender
import io.tezra.fermix.design.bubbleShape

/** A corner of the ring where a bare line lies in the message's corner. */
private val SQUARE = CornerSize(0.dp)

/**
 * An agent's message's focus ring (the M51 update's 1.3), drawn round its [parts]: its first part's top corners
 * and its last part's bottom corners, a bubble's or a card's, but square where a bare line lies in the corner, a
 * job's tag over the first card, or the time ([timed], the part that shows it) or the streaming cursor under the
 * last, as a rounded ring's corner would cut through that line's words.
 */
internal fun agentRingShape(
    message: ShownMessage,
    parts: List<Segment>,
    timed: Int,
): RoundedCornerShape {
    require(parts.isNotEmpty()) { "an agent's message is drawn as one part at the least" }
    require(timed in parts.indices) { "the time is shown by one of the message's ${parts.size} parts, not $timed" }
    val bubble = bubbleShape(Sender.Agent, message.position)
    // A card's top corners are its message's place in the group's (cardShape); its bottom ones are 16 dp anywhere.
    val card = cardShape(message.position)
    val top =
        when {
            parts.first() is Segment.Prose -> bubble
            message.job != null -> null
            else -> card
        }
    val bottom =
        when {
            parts.last() is Segment.Prose -> bubble
            message.streaming || timed == parts.lastIndex -> null
            else -> card
        }
    return RoundedCornerShape(
        topStart = top?.topStart ?: SQUARE,
        topEnd = top?.topEnd ?: SQUARE,
        bottomEnd = bottom?.bottomEnd ?: SQUARE,
        bottomStart = bottom?.bottomStart ?: SQUARE,
    )
}
