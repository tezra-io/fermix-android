package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.isApprovalAnswer

/**
 * The outbox at the bottom, in enqueue order: a refused item with its error card under it; one never written
 * queued behind a running turn, or waiting for a connection; any other on its way, with the clock. An
 * approval's answer is not drawn, nor a model pick on its way (MODEL_PICK_PREFIX).
 */
internal fun outboxItems(inputs: ChatInputs): List<ChatItem> {
    val turnRuns = inputs.live.turns.any { it.live }
    return inputs.outbox.filterNot(::undrawn).flatMap { item ->
        val delivery =
            when {
                item.failure != null -> Delivery.FAILED
                item.written -> Delivery.SENDING
                !inputs.connected -> Delivery.PENDING
                turnRuns -> Delivery.QUEUED
                else -> Delivery.SENDING
            }
        val message = ChatItem.Message(outboxKey(item.clientMsgId), outboxMessage(item, delivery))
        val notSent = item.failure?.let { ChatItem.Error("refused:${item.clientMsgId}", notSentError(item)) }
        listOfNotNull(message, notSent)
    }
}

/**
 * Whether [item] has no bubble: an approval's answer, whose route holds the card's token and whose card says where
 * it stands; a model pick, which "Switches after this reply" and the daemon's line tell of, but for one refused.
 */
private fun undrawn(item: OutboxItem): Boolean =
    isApprovalAnswer(item.clientMsgId) || (isModelPick(item.clientMsgId) && item.failure == null)

/**
 * Whether the chat draws no bubble for [row], the owner's row the daemon writes of a request the chat sent for
 * them: an approval's answer, whose card's receipt stands for it, or a model picked on the sheet, whose line
 * from the daemon does. The Chats row's last message passes it by as well.
 */
fun isQuietRow(row: TimelineRow): Boolean {
    val id = (row as? TimelineRow.Message)?.message?.clientMsgId ?: return false
    return isApprovalAnswer(id) || isModelPick(id)
}

/** An outbox item's bubble: its words, [delivery], and Edit and Remove while its frame was never written. */
internal fun outboxMessage(
    item: OutboxItem,
    delivery: Delivery,
): ShownMessage =
    ShownMessage(
        sender = Sender.User,
        text = requestText(item.request),
        wallMs = null,
        delivery = delivery,
        clientMsgId = item.clientMsgId,
        request = item.request,
        editable = !item.written && item.failure == null,
    )

/** The words a request carries: a message's text, or a command as the owner would type it. */
internal fun requestText(request: ClientEvent): String =
    when (request) {
        is ClientEvent.Msg -> {
            request.text
        }

        is ClientEvent.Command -> {
            listOfNotNull("/${request.name}", request.args).filter { it.isNotEmpty() }.joinToString(" ")
        }

        else -> {
            error("an outbox item holds a msg or a command")
        }
    }
