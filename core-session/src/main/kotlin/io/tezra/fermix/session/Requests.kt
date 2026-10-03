package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.RequestOutcome
import io.tezra.fermix.protocol.RequestState
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.encodeClientEvent

/** The most requests an outbox holds: four times the daemon's backlog of 32 (PROTOCOL.md). */
internal const val MAX_OUTBOX = 128

/** The code of a request `request_status_page` reports failed without a code of its own. */
private const val REQUEST_FAILED = "request_failed"

/** The message of a request `request_status_page` reports failed: the page carries a code alone. */
private const val FAILED_WHILE_AWAY = "failed while this phone was away"

/**
 * The outbox's rules (design section 13.6, PROTOCOL.md "Delivery and failure behavior"): a request is
 * persisted before it is sent, `accepted` clears it, a duplicate one too, and `error{client_msg_id}`
 * marks it failed and keeps it. A failed request is never sent again; running it again is a new one.
 * Design section 13.5's same-id "Retry sending" is the outbox's own: it resends a request with no
 * receipt on every connection. A request the daemon refused is not resent under its id, since a failed
 * request stays failed and its resend is a duplicate that never runs (PROTOCOL.md).
 */
internal class Requests(
    private val core: SessionCore,
) {
    private val store: SessionStore get() = core.parts.store

    suspend fun submit(request: ClientEvent) {
        core.requireOpen()
        val item = OutboxItem(request)
        val profile = (request as? ClientEvent.Msg)?.profileId ?: (request as? ClientEvent.Command)?.profileId
        require(profile == core.instance.profileId) { "a request for profile $profile in a session of another" }
        // The codec holds the request to protocol v2's rules; one it refuses is never stored.
        encodeClientEvent(SESSION_VERSION, 1uL, request)
        val items = store.outbox()
        check(items.size < MAX_OUTBOX) { "the outbox holds $MAX_OUTBOX requests already" }
        require(items.none { it.clientMsgId == item.clientMsgId }) { "${item.clientMsgId} is in the outbox already" }
        store.enqueue(item)
        core.live?.offer(item)
    }

    /**
     * "Run again" (design section 13.5): [failed] goes again as [newClientMsgId], whose `retry_of` names
     * it. The caller passes the request itself, since `accepted` took it out of the outbox before its run
     * failed; one refused before `accepted` is still there, failed, and leaves now.
     */
    suspend fun retry(
        failed: ClientEvent,
        newClientMsgId: String,
    ) {
        core.requireOpen()
        val clientMsgId = OutboxItem(failed).clientMsgId
        require(newClientMsgId != clientMsgId) { "running $clientMsgId again is a new request, with a new id" }
        val held = store.outbox().firstOrNull { it.clientMsgId == clientMsgId }
        require(held == null || held.failure != null) { "$clientMsgId has not failed; the outbox sends it" }
        val again =
            when (failed) {
                is ClientEvent.Msg -> failed.copy(clientMsgId = newClientMsgId, retryOf = clientMsgId)
                is ClientEvent.Command -> failed.copy(clientMsgId = newClientMsgId)
                else -> error("an outbox item holds a msg or a command")
            }
        submit(again)
        if (held != null) store.dequeue(clientMsgId)
    }

    /**
     * `accepted`: the request leaves the outbox, and the owner's own `msg` opens its card (design section 8.2,
     * `accepted(own msg)`). A duplicate is a resend of a request that ran or runs already, whose card came the
     * first time; a command's answer comes inline (Dispatch), and one the daemon runs as a turn shows from its
     * `turn_started`.
     */
    suspend fun accepted(event: ServerEvent.Accepted) {
        store.dequeue(event.clientMsgId)
        core.emit(SessionEvent.Accepted(event.clientMsgId, event.duplicate))
        val opens = !event.duplicate && event.clientMsgId !in core.commands
        if (opens) core.turns { it.apply(TurnEvent.Accepted(event.clientMsgId)) }
    }

    /**
     * "Remove from outbox" and a queued message's Remove or Edit (design section 13.6): a request the daemon
     * refused, or one never written to a socket, leaves it; whether it did.
     */
    suspend fun remove(clientMsgId: String): Boolean {
        core.requireOpen()
        require(clientMsgId.isNotEmpty()) { "a removal names its request" }
        return store.withdraw(clientMsgId)
    }

    /**
     * `error{client_msg_id}`: the request failed, before `accepted` or after it, and so did its turn. An
     * approval's answer runs no turn of its own; the turn it answers goes on with or without it.
     */
    suspend fun failed(
        clientMsgId: String,
        error: ServerEvent.Error,
    ) {
        val failure = RequestFailure(error.code, error.message)
        if (isApprovalAnswer(clientMsgId)) return answerFailed(clientMsgId, failure)
        val inOutbox = markFailedIfHeld(clientMsgId, failure)
        core.emit(SessionEvent.RequestFailed(clientMsgId, failure, inOutbox))
        core.turns { it.apply(TurnEvent.TurnError(turnIdOf(clientMsgId), error.code, error.message)) }
    }

    /**
     * A `request_status_page`: a request the daemon has is no longer the outbox's, unless it failed (outboxOf);
     * the app hears every outcome, and each request's turn moves as its state says (statusTurnEvent). An
     * approval's answer runs no turn, so its state moves none.
     */
    suspend fun status(page: ServerEvent.RequestStatusPage) {
        page.requests.forEach { outcome ->
            val answer = isApprovalAnswer(outcome.clientMsgId)
            outboxOf(outcome, answer)
            core.emit(SessionEvent.RequestStatus(outcome))
            if (!answer) core.turns { it.apply(statusTurnEvent(outcome)) }
        }
    }

    /**
     * What a request's state at a reconnect does to the outbox: one the daemon has leaves it, unless it failed.
     * A shown turn's request left the outbox at `accepted`, so only the outbox's own are marked failed; a failed
     * one is told as `error{client_msg_id}` tells it, whether the outbox holds it or not, and a failed
     * approval's answer as [failed] tells it.
     */
    private suspend fun outboxOf(
        outcome: RequestOutcome,
        answer: Boolean,
    ) {
        val clientMsgId = outcome.clientMsgId
        val failure = RequestFailure(outcome.error ?: REQUEST_FAILED, FAILED_WHILE_AWAY)
        when {
            outcome.status != RequestState.FAILED -> store.dequeue(clientMsgId)
            answer -> answerFailed(clientMsgId, failure)
            else -> core.emit(SessionEvent.RequestFailed(clientMsgId, failure, markFailedIfHeld(clientMsgId, failure)))
        }
    }

    /** Marks [clientMsgId]'s item failed when the outbox holds it; whether it did. */
    private suspend fun markFailedIfHeld(
        clientMsgId: String,
        failure: RequestFailure,
    ): Boolean {
        val held = store.outbox().any { it.clientMsgId == clientMsgId }
        if (held) store.markFailed(clientMsgId, failure)
        return held
    }

    /**
     * An approval's answer refused or failed: it leaves the outbox, the token with it, and its card may be
     * answered again, its buttons its retry (Session.answerApproval). The app hears it as a request that left
     * the outbox; the turn it answers goes on with or without it.
     */
    private suspend fun answerFailed(
        clientMsgId: String,
        failure: RequestFailure,
    ) {
        core.approvals.answerRefused(clientMsgId)
        store.dequeue(clientMsgId)
        core.emit(SessionEvent.RequestFailed(clientMsgId, failure, inOutbox = false))
    }

    /** The requests `request_status` asks after: every outbox item not failed, then [turnRequests]. */
    suspend fun pendingIds(turnRequests: List<String>): List<String> =
        (store.outbox().filter { it.failure == null }.map { it.clientMsgId } + turnRequests).distinct()

    /** After reconciliation: every outbox item not failed, in order, once on this connection. */
    suspend fun drain(live: Live) {
        live.drain { store.outbox() }
    }
}

/**
 * What a request's state says of its turn. Queued or running, its card shows as on a live `accepted`, on
 * every model, the routes that send no `turn_started` included (onboarding gotcha 19); design section 8.2
 * shows it after a reconnect for the turns `hello_ack.active_turns` lists, which leaves out a request
 * still queued. Completed or failed, its turn ended while the phone was away: `active_turns` says so too
 * when the daemon sends it, which leaves the turn idle and this a no-op for a completed one, and this alone
 * when it does not. A failed one shows its error (design section 8.2, "failed → the error card").
 */
private fun statusTurnEvent(outcome: RequestOutcome): TurnEvent {
    val turnId = turnIdOf(outcome.clientMsgId)
    return when (outcome.status) {
        RequestState.ACCEPTED, RequestState.RUNNING -> TurnEvent.Accepted(outcome.clientMsgId)
        RequestState.COMPLETED -> TurnEvent.TurnDone(turnId)
        RequestState.FAILED -> TurnEvent.TurnError(turnId, outcome.error ?: REQUEST_FAILED, FAILED_WHILE_AWAY)
    }
}
