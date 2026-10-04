package io.tezra.fermix.session

import io.tezra.fermix.protocol.ApprovalOutcome
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.ModelSource
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

    /**
     * An approval card (design section 8.4), new, or replayed in place by its id after a reconnect with the
     * seconds it has left. Its token and its routes stay in the session, which sends the one the owner picks
     * (Session.answerApproval): nothing here can render the token.
     */
    data class Approval(
        val approvalId: String,
        val kind: String,
        val text: String,
        val detail: String?,
        val ttlS: Int,
    ) : SessionEvent

    /** The daemon withdrew [approvalId]'s card: approved, denied or expired (`approval_resolved`). */
    data class ApprovalResolved(
        val approvalId: String,
        val outcome: ApprovalOutcome,
    ) : SessionEvent

    /**
     * The owner answered [approvalId]'s card: its route is in the outbox as [clientMsgId], whose refusal makes
     * the card answerable again ([RequestFailed]).
     */
    data class ApprovalAnswered(
        val approvalId: String,
        val approve: Boolean,
        val clientMsgId: String,
    ) : SessionEvent

    /** A card shown before the reconnect that the daemon no longer holds: "Closed while this phone was away". */
    data class ApprovalClosedWhileAway(
        val approvalId: String,
    ) : SessionEvent

    /** The profile's cache was dropped and is being rebuilt from the newest page (`mutations_gone`). */
    data class RebuildProfileCache(
        val mutationHeadSeq: ULong,
    ) : SessionEvent

    /**
     * A connection's reconnect reconciliation is done, its outbox drained: the `hello` the app registers this
     * phone's push token at (design section 10, "Registration"), as a request that is never queued may go now.
     * [pulledInFull] says the session was opened to pull in full (SessionParts.fullPull), as this connection's
     * first pull has asked; a session opened before the app asked for that pulls as always.
     */
    data class Reconciled(
        val pulledInFull: Boolean,
    ) : SessionEvent

    /**
     * The read frontier, on every `hello_ack` and `read_state` whether it moved or not, and whenever this
     * phone's own read moves it: the notified set drops what it covers (design section 10, "Lifecycle on the
     * phone").
     */
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

    /**
     * The daemon reacted to the owner's message [inReplyTo] with [emoji]; [stored] says the cached row took it
     * into its metadata's `reaction`, as the mutation feed will (design section 7, `reaction` durability).
     */
    data class Reaction(
        val inReplyTo: String,
        val emoji: String,
        val stored: Boolean,
    ) : SessionEvent

    /**
     * A preview of row [serverSeq]'s link (`link_preview`); [stored] says the cached row keeps it with its link
     * previews, as its history carries it from then on.
     */
    data class LinkPreview(
        val serverSeq: ULong,
        val card: LinkPreviewCard,
        val stored: Boolean,
    ) : SessionEvent

    /**
     * The owner's voice note [clientMsgId] was transcribed (`transcript`): its row's content is [text] from now
     * on; [stored] says the cached row took it, as the mutation feed will (design section 7, the `transcript` row).
     */
    data class Transcript(
        val clientMsgId: String,
        val text: String,
        val stored: Boolean,
    ) : SessionEvent

    /**
     * The chat's model changed, on every device (`model_changed`): to an override, or back to the config's
     * default, with the daemon's [note] when it sent one.
     */
    data class ModelChanged(
        val provider: String,
        val model: String,
        val label: String,
        val source: ModelSource,
        val note: String?,
    ) : SessionEvent

    /**
     * A `models` page no pull of this session's asked for: the daemon's answer to a `/model` sent as a command
     * (design section 7, the `models` row). A pull's own pages are its answer (Session.pullModels).
     */
    data class Models(
        val entries: List<ModelEntry>,
        val next: Boolean,
    ) : SessionEvent

    /** An `error` that names no request of the outbox, such as `request_backlog_full`. */
    data class Refused(
        val error: ServerEvent.Error,
    ) : SessionEvent

    /**
     * A server event the session does not own, passed on as it came: `hello_ack` (caps, instance,
     * profiles), notices, an attachment status no upload waits for, and a blob's frames no fetch of this
     * session's asked for.
     */
    data class Server(
        val event: ServerEvent.Known,
    ) : SessionEvent
}
