package io.tezra.fermix.chat

import io.tezra.fermix.design.GroupPosition
import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.TurnOutcome
import java.time.LocalDate

/**
 * How a user bubble says where it stands (design sections 13.5 and 13.6): nothing for the agent's; the
 * clock until `accepted`, then a single tick, never a double; queued behind a running turn ("queued · sends
 * after this reply", at 55 %); waiting for a connection ("Queued", with the clock); or refused, the red "!".
 */
enum class Delivery { NONE, SENDING, DELIVERED, QUEUED, PENDING, FAILED }

/**
 * One message as the chat draws it: its [sender], its [text] (the agent's is markdown), when it came, its
 * place in a group, its [delivery], and whether it still [streaming] (the cursor, no time). [resets] counts
 * the replaced snapshots of a live bubble (LiveBubble). [job] is the scheduled job a delivery wears. [seq],
 * [clientMsgId], [turnId] and [route] are Info's; [request] is the outbox's for Edit, Remove and a retry, and
 * [editable] says its frame was never written to a socket.
 */
data class ShownMessage(
    val sender: Sender,
    val text: String,
    val wallMs: Long?,
    val delivery: Delivery,
    val position: GroupPosition = GroupPosition.Single,
    val streaming: Boolean = false,
    val resets: Int = 0,
    val fromCard: Boolean = false,
    val job: String? = null,
    val seq: ULong? = null,
    val clientMsgId: String? = null,
    val turnId: String? = null,
    val route: Route? = null,
    val request: ClientEvent? = null,
    val editable: Boolean = false,
)

/** An error card's sentence (design section 13.9, by `turn_error` code). */
enum class ErrorLine { NOT_SENT, TIMEOUT, MODEL_UNAVAILABLE, UNSUPPORTED, GENERIC }

/** An error card's one action (design section 13.5, "Error card"; review R4). */
enum class ErrorAction { RETRY_SENDING, RUN_AGAIN, RESET_TO_DEFAULT, NONE }

/**
 * An error card: its [line] and [action], on the user's side for a request that never got `accepted` and on
 * the agent's for a turn that ran and failed. [request] is what "Retry sending" or "Run again" sends again,
 * when the chat still holds it, and neither shows without it; [code] is the daemon's word, for Info.
 */
data class ShownError(
    val line: ErrorLine,
    val action: ErrorAction,
    val side: Sender,
    val code: String,
    val request: ClientEvent?,
) {
    init {
        val resends = action == ErrorAction.RETRY_SENDING || action == ErrorAction.RUN_AGAIN
        require(request != null || !resends) { "$action sends again a request the card does not hold" }
    }
}

/** A centred line (design section 8.4): "Stopped", a notice, a model change, its note. */
sealed interface PillText {
    data object Stopped : PillText

    data class Notice(
        val text: String,
    ) : PillText

    data class Switched(
        val label: String,
    ) : PillText

    data class BackToDefault(
        val label: String,
    ) : PillText

    data class Note(
        val text: String,
    ) : PillText
}

/** One thing in the chat's list, by a key that stays as it moves from the outbox to its row, or from live to sealed. */
sealed interface ChatItem {
    val key: String

    data class Day(
        override val key: String,
        val date: LocalDate,
    ) : ChatItem

    /** The unread divider, at the read frontier as it was on open (design section 13.5). */
    data object Unread : ChatItem {
        override val key: String = "unread"
    }

    /** Three bubbles in the thread's rhythm above the oldest row while an older page is on its way (section 13.5). */
    data object Older : ChatItem {
        override val key: String = "older"
    }

    data class Message(
        override val key: String,
        val message: ShownMessage,
    ) : ChatItem

    /** The thinking card of [turnId]: the working indicator's clock starts at [startedMono]. */
    data class Thinking(
        override val key: String,
        val turnId: String,
        val card: LiveCard,
        val startedMono: Long,
        val seed: Long,
    ) : ChatItem

    data class Error(
        override val key: String,
        val error: ShownError,
    ) : ChatItem

    data class Pill(
        override val key: String,
        val text: PillText,
    ) : ChatItem
}

/**
 * The error card of a turn that ran and failed with [code] (design section 13.9; PROTOCOL.md's closed list,
 * Q9): the timeout's line, `model_unavailable`'s with "Reset to default", `unsupported`'s with no action, and
 * the generic line for every other code, `interrupted` and `turn_failed` among them, with "Run again". "Run
 * again" needs the turn's [request]; a turn whose request the chat does not hold (a cron's, or one whose
 * message is older than the rows the list holds) shows its line with no action. None says "Nothing ran": only
 * a proven pre-execution refusal may, and the wire proves none.
 */
fun errorOf(
    code: String,
    request: ClientEvent?,
): ShownError {
    val (line, action) =
        when (code) {
            TIMEOUT -> ErrorLine.TIMEOUT to ErrorAction.RUN_AGAIN
            MODEL_UNAVAILABLE -> ErrorLine.MODEL_UNAVAILABLE to ErrorAction.RESET_TO_DEFAULT
            UNSUPPORTED -> ErrorLine.UNSUPPORTED to ErrorAction.NONE
            else -> ErrorLine.GENERIC to ErrorAction.RUN_AGAIN
        }
    val held = if (action == ErrorAction.RUN_AGAIN && request == null) ErrorAction.NONE else action
    return ShownError(line, held, Sender.Agent, code, request)
}

/** The error card of an outbox item the daemon refused before `accepted`: "Retry sending" (design section 13.5). */
fun notSentError(item: OutboxItem): ShownError =
    ShownError(
        ErrorLine.NOT_SENT,
        ErrorAction.RETRY_SENDING,
        Sender.User,
        checkNotNull(item.failure) { "${item.clientMsgId} has not failed" }.code,
        item.request,
    )

/** The `turn_error` codes the design words on their own (design section 13.9); every other is the generic line. */
internal const val TIMEOUT = "timeout"
internal const val MODEL_UNAVAILABLE = "model_unavailable"
internal const val UNSUPPORTED = "unsupported"

/** What a turn that ended leaves in the chat: "Stopped", an error card, or nothing. */
internal fun endingItem(
    turn: LiveTurn,
    request: ClientEvent?,
): ChatItem? {
    val outcome = turn.ending?.outcome ?: return null
    val key = "end:${turn.turnId}"
    return when (outcome) {
        TurnOutcome.Stopped -> ChatItem.Pill(key, PillText.Stopped)
        is TurnOutcome.Failed -> ChatItem.Error(key, errorOf(outcome.code, request))
        TurnOutcome.Completed, TurnOutcome.Over -> null
    }
}
