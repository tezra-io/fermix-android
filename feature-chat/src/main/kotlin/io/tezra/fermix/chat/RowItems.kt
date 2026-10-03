package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.session.TimelineRow

/**
 * When each row came. A message carries its time; a reply's bubble carries none (TimelineRow.Reply), so it
 * takes the time this process sealed it, or else its request's row's, or else the row's before it.
 */
internal fun rowTimes(
    ascending: List<TimelineRow>,
    live: ChatLive,
): Map<ULong, Long?> {
    val sealed =
        live.turns
            .flatMap { turn -> turn.bubbles.mapNotNull { b -> b.sealedSeq?.let { it to b.sealedWall } } }
            .toMap()
    val requestTimes =
        ascending
            .filterIsInstance<TimelineRow.Message>()
            .mapNotNull { row ->
                userRequest(row)?.let { id -> id to wallOf(row) }
            }.toMap()
    var previous: Long? = null
    return ascending.associate { row ->
        val own =
            when (row) {
                is TimelineRow.Message -> wallOf(row)
                is TimelineRow.Reply -> sealed[row.serverSeq] ?: requestTimes[clientMsgIdOf(row.turnId)]
            }
        val time = own ?: previous
        previous = time
        row.serverSeq to time
    }
}

/** A row's message, none for a row with no words to show yet (a media row is Task 13's). */
internal fun rowItem(
    row: TimelineRow,
    wallMs: Long?,
    live: ChatLive,
): Placed? {
    val message =
        when (row) {
            is TimelineRow.Message -> messageOf(row, wallMs)
            is TimelineRow.Reply -> replyOf(row, wallMs)
        }
    if (message.text.isBlank()) return null
    return Placed(row.serverSeq, 0, ChatItem.Message(rowKey(row, message, live), message))
}

internal fun messageOf(
    row: TimelineRow.Message,
    wallMs: Long?,
): ShownMessage {
    val message = row.message
    val user = message.role == USER_ROLE
    val metadata = message.metadata
    return ShownMessage(
        sender = if (user) Sender.User else Sender.Agent,
        text = message.content,
        wallMs = wallMs,
        delivery = if (user) Delivery.DELIVERED else Delivery.NONE,
        job = metadata?.let(::jobOf),
        seq = message.serverSeq,
        clientMsgId = message.clientMsgId,
        turnId = metadata?.string(TURN_ID_KEY),
        route = metadata?.let(::routeOf),
    )
}

internal fun replyOf(
    row: TimelineRow.Reply,
    wallMs: Long?,
): ShownMessage =
    ShownMessage(
        sender = Sender.Agent,
        text = row.text,
        wallMs = wallMs,
        delivery = Delivery.NONE,
        seq = row.serverSeq,
        turnId = row.turnId,
        route = row.route,
    )

/**
 * A row's key: the owner's message keeps its outbox item's, so the bubble stays as `accepted` and then its
 * row replace the item; a reply keeps its live bubble's, so a sealed bubble stays as its row lands.
 */
internal fun rowKey(
    row: TimelineRow,
    message: ShownMessage,
    live: ChatLive,
): String {
    val clientMsgId = message.clientMsgId
    if (message.sender == Sender.User && clientMsgId != null) return outboxKey(clientMsgId)
    val sealed =
        live.turns.firstNotNullOfOrNull { turn ->
            turn.bubbles.firstOrNull { it.sealedSeq == row.serverSeq }?.let { liveBubbleKey(turn.turnId, it) }
        }
    return sealed ?: "row:${row.serverSeq}"
}

internal fun outboxKey(clientMsgId: String): String = "out:$clientMsgId"

/**
 * A live bubble's key: the card's it came from, so the card's place in the list becomes the bubble's and its
 * bounds morph into it (design section 13.5), or else its own.
 */
internal fun liveBubbleKey(
    turnId: String,
    bubble: LiveBubble,
): String = if (bubble.fromCard) cardKey(turnId, bubble.index) else bubbleKey(turnId, bubble.index)

internal fun bubbleKey(
    turnId: String,
    index: Int,
): String = "turn:$turnId:$index"

/** The key of [turnId]'s card that bubble [index] would come from: a turn opens a card again below a seal. */
internal fun cardKey(
    turnId: String,
    index: Int,
): String = "$CARD_KEY_PREFIX$turnId:$index"

/** What every card's key, and a bubble's that a card became, starts with: the list morphs one into the other. */
internal const val CARD_KEY_PREFIX = "card:"

/** The client_msg_id a row of the owner's carries, none for any other row. */
internal fun userRequest(row: TimelineRow): String? =
    (row as? TimelineRow.Message)?.message?.takeIf { it.role == USER_ROLE }?.clientMsgId

internal fun clientMsgIdOf(turnId: String): String? = LiveTurn(turnId, 0L, 0L).clientMsgId
