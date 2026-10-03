package io.tezra.fermix.session

import io.tezra.fermix.protocol.RequestOutcome
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate

/** What a session tells the app, besides the rows its announcer takes. */
sealed interface SessionEvent {
    /**
     * A step of a turn's machine (design section 8.2). [daemonSpeaking] is the turn's machine after the step
     * (TurnState.daemonSpeaking): a heading or a running tool on its card, when the working indicator reads
     * plain "Thinking", so the app reads it here rather than count the card's lines and chips again.
     */
    data class Turn(
        val effect: TurnEffect,
        val daemonSpeaking: Boolean,
    ) : SessionEvent

    /** The daemon took a request: its delivery tick, and the item left the outbox. */
    data class Accepted(
        val clientMsgId: String,
        val duplicate: Boolean,
    ) : SessionEvent

    /**
     * The daemon refused or failed a request, by `error{client_msg_id}` or, for one that failed while the
     * phone was away, by `request_status_page`. With [inOutbox] it was refused before `accepted`, and its
     * outbox item stays, failed, for "Try again" or "Remove from outbox" (design section 13.6). Without,
     * it left the outbox at `accepted` and its run failed: "Run again", and earlier actions may have
     * completed (design section 13.5). Either way running it again is a new request whose `retry_of`
     * names it (Session.retry).
     */
    data class RequestFailed(
        val clientMsgId: String,
        val failure: RequestFailure,
        val inOutbox: Boolean,
    ) : SessionEvent

    /** How a request stood at a reconnect (`request_status_page`). */
    data class RequestStatus(
        val outcome: RequestOutcome,
    ) : SessionEvent

    /** A card shown before the reconnect that the daemon no longer holds: "Closed while this phone was away". */
    data class ApprovalClosedWhileAway(
        val approvalId: String,
    ) : SessionEvent

    /** The profile's cache was dropped and is being rebuilt from the newest page (`mutations_gone`). */
    data class RebuildProfileCache(
        val mutationHeadSeq: ULong,
    ) : SessionEvent

    /** The read frontier moved: the notified set drops what it covers (design section 10). */
    data class ReadFrontier(
        val readUpToSeq: ULong,
    ) : SessionEvent

    /**
     * An older page arrived: [rows], the ones the cache does not hold yet, are for the cache alone and are
     * never announced, since they are not new. [prevBeforeSeq] is where the next older page starts, if any.
     * The newest page of an empty cache ends with one too, its [rows] empty: they went through the
     * announcer as new rows.
     */
    data class OlderLoaded(
        val rows: List<TimelineRow>,
        val prevBeforeSeq: ULong?,
    ) : SessionEvent

    /** The daemon's routes for later reconnects, from `hello_ack`, for the instance record. */
    data class Candidates(
        val candidates: List<Candidate>,
    ) : SessionEvent

    /** An `error` that names no request of the outbox, such as `request_backlog_full`. */
    data class Refused(
        val error: ServerEvent.Error,
    ) : SessionEvent

    /**
     * A server event the session does not own, passed on as it came: `hello_ack` (caps, instance,
     * profiles), approvals, reactions, link previews, media, model changes, transcripts, models and
     * search results.
     */
    data class Server(
        val event: ServerEvent.Known,
    ) : SessionEvent
}
