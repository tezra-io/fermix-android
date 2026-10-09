package io.tezra.fermix.demo

import io.tezra.fermix.protocol.ApprovalOutcome
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.MessageKind
import io.tezra.fermix.protocol.RequestState
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.turnIdOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A request the daemon could not run: PROTOCOL.md's word for a failure only it can explain. */
internal const val REQUEST_FAILED = "request_failed"

/** A `msg` names an attachment the daemon does not hold (PROTOCOL.md "Attachments"). */
private const val ATTACHMENT_UNAVAILABLE = "attachment_unavailable"

/**
 * The owner's requests (PROTOCOL.md "Delivery and failure behavior", "Streaming a turn", "Approvals"): a `msg` or a
 * `command` is claimed by its id, answered `accepted`, written as the owner's row and run; the same id again is a
 * duplicate and runs nothing, and the same id with other words is refused. A message is answered by a turn
 * (DemoTurns), or, naming an attachment the demo does not hold, fails once claimed (`attachment_unavailable`);
 * `/stop` stops every turn and says so inline; `/confirm` and `/deny` answer the approval whose token they carry
 * inline, with the engine's words, and a grant runs the request it held up again; `/model` switches the chat's model
 * (DemoModels); the palette's others answer inline; `cancel` stops the one request's turn and is never answered.
 */
internal class DemoRequests(
    private val connection: DemoConnection,
) {
    private val home = connection.home
    private val parts = connection.parts
    private val turns = DemoTurns(home, parts)

    fun answer(event: ClientEvent) {
        when (event) {
            is ClientEvent.Msg -> msg(event)
            is ClientEvent.Command -> command(event)
            is ClientEvent.Cancel -> turns.cancel(event.clientMsgId)
            else -> error("${nameOf(event)} is not a request")
        }
    }

    private fun msg(msg: ClientEvent.Msg) {
        if (duplicate(msg.clientMsgId, msg.text)) return
        claim(msg.clientMsgId, msg.text)
        val media = msg.attachIds.map { parts.attached[it] }
        // Claimed and accepted first, then failed, as the engine resolves attachments as the request runs: the
        // request stays failed, and the same id again is its duplicate ("Run again" sends a new one).
        if (media.any { it == null }) return fail(msg.clientMsgId, ATTACHMENT_UNAVAILABLE, "an attachment is gone")
        written(msg.clientMsgId, msg.text, media.filterNotNull())
        turns.reply(msg.clientMsgId, msg.text, media.isNotEmpty())
    }

    private fun command(command: ClientEvent.Command) {
        val words = "/${command.name}" + command.args?.let { " $it" }.orEmpty()
        if (duplicate(command.clientMsgId, words)) return
        claim(command.clientMsgId, words)
        written(command.clientMsgId, words, emptyList())
        when (command.name) {
            "stop" -> stop(command.clientMsgId)
            "confirm" -> approval(command, ApprovalOutcome.APPROVED)
            "deny" -> approval(command, ApprovalOutcome.DENIED)
            "model" -> DemoModels(connection).pick(command.clientMsgId, command.args)
            else -> inline(command.clientMsgId, paletteAnswer(command.name))
        }
    }

    /** Whether [clientMsgId] was claimed already: `accepted` again as a duplicate, or refused for other words. */
    private fun duplicate(
        clientMsgId: String,
        words: String,
    ): Boolean {
        val claimed = home.claims[clientMsgId] ?: return false
        if (claimed.text != words) {
            connection.send(ServerEvent.Error("client_message_conflict", "other words under one id", clientMsgId))
        } else {
            connection.send(ServerEvent.Accepted(clientMsgId, duplicate = true, serverSeq = claimed.resultSeq))
        }
        return true
    }

    private fun claim(
        clientMsgId: String,
        words: String,
    ) {
        home.claims[clientMsgId] = Claim(words, RequestState.ACCEPTED, connection.phoneKey)
        connection.send(ServerEvent.Accepted(clientMsgId, duplicate = false))
    }

    /** The owner's row of the request, to every phone, the sender's own with its id to match its outbox. */
    private fun written(
        clientMsgId: String,
        words: String,
        media: List<DemoBlob>,
    ) {
        val refs = media.map { it.mediaRef() }
        val kind = if (refs.isEmpty()) MessageKind.TEXT else MessageKind.MEDIA
        val row =
            home.write(
                HistoryMessage(0uL, OWNER, words, stamp(parts.wallMs()), refs, kind, clientMsgId = clientMsgId),
            )
        home.broadcast(rowEvent(row))
    }

    /** `/stop`: every turn stopped, each ending `cancelled`, and the stop's own answer written inline. */
    private fun stop(clientMsgId: String) {
        val stopped = turns.stopAll()
        inline(clientMsgId, if (stopped == 0) "Nothing was running." else "Stopped.")
    }

    /**
     * `/confirm` or `/deny` with an approval's token, as the engine's sandbox command answers it: the card withdrawn
     * with [outcome] to every phone, then the command's own answer inline, and on a grant the request the card held
     * up run again as a turn of its own; a token no card waits with is answered inline that it failed.
     */
    private fun approval(
        command: ClientEvent.Command,
        outcome: ApprovalOutcome,
    ) {
        val approval = resolve(home, command.args, outcome)
        val granted = outcome == ApprovalOutcome.APPROVED
        val text =
            when {
                approval == null && granted -> "Confirmation failed: :unknown_token"
                approval == null -> "Denial failed: :unknown_token"
                granted -> "Sandbox updated. Access granted — resuming your request.\n${approval.card.grants}"
                else -> "Sandbox change denied — the pending grant was discarded."
            }
        inline(command.clientMsgId, text)
        if (approval != null && granted) turns.resume(approval.resumed)
    }

    /** A request answered in one bubble, a bare `text_done` on a turn nothing of which shows (design section 8.2). */
    private fun inline(
        clientMsgId: String,
        text: String,
    ) {
        val turnId = turnIdOf(clientMsgId)
        val metadata = JsonObject(mapOf("turn_id" to JsonPrimitive(turnId)))
        val row = home.write(HistoryMessage(0uL, AGENT, text, stamp(parts.wallMs()), emptyList(), metadata = metadata))
        home.claims.getValue(clientMsgId).apply {
            state = RequestState.COMPLETED
            resultSeq = row.serverSeq
        }
        home.broadcast(ServerEvent.TextDone(turnId, row.serverSeq, text))
    }

    /** A request the daemon took and could not run: `error` naming it by [code], after `accepted`. */
    private fun fail(
        clientMsgId: String,
        code: String,
        message: String,
    ) {
        home.claims.getValue(clientMsgId).apply {
            state = RequestState.FAILED
            error = code
        }
        connection.send(ServerEvent.Error(code, message, clientMsgId))
    }
}

/** A palette command's answer, from the commands `hello_ack` offers. */
private fun paletteAnswer(name: String): String =
    when (name) {
        "help" -> DEMO_COMMANDS.joinToString("\n") { "- `/${it.name}`: ${it.description}" }
        "tasks" -> "Nothing runs in the background: this is the demo."
        "new" -> "Started a fresh conversation session."
        "compact" -> "Compacted the conversation to keep it within the model's window."
        else -> "`/$name` is not a command here; `/help` lists them."
    }

/** A kept row as the daemon announces it live (PROTOCOL.md "Timeline shapes"). */
internal fun rowEvent(row: HistoryMessage): ServerEvent.Row =
    ServerEvent.Row(
        profileId = MAIN,
        serverSeq = row.serverSeq,
        role = row.role,
        text = row.content,
        ts = row.ts,
        mediaRefs = row.mediaRefs,
        kind = row.kind,
        clientMsgId = row.clientMsgId,
        inReplyTo = row.inReplyTo,
        metadata = row.metadata,
        linkPreviews = row.linkPreviews,
    )
