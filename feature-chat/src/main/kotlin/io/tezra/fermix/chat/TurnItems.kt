package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.TimelineRow

/**
 * What follows a row with no row of its own: the notices, model lines and approval cards after the row that
 * was newest as they came, and each turn's ending after its last sealed bubble, or else its request's row, or else the
 * row that was newest as it ended. An ending whose last bubble's row has not come yet goes with the turn's
 * bubbles at the bottom (liveItems). A request the daemon refused before `accepted` shows on its outbox item,
 * not as the turn's error card.
 */
internal fun extras(
    inputs: ChatInputs,
    ascending: List<TimelineRow>,
    rowSeqs: Set<ULong>,
): List<Placed> {
    val pills = inputs.live.pills.flatMap { pill -> pillItems(pill) }
    val failedOutbox =
        inputs.outbox
            .filter { it.failure != null }
            .map { it.clientMsgId }
            .toSet()
    val endings =
        inputs.live.turns
            .filter { !it.live && pendingBubbles(it, rowSeqs).isEmpty() }
            .filter { turn -> turn.clientMsgId.let { it == null || it !in inputs.live.refused + failedOutbox } }
            .mapNotNull { turn ->
                endingItem(turn, requestFor(turn, inputs, ascending))?.let { item ->
                    Placed(endingAnchor(turn, ascending), 1, item) to turn.ending?.wallMs
                }
            }
    return (pills + endings + approvalItems(inputs.live)).sortedBy { it.second ?: Long.MAX_VALUE }.map { it.first }
}

internal fun pillItems(pill: LivePill): List<Pair<Placed, Long?>> {
    val key = "pill:${pill.wallMs}:${pill.afterSeq}"
    val texts =
        when (pill) {
            is LivePill.Notice -> {
                listOf(PillText.Notice(pill.text))
            }

            is LivePill.ModelChanged -> {
                val change = if (pill.toDefault) PillText.BackToDefault(pill.label) else PillText.Switched(pill.label)
                listOfNotNull(change, pill.note?.let(PillText::Note))
            }
        }
    return texts.mapIndexed { index, text ->
        Placed(pill.afterSeq, 1, ChatItem.Pill("$key:$index", text)) to pill.wallMs
    }
}

/** Where a turn's ending goes: after its last sealed bubble, else after its request's row, else as it ended. */
internal fun endingAnchor(
    turn: LiveTurn,
    ascending: List<TimelineRow>,
): ULong {
    val sealed = turn.bubbles.mapNotNull { it.sealedSeq }.maxOrNull()
    val request = turn.clientMsgId?.let { id -> ascending.firstOrNull { userRequest(it) == id }?.serverSeq }
    return sealed ?: request ?: checkNotNull(turn.ending).afterSeq
}

/** What "Run again" sends again for [turn]: the request the screen sent, or else its row's words as a `msg`. */
internal fun requestFor(
    turn: LiveTurn,
    inputs: ChatInputs,
    ascending: List<TimelineRow>,
): ClientEvent? {
    val clientMsgId = turn.clientMsgId ?: return null
    val row = ascending.firstOrNull { userRequest(it) == clientMsgId } as? TimelineRow.Message
    val asWritten = row?.let { ClientEvent.Msg(clientMsgId, inputs.profileId, it.message.content, emptyList()) }
    return inputs.requests[clientMsgId] ?: asWritten
}

/** The requests `accepted` took out of the outbox whose rows have not come yet: delivered, with one tick. */
internal fun bridgedItems(
    inputs: ChatInputs,
    ascending: List<TimelineRow>,
): List<ChatItem> {
    val landed = ascending.mapNotNull(::userRequest).toSet()
    return inputs.bridged.filter { it.clientMsgId !in landed }.map { item ->
        val sent = inputs.live.accepted[item.clientMsgId] ?: inputs.nowWall
        val message = outboxMessage(item, Delivery.DELIVERED, inputs).copy(wallMs = sent, uploadLine = null)
        ChatItem.Message(outboxKey(item.clientMsgId), message)
    }
}

/** A turn's sealed bubbles whose rows have not come yet. */
internal fun pendingBubbles(
    turn: LiveTurn,
    rowSeqs: Set<ULong>,
): List<LiveBubble> = turn.bubbles.filter { it.sealedSeq != null && it.sealedSeq !in rowSeqs }

/**
 * The turns at the bottom, in the order they started: each running turn's bubbles and its card, and a turn
 * that ended whose last bubble's row has not come yet, with its ending after it.
 */
internal fun liveItems(
    inputs: ChatInputs,
    rowSeqs: Set<ULong>,
    ascending: List<TimelineRow>,
): List<ChatItem> =
    inputs.live.turns.flatMap { turn ->
        val pending = pendingBubbles(turn, rowSeqs)
        when {
            turn.live -> turnBubbles(turn, rowSeqs) + listOfNotNull(cardItem(turn))
            pending.isEmpty() -> emptyList()
            else -> turnBubbles(turn, rowSeqs) + listOfNotNull(endingItem(turn, requestFor(turn, inputs, ascending)))
        }
    }

/**
 * A turn's bubbles that have no row yet: the one streaming, and those sealed whose rows are on their way. A
 * sealed bubble with no words yet, a bare `text_done`'s, shows the card it came from in its place, under the
 * key both share, until its row brings the words.
 */
internal fun turnBubbles(
    turn: LiveTurn,
    rowSeqs: Set<ULong>,
): List<ChatItem> =
    turn.bubbles
        .sortedBy { it.index }
        .filter { it.sealedSeq == null || it.sealedSeq !in rowSeqs }
        .mapNotNull { bubble ->
            val key = liveBubbleKey(turn.turnId, bubble)
            when {
                bubble.text.isNotEmpty() || bubble.sealedSeq == null -> ChatItem.Message(key, liveMessage(turn, bubble))
                bubble.card != null -> ChatItem.Thinking(key, turn.turnId, bubble.card, turn.startedMono, turn.seed)
                else -> null
            }
        }

private fun liveMessage(
    turn: LiveTurn,
    bubble: LiveBubble,
): ShownMessage =
    ShownMessage(
        sender = Sender.Agent,
        text = bubble.text,
        wallMs = bubble.sealedWall,
        delivery = Delivery.NONE,
        streaming = bubble.sealedSeq == null,
        resets = bubble.resets,
        fromCard = bubble.fromCard,
        seq = bubble.sealedSeq,
        turnId = turn.turnId,
    )

/** The card of [turn] while it shows, keyed by the bubble it would become (cardKey). */
internal fun cardItem(turn: LiveTurn): ChatItem? {
    val card = turn.card ?: return null
    val next = (turn.bubbles.maxOfOrNull { it.index } ?: 0) + 1
    return ChatItem.Thinking(cardKey(turn.turnId, next), turn.turnId, card, turn.startedMono, turn.seed)
}
