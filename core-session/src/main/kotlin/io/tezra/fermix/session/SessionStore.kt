package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MutationRow

/**
 * The cursors one (instance, profile) keeps across sessions: [lastServerSeq], the last row applied,
 * which `hello.last_server_seq` and `history_pull.after_seq` carry; [readUpToSeq], the read frontier;
 * [lastMutationSeq], the mutation feed's cursor, which `hello.last_mutation_seq` carries;
 * [announcedUpToSeq], the highest row such that every row applied up to it was announced or read, the
 * most an `ack` may say; and [lastUnannouncedSeq], the last row applied that the owner was not told of,
 * 0 when none. The ack frontier is stored apart from the cursor because a row the owner was not told of
 * holds it below the cursor, and a new session must not ack past that row (tla/specs/mobile_push,
 * PUSH-2); the held row is stored so that a read reaching it releases the ack on the next session too.
 */
data class StoredCursors(
    val lastServerSeq: ULong,
    val readUpToSeq: ULong,
    val lastMutationSeq: ULong,
    val announcedUpToSeq: ULong,
    val lastUnannouncedSeq: ULong,
)

/** Why the daemon refused a request: an `error{client_msg_id}`'s code and message, or a failed request's. */
data class RequestFailure(
    val code: String,
    val message: String,
)

/**
 * A `msg` or `command` the phone has persisted and not yet seen accepted (design section 13.6). One
 * with a [failure] stays for the UI and is never sent again: running it again is a new request with
 * a new client_msg_id and `retry_of` (design section 7, the `msg.retry_of?` row). [written] is set
 * before its frame first goes to a socket and never cleared: until then the owner may still edit or
 * remove it, and after it the daemon may have it (design section 13.6, "Queued and pending messages").
 */
data class OutboxItem(
    val request: ClientEvent,
    val failure: RequestFailure? = null,
    val written: Boolean = false,
) {
    init {
        require(request is ClientEvent.Msg || request is ClientEvent.Command) { "only a msg or a command is outboxed" }
    }

    val clientMsgId: String
        get() =
            when (request) {
                is ClientEvent.Msg -> request.clientMsgId
                is ClientEvent.Command -> request.clientMsgId
                else -> error("an outbox item holds a msg or a command")
            }
}

/**
 * What the daemon puts on a row it sent already, kept on the row the cache holds (SessionStore): each call is
 * one write, and false when the cache holds no such row, whose history carries it from then on.
 */
interface RowEdits {
    /**
     * A `reaction` on the owner's message [clientMsgId]: the cached row takes `{"emoji": [emoji]}` as its
     * metadata's `reaction`, its other metadata kept, as the mutation that persists it will put it (design
     * section 7, `reaction` durability, with its `ts`).
     */
    suspend fun applyReaction(
        clientMsgId: String,
        emoji: String,
    ): Boolean

    /**
     * A `link_preview` on row [serverSeq]: the cached row keeps [card] among its link previews, once per url
     * and at most four (PROTOCOL.md "Link previews").
     */
    suspend fun addLinkPreview(
        serverSeq: ULong,
        card: LinkPreviewCard,
    ): Boolean
}

/**
 * What one (instance, profile) persists for its session; the data layer implements it over its Room
 * database. Each call is one write the store makes whole or not at all. One session at a time owns a
 * store: the data layer opens at most one [Session] per (instance, profile), since two would race,
 * resend the same outbox and announce the same rows. The session calls it from its own dispatcher,
 * and an exception from it fails the session: its state is lost data otherwise. The edits a live event
 * makes to a cached row are its [RowEdits].
 */
interface SessionStore : RowEdits {
    suspend fun cursors(): StoredCursors

    /**
     * Called after the announcer has taken every row up to [seq]; [announcedUpToSeq] and
     * [lastUnannouncedSeq] are the ack frontier after it (StoredCursors), written in the same write. The
     * row's `ack` goes once this returns, and design section 7 holds the ack to 1 s after the
     * announcement, so the write is budgeted at well under that: a local database write takes
     * milliseconds. An ack that waited 500 ms or more is noted in the session's diagnostics with how long.
     */
    suspend fun setServerCursor(
        seq: ULong,
        announcedUpToSeq: ULong,
        lastUnannouncedSeq: ULong,
    )

    suspend fun setReadFrontier(seq: ULong)

    /** Updates the cached rows [rows] name in place, and records [lastMutationSeq], in one write. */
    suspend fun applyMutations(
        rows: List<MutationRow>,
        lastMutationSeq: ULong,
    )

    /**
     * The mutation feed no longer reaches back to this phone's cursor (`mutations_gone`): drops the
     * profile's cached timeline and its server cursor, and records [mutationHeadSeq], in one write. The
     * ack frontier stays: what the owner was told of does not change with the cache. The held row may
     * stay as it is: past the frontier, it went with the cache and is judged again.
     */
    suspend fun rebuildCache(mutationHeadSeq: ULong)

    /** Every item, in the order it was enqueued. */
    suspend fun outbox(): List<OutboxItem>

    suspend fun enqueue(item: OutboxItem)

    /** Removes [clientMsgId]'s item; an id the outbox does not hold, a duplicate receipt's, is no change. */
    suspend fun dequeue(clientMsgId: String)

    /**
     * Marks [clientMsgId]'s item written, just before its frame first goes; false when the outbox no longer
     * holds it, removed meanwhile, and then the frame does not go. One write, so it and [withdraw] are never
     * both true for an item.
     */
    suspend fun markWritten(clientMsgId: String): Boolean

    /**
     * Removes [clientMsgId]'s item if it failed or was never written, in one write; whether it did. One
     * written and not failed stays, since the daemon may have it.
     */
    suspend fun withdraw(clientMsgId: String): Boolean

    /** Marks the item the outbox holds for [clientMsgId] failed; the session never names another. */
    suspend fun markFailed(
        clientMsgId: String,
        failure: RequestFailure,
    )
}
