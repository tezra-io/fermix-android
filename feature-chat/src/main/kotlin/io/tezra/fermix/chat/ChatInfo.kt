package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.Caps
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.transport.Candidate

/**
 * What Info says of a turn (design section 13.7), from this process's memory of it, never stored: how long it
 * ran, once it ended; how long the card showed and every tool it ran, with each one's status; and the path its
 * last bubble landed over.
 */
data class TurnInfo(
    val durationMs: Long?,
    val thoughtMs: Long,
    val tools: List<LiveChip>,
    val path: Candidate.Scope?,
)

/**
 * Info on a message (design section 13.7): when it was sent and when the daemon took it, its `server_seq`
 * and `client_msg_id`, the model that wrote it (`text_done.route`), and its turn's facts when this process
 * saw the turn.
 */
data class ShownInfo(
    val sentWall: Long?,
    val deliveredWall: Long?,
    val serverSeq: ULong?,
    val clientMsgId: String?,
    val route: Route?,
    val turn: TurnInfo?,
)

/**
 * [message]'s Info from [live]: a message of the owner's has its delivery and the turn that answered it; a
 * bubble of the agent's has the turn it came in.
 */
fun infoOf(
    message: ShownMessage,
    live: ChatLive,
): ShownInfo {
    val clientMsgId = message.clientMsgId
    val turnId = if (message.sender == Sender.User) clientMsgId?.let { "turn-$it" } else message.turnId
    val turn = live.turns.lastOrNull { it.turnId == turnId }
    return ShownInfo(
        sentWall = message.wallMs,
        deliveredWall = clientMsgId?.let { live.accepted[it] },
        serverSeq = message.seq,
        clientMsgId = clientMsgId,
        route = message.route,
        turn = turn?.let(::turnInfo),
    )
}

private fun turnInfo(turn: LiveTurn): TurnInfo {
    // The card's time counts once it went: a card still showing is a turn still running, whose Info has no end.
    return TurnInfo(
        durationMs = turn.ending?.let { (it.monoMs - turn.startedMono).coerceAtLeast(0L) },
        thoughtMs = turn.thoughtMs,
        tools = turn.tools,
        path = turn.path,
    )
}

/**
 * "Copy as transcript" (design section 13.7, "Multi-select"): [messages] oldest first, each as its sender's
 * name and its time on one line and its words under it, a blank line between two.
 */
fun transcript(
    messages: List<ShownMessage>,
    nameOf: (Sender) -> String,
    timeOf: (Long) -> String,
): String =
    messages.joinToString("\n\n") { message ->
        val heading = listOfNotNull(nameOf(message.sender), message.wallMs?.let(timeOf)).joinToString(" · ")
        "$heading\n${message.text}"
    }

/** The long-press menu's entries (design section 13.7), in its order; Reply and Forward are M52's and absent. */
enum class MenuEntry { COPY, SELECT_TEXT, COPY_CODE, SHARE, INFO, RETRY }

/**
 * [message]'s long-press menu: Copy · Select text · Copy code, when it holds a fence · Share · Info · Retry, on
 * an item the daemon refused.
 */
fun menuOf(message: ShownMessage): List<MenuEntry> =
    listOfNotNull(
        MenuEntry.COPY,
        MenuEntry.SELECT_TEXT,
        MenuEntry.COPY_CODE.takeIf { codeOf(message).isNotEmpty() },
        MenuEntry.SHARE,
        MenuEntry.INFO,
        MenuEntry.RETRY.takeIf { message.delivery == Delivery.FAILED },
    )

/** The code of every fence in the agent's [message], a blank line between two; none in the owner's. */
fun codeOf(message: ShownMessage): String {
    if (message.sender == Sender.User) return ""
    return segmentsOf(message.text, streaming = false)
        .filterIsInstance<Segment.Code>()
        .joinToString("\n\n") { it.code }
}

/** A tap's menu on the owner's item still in the outbox (design section 13.6). */
enum class OutboxEntry { EDIT, REMOVE, TRY_AGAIN, REMOVE_FROM_OUTBOX }

/**
 * What a tap on [message] offers: Edit and Remove while its frame was never written, "Try again" and "Remove
 * from outbox" once the daemon refused it, and nothing on any other.
 */
fun outboxMenuOf(message: ShownMessage): List<OutboxEntry> =
    when {
        message.editable -> listOf(OutboxEntry.EDIT, OutboxEntry.REMOVE)
        message.delivery == Delivery.FAILED -> listOf(OutboxEntry.TRY_AGAIN, OutboxEntry.REMOVE_FROM_OUTBOX)
        else -> emptyList()
    }

/**
 * The words for the model that wrote a bubble (`text_done.route`): the label the chat's caps give it, its
 * default's or its own, or else the model's id as the route names it.
 */
fun modelLabel(
    route: Route,
    caps: Caps?,
): String {
    val state = caps?.modelState
    val known = listOfNotNull(state?.overrideModel, state?.defaultModel)
    return known.firstOrNull { it.provider == route.provider && it.model == route.model }?.label ?: route.model
}
