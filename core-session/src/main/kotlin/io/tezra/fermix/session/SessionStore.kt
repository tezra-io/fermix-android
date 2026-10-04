package io.tezra.fermix.session

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MutationRow

/** The attachments one `msg` carries at most (design section 8.5, "Caps": at most 10 items per send). */
const val MAX_ATTACHMENTS = 10

/**
 * The automatic restarts of one item's upload (design section 8.5, "Interrupted upload"): a disconnect cancels the
 * daemon's partial upload, so each reconnect starts again from `attach_begin`, three times at most. An item whose
 * upload started 1 + this many times and was cut again fails instead of starting once more.
 */
const val MAX_UPLOAD_RESTARTS = 3

/** A SHA-256 as the phone names a blob: lowercase hex, which the media cache keys its files by too. */
private val BLOB_DIGEST = Regex("[0-9a-f]{64}")

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
 * One attachment of an outbox `msg` (design section 8.5): what its `attach_begin` announces, and [source], the
 * path of the local file whose bytes go up, which the app keeps until the item leaves the outbox. [uploaded] is
 * set once the daemon holds the blob, its `present` after `attach_end` or at once for a digest it stored, and
 * never cleared: an attachment uploaded is never announced again, since its id names the blob for 48 hours
 * (PROTOCOL.md "Attachments").
 */
data class OutboxAttachment(
    val attachId: String,
    val kind: AttachKind,
    val mime: String,
    val sizeBytes: Long,
    val sha256: String,
    val name: String?,
    val source: String,
    val uploaded: Boolean = false,
) {
    init {
        require(attachId.isNotEmpty()) { "an attachment has an id" }
        require(mime.isNotEmpty()) { "$attachId has a media type" }
        require(sizeBytes >= 0) { "$attachId is $sizeBytes bytes" }
        require(BLOB_DIGEST.matches(sha256)) { "$attachId's digest is not a SHA-256 in lowercase hex" }
        require(name == null || name.isNotEmpty()) { "$attachId's name is empty" }
        require(source.isNotEmpty()) { "$attachId has a local source" }
    }
}

/**
 * A `msg` or `command` the phone has persisted and not yet seen accepted (design section 13.6). One
 * with a [failure] stays for the UI and is never sent again: running it again is a new request with
 * a new client_msg_id and `retry_of` (design section 7, the `msg.retry_of?` row). [written] is set
 * before its frame first goes to a socket and never cleared: until then the owner may still edit or
 * remove it, and after it the daemon may have it (design section 13.6, "Queued and pending messages").
 *
 * A `msg` may carry [attachments], in the order of its `attach_ids`, which go up before it does; one with none
 * may still name blobs the daemon holds, as a run again of a `msg` that was accepted does. [uploadStarts] counts
 * how many connections began uploading them, persisted with the item: the first start and then at most
 * [MAX_UPLOAD_RESTARTS] restarts (design section 8.5, "Interrupted upload").
 */
data class OutboxItem(
    val request: ClientEvent,
    val failure: RequestFailure? = null,
    val written: Boolean = false,
    val attachments: List<OutboxAttachment> = emptyList(),
    val uploadStarts: Int = 0,
) {
    init {
        require(request is ClientEvent.Msg || request is ClientEvent.Command) { "only a msg or a command is outboxed" }
        val named = (request as? ClientEvent.Msg)?.attachIds
        require(attachments.isEmpty() || attachments.map { it.attachId } == named) {
            "an item's attachments are its msg's attach_ids, in order"
        }
        require(attachments.size <= MAX_ATTACHMENTS) { "${attachments.size} attachments is past $MAX_ATTACHMENTS" }
        require(uploadStarts in 0..1 + MAX_UPLOAD_RESTARTS) { "an upload started $uploadStarts times" }
    }

    /** Whether an attachment of it is still to go up: its `msg` waits for that. */
    val uploading: Boolean get() = attachments.any { !it.uploaded }

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

    /**
     * A `transcript` of the owner's voice note [clientMsgId]: the cached row's content becomes [text], as the
     * mutation that persists it will make it (design section 7, the `transcript` row, which bumps `mutation_seq`).
     */
    suspend fun applyTranscript(
        clientMsgId: String,
        text: String,
    ): Boolean
}

/**
 * What an upload writes on its outbox item (Uploads), each one write: kept with the store (SessionStore), which
 * holds the item.
 */
interface UploadMarks {
    /**
     * Marks [attachId] of [clientMsgId]'s item uploaded, once the daemon said `present`; false when the outbox no
     * longer holds the item, removed meanwhile.
     */
    suspend fun markUploaded(
        clientMsgId: String,
        attachId: String,
    ): Boolean

    /**
     * Records that [clientMsgId]'s upload starts for the [starts]th time, before its first `attach_begin` on a
     * connection; false when the outbox no longer holds the item, and then nothing goes.
     */
    suspend fun setUploadStarts(
        clientMsgId: String,
        starts: Int,
    ): Boolean
}

/**
 * What one (instance, profile) persists for its session; the data layer implements it over its Room
 * database. Each call is one write the store makes whole or not at all. One session at a time owns a
 * store: the data layer opens at most one [Session] per (instance, profile), since two would race,
 * resend the same outbox and announce the same rows. The session calls it from its own dispatcher,
 * and an exception from it fails the session: its state is lost data otherwise. The edits a live event
 * makes to a cached row are its [RowEdits], and an upload's marks on its item its [UploadMarks].
 */
interface SessionStore :
    RowEdits,
    UploadMarks {
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
