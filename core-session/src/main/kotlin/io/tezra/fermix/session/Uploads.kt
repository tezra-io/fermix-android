package io.tezra.fermix.session

import io.tezra.fermix.protocol.AttachStatus
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.MAX_RAW_BYTES
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Items one connection uploads at most: each goes once per connection, and the daemon closes it within the hour.
 * Past it the connection ends, and the next one goes on.
 */
private const val MAX_UPLOAD_ITEMS = 10_000

/**
 * Bytes the socket may hold unwritten before the next chunk waits: sixteen chunks, far below OkHttp's 16 MiB,
 * past which it closes the socket (core-transport's Connection.send), and enough to keep a fast link busy.
 */
internal const val UPLOAD_QUEUE_BYTES = 16L * MAX_RAW_BYTES

/** How long a chunk waits between looks at the socket's queue. */
private const val PACE_MS = 50L

/** Looks at a full queue before the upload counts as stalled: [ANSWER_TIMEOUT_MS]'s 30 s. */
private const val MAX_PACE_WAITS = (ANSWER_TIMEOUT_MS / PACE_MS).toInt()

/**
 * The upload codes of PROTOCOL.md's error table, which name no attachment: while one upload runs, the one this
 * connection has on its way is the one refused.
 */
private val UPLOAD_REFUSALS =
    setOf(
        "upload_limit_reached",
        "store_upload_limit_reached",
        "upload_exists",
        "attachment_exists",
        "unknown_upload",
        "store_quota_exceeded",
        "media_too_large",
        "unexpected_chunk",
        "size_exceeded",
        "size_mismatch",
        "sha256_mismatch",
        "announced_hash_mismatch",
    )

/**
 * The code the daemon answers a failure only it can explain with (PROTOCOL.md "Errors"), its media store's commit
 * among them. Naming nothing, it may be the upload's or another frame's, so it ends the upload as a stall would.
 */
private const val REQUEST_FAILED = "request_failed"

/**
 * The codes the daemon refuses a chunk or an `attach_end` with, never an `attach_begin`: one per frame of an upload
 * it dropped (fermix's media store answers each later chunk and the end `unknown_upload`). Read while the next
 * attachment's `attach_begin` waits for its answer, one of them is the dropped upload's, still on its way, and is
 * let go: the daemon answers in order, so none comes once the next `attach_status` came.
 */
private val CHUNK_REFUSALS = setOf("unknown_upload", "unexpected_chunk", "size_exceeded")

/**
 * Late refusals of a dropped upload let go while an `attach_begin` waits for its answer: one per chunk sent before
 * the refusal was read, 60 MiB of them. Past it the attachment counts as stalled and the next connection restarts it.
 */
private const val MAX_LATE_REFUSALS = 1_024

/** The code an item fails with once its upload was cut off past its restarts (design section 8.5). */
const val UPLOAD_INTERRUPTED = "upload_interrupted"

/** The code an item fails with when its local file no longer holds the bytes its attachment announced. */
const val UPLOAD_SOURCE_CHANGED = "upload_source_changed"

/** Where one attachment's upload stands (design section 13.5): its bubble's ring and its one line. */
enum class UploadStage {
    /** Its chunks go: the ring shows [UploadProgress.sentBytes] of [UploadProgress.sizeBytes]. */
    UPLOADING,

    /** The daemon held its digest already, and no byte went: "Already on {host} — sent instantly". */
    DUPLICATE,

    /** Its `attach_end` was answered `present`. */
    UPLOADED,

    /** The connection ended before it was in: "Upload interrupted — resumes when connected". */
    INTERRUPTED,
}

/** One attachment's upload as the app shows it, by its `attach_id`. */
data class UploadProgress(
    val clientMsgId: String,
    val attachId: String,
    val sentBytes: Long,
    val sizeBytes: Long,
    val stage: UploadStage,
)

/** What came back for the attachment on its way. */
private sealed interface UploadReply {
    data class Status(
        val status: AttachStatus,
    ) : UploadReply

    data class Refused(
        val code: String,
    ) : UploadReply

    /** [REQUEST_FAILED] naming nothing: the upload goes again on the next connection, as after a stall. */
    data class Unexplained(
        val code: String,
    ) : UploadReply
}

/** How one attachment's upload ended on this connection. */
private sealed interface Uploaded {
    /** The daemon holds it: after `attach_end`, or at once for a digest it stored. */
    data object Present : Uploaded

    /** It cannot go as it is: the item fails with [failure]. */
    data class Failed(
        val failure: RequestFailure,
    ) : Uploaded

    /**
     * The daemon did not answer, or the socket did not drain, in 30 s, or it failed in a way only it can explain:
     * the connection ends (Ending.UploadStalled), and the next one starts it again.
     */
    data object Stalled : Uploaded
}

/**
 * One connection's uploads (design section 8.5, PROTOCOL.md "Attachments"): every outbox `msg` with an attachment
 * still to go, one item at a time and one attachment at a time, in order (AttachmentUpload). Each attachment the
 * daemon holds is marked so in the store, and only once every one is in does the `msg` go, through the outbox like
 * any other (Live.offer). The daemon drops a partial upload with its connection (onboarding gotcha 17), so a later
 * connection starts each attachment not yet in again from `attach_begin`, at most [MAX_UPLOAD_RESTARTS] times per
 * item, and then the item fails. An upload that stalls ends its connection, so the same rule applies to it at once
 * and no later `msg` waits behind it for the rest of the hour. Driven by its own coroutine ([run]) while the actor
 * hands it the daemon's answers; touched from the session's dispatcher alone.
 */
internal class Uploads(
    private val core: SessionCore,
    private val live: Live,
) {
    private val items = Channel<String>(Channel.UNLIMITED)
    private val taken = HashSet<String>()
    private val pending = HashSet<String>()
    private val replies = Channel<UploadReply>(1)
    private var current: OutboxAttachment? = null

    private val store: SessionStore get() = core.parts.store

    /** [item]'s upload, once on this connection; its `msg` comes back to the outbox once every attachment is in. */
    fun queue(item: OutboxItem) {
        require(item.uploading) { "${item.clientMsgId} has nothing to upload" }
        if (!taken.add(item.clientMsgId)) return
        pending += item.clientMsgId
        core.uploading.value = true
        items.trySend(item.clientMsgId)
    }

    /** Uploads each item queued, in order, until the connection ends and cancels it, or ends it as one stalls. */
    suspend fun run(): Ending {
        repeat(MAX_UPLOAD_ITEMS) {
            val clientMsgId = items.receive()
            val stalled = upload(clientMsgId) == Uploaded.Stalled
            pending -= clientMsgId
            core.uploading.value = pending.isNotEmpty()
            if (stalled) return Ending.UploadStalled
        }
        core.log(DiagnosticKind.BOUND, "$MAX_UPLOAD_ITEMS uploads on one connection; the next one goes on")
        return Ending.LifetimeReached
    }

    /** An `attach_status`: the answer for the attachment on its way; false when none is, or it names another. */
    fun status(event: ServerEvent.AttachStatusEvent): Boolean =
        current?.attachId == event.attachId && deliver(UploadReply.Status(event.status))

    /**
     * An `error` naming nothing, while an upload is on its way: with an upload's code, its refusal; [REQUEST_FAILED]
     * ends it as a stall; any other is not the upload's.
     */
    fun refused(error: ServerEvent.Error): Boolean =
        when {
            current == null -> false
            error.code in UPLOAD_REFUSALS -> deliver(UploadReply.Refused(error.code))
            error.code == REQUEST_FAILED -> deliver(UploadReply.Unexplained(error.code))
            else -> false
        }

    /**
     * The connection ended: each attachment shown on its way shows as interrupted until the next one starts it
     * again. Its coroutine was cancelled before this runs, so the shown stage says which one it was. An item whose
     * last restart this connection began fails now, with the deck's line (design section 8.5), rather than waiting
     * as interrupted for a connection that would only fail it.
     */
    suspend fun interrupt() {
        val last = 1 + MAX_UPLOAD_RESTARTS
        current = null
        pending.clear()
        core.uploading.value = false
        core.uploads.update { shown -> shown.mapValues { (_, progress) -> progress.cut() } }
        val spent = store.outbox().filter { it.failure == null && it.uploading && it.uploadStarts >= last }
        spent.forEach { fail(it.clientMsgId, interrupted(it.uploadStarts)) }
    }

    /** Lets go of the answer no attachment read, a dropped upload's late refusal, logged; the channel holds one. */
    private fun dropLate() {
        val late = replies.tryReceive().getOrNull() ?: return
        core.log(DiagnosticKind.REFUSED, "a late answer of an upload that ended let go: $late")
    }

    private fun deliver(reply: UploadReply): Boolean {
        if (!replies.trySend(reply).isSuccess) {
            core.log(DiagnosticKind.BOUND, "a second answer for ${current?.attachId} before the first was read")
        }
        return true
    }

    /**
     * [clientMsgId]'s attachments not yet in, then the outbox offered again, its `msg` with it; how the last one
     * tried ended, none when none was. The item is read again first: removed meanwhile, it goes no further, and past
     * its restarts it fails instead of starting again. A stall offers nothing: its connection ends.
     */
    private suspend fun upload(clientMsgId: String): Uploaded? {
        val held = store.outbox().firstOrNull { it.clientMsgId == clientMsgId } ?: return null
        val starts = held.uploadStarts
        var outcome: Uploaded? = null
        when {
            held.failure != null || !held.uploading -> Unit
            starts > MAX_UPLOAD_RESTARTS -> fail(clientMsgId, interrupted(starts))
            store.setUploadStarts(clientMsgId, starts + 1) -> outcome = attachments(held)
        }
        // Its msg goes once every attachment is in, and the msgs it held back after it; one that failed holds none.
        if (outcome != Uploaded.Stalled) live.offer(store.outbox())
        return outcome
    }

    /**
     * Each attachment of [item] not yet in, in order, until one is not: how the one that stopped it ended, or
     * [Uploaded.Present] once every one is in. One removed meanwhile is marked nowhere, and goes no further.
     */
    private suspend fun attachments(item: OutboxItem): Uploaded {
        for (attachment in item.attachments.filterNot { it.uploaded }) {
            val outcome = uploaded(item.clientMsgId, attachment)
            if (outcome is Uploaded.Failed) fail(item.clientMsgId, outcome.failure)
            val marked = outcome == Uploaded.Present && store.markUploaded(item.clientMsgId, attachment.attachId)
            if (!marked) return outcome
        }
        return Uploaded.Present
    }

    /** How [attachment]'s upload ended on this connection; a late answer of the one before is let go first. */
    private suspend fun uploaded(
        clientMsgId: String,
        attachment: OutboxAttachment,
    ): Uploaded {
        current = attachment
        dropLate()
        return try {
            AttachmentUpload(core, live, clientMsgId, attachment, replies).run()
        } finally {
            current = null
            dropLate()
        }
    }

    /** [clientMsgId] fails, kept in the outbox for "Not sent. Tap to retry sending." (design section 8.5). */
    private suspend fun fail(
        clientMsgId: String,
        failure: RequestFailure,
    ) {
        store.markFailed(clientMsgId, failure)
        core.emit(SessionEvent.RequestFailed(clientMsgId, failure, inOutbox = true))
    }
}

/**
 * One attachment's upload on one connection: `attach_begin`, answered `upload` or `present`; then its chunks from
 * index 0, each a 60 KiB raw tail read from its source on the session's io dispatcher and hashed as it goes; then
 * `attach_end`, answered `present`. Its progress shows in [SessionCore.uploads] as it goes.
 */
private class AttachmentUpload(
    private val core: SessionCore,
    private val live: Live,
    private val clientMsgId: String,
    private val attachment: OutboxAttachment,
    private val replies: ReceiveChannel<UploadReply>,
) {
    suspend fun run(): Uploaded {
        show(0L, UploadStage.UPLOADING)
        live.post(attachment.begin())
        return when (val begun = beginReply()) {
            UploadReply.Status(AttachStatus.PRESENT) -> {
                show(attachment.sizeBytes, UploadStage.DUPLICATE)
                Uploaded.Present
            }

            UploadReply.Status(AttachStatus.UPLOAD) -> {
                chunks() ?: end()
            }

            else -> {
                endedBy(begun)
            }
        }
    }

    /** `attach_end`, once every chunk went: in once it is answered `present`. */
    private suspend fun end(): Uploaded {
        live.post(ClientEvent.AttachEnd(attachment.attachId, attachment.sha256))
        val ended = replies.answer()
        if (ended != UploadReply.Status(AttachStatus.PRESENT)) return endedBy(ended)
        show(attachment.sizeBytes, UploadStage.UPLOADED)
        return Uploaded.Present
    }

    /**
     * The answer to its `attach_begin`, a dropped upload's late chunk refusals (CHUNK_REFUSALS) let go before it;
     * none past [ANSWER_TIMEOUT_MS] for one of them or [MAX_LATE_REFUSALS] of them.
     */
    private suspend fun beginReply(): UploadReply? {
        repeat(MAX_LATE_REFUSALS) {
            val reply = replies.answer()
            if (reply !is UploadReply.Refused || reply.code !in CHUNK_REFUSALS) return reply
            core.log(DiagnosticKind.REFUSED, "${reply.code} came late, for an upload before ${attachment.attachId}")
        }
        core.log(DiagnosticKind.BOUND, "$MAX_LATE_REFUSALS late refusals before ${attachment.attachId}'s answer")
        return null
    }

    /** Every chunk of its source; none when they all went, or how the upload ends. Its stream closes on every path. */
    private suspend fun chunks(): Uploaded? {
        var input: InputStream? = null
        try {
            input = withContext(core.parts.io) { FileInputStream(File(attachment.source)) }
            return sendChunks(checkNotNull(input))
        } catch (unreadable: IOException) {
            return changed("it could not be read: $unreadable")
        } finally {
            withContext(NonCancellable + core.parts.io) { input?.close() }
        }
    }

    private suspend fun sendChunks(input: InputStream): Uploaded? {
        val digest = MessageDigest.getInstance("SHA-256")
        val count = chunkCount(attachment.sizeBytes)
        var outcome: Uploaded? = null
        var index = 0
        while (outcome == null && index < count) {
            // An answer while the chunks go is a refusal: the daemon dropped the upload, so no more chunk goes.
            outcome = replies.tryReceive().getOrNull()?.let(::endedBy) ?: chunk(input, index++, digest)
        }
        return outcome ?: whole(input, digest)
    }

    /** Chunk [index], read, hashed and sent once the socket has room; none when it went. */
    private suspend fun chunk(
        input: InputStream,
        index: Int,
        digest: MessageDigest,
    ): Uploaded? {
        val from = index.toLong() * MAX_RAW_BYTES
        val want = minOf(MAX_RAW_BYTES.toLong(), attachment.sizeBytes - from).toInt()
        val bytes = withContext(core.parts.io) { input.readNBytes(want) }
        digest.update(bytes)
        val outcome =
            when {
                bytes.size != want -> changed("it ended at ${from + bytes.size} bytes")
                !paced() -> endedBy(null)
                else -> null
            }
        if (outcome == null) {
            live.post(ClientEvent.AttachChunk(attachment.attachId, index), bytes)
            show(from + bytes.size, UploadStage.UPLOADING)
        }
        return outcome
    }

    /** After the last chunk: the source ends there and hashes to the digest announced; none when it does. */
    private suspend fun whole(
        input: InputStream,
        digest: MessageDigest,
    ): Uploaded? {
        val longer = withContext(core.parts.io) { input.read() } != -1
        val actual = digest.digest().toHex()
        return when {
            longer -> changed("it holds more than ${attachment.sizeBytes} bytes")
            actual != attachment.sha256 -> changed("its bytes hash to $actual")
            else -> null
        }
    }

    /** Whether the socket drained below [UPLOAD_QUEUE_BYTES] within [MAX_PACE_WAITS] looks. */
    private suspend fun paced(): Boolean {
        repeat(MAX_PACE_WAITS) {
            if (live.channel.queuedBytes <= UPLOAD_QUEUE_BYTES) return true
            delay(PACE_MS)
        }
        return false
    }

    /**
     * An answer that ends the upload: a refusal fails its item; silence, a socket that does not drain, or a failure
     * only the daemon can explain leaves it interrupted for the next connection, which starts it again.
     */
    private fun endedBy(reply: UploadReply?): Uploaded =
        when (reply) {
            null, is UploadReply.Unexplained -> {
                val why = reply?.code ?: "no answer in $ANSWER_TIMEOUT_MS ms"
                core.log(DiagnosticKind.BOUND, "${attachment.attachId}'s upload stalled: $why; the connection ends")
                show(0L, UploadStage.INTERRUPTED)
                Uploaded.Stalled
            }

            is UploadReply.Refused -> {
                Uploaded.Failed(RequestFailure(reply.code, "${attachment.attachId} was refused"))
            }

            is UploadReply.Status -> {
                throw SessionProtocolError("attach_status ${reply.status} out of its place")
            }
        }

    private fun changed(why: String): Uploaded {
        core.log(DiagnosticKind.REFUSED, "${attachment.attachId}'s source changed: $why")
        return Uploaded.Failed(RequestFailure(UPLOAD_SOURCE_CHANGED, "its file changed: $why"))
    }

    private fun show(
        sentBytes: Long,
        stage: UploadStage,
    ) {
        val progress = UploadProgress(clientMsgId, attachment.attachId, sentBytes, attachment.sizeBytes, stage)
        core.uploads.update { it + (attachment.attachId to progress) }
    }
}

/** An upload on its way when its connection ended shows as interrupted; any other stays as it was. */
private fun UploadProgress.cut(): UploadProgress {
    val going = stage == UploadStage.UPLOADING
    return if (going) copy(stage = UploadStage.INTERRUPTED) else this
}

/** [this]'s `attach_begin`. */
internal fun OutboxAttachment.begin(): ClientEvent.AttachBegin =
    ClientEvent.AttachBegin(attachId, kind, mime, sizeBytes, name, sha256)

/** [clientMsgId]'s uploads leave the session's progress with it. */
internal fun SessionCore.forgetUploads(clientMsgId: String) {
    uploads.update { shown -> shown.filterValues { it.clientMsgId != clientMsgId } }
}

/** The daemon's next answer within [ANSWER_TIMEOUT_MS]; none past it. */
private suspend fun ReceiveChannel<UploadReply>.answer(): UploadReply? =
    withTimeoutOrNull(ANSWER_TIMEOUT_MS) { receive() }

/** The chunks a blob of [sizeBytes] goes in: none for an empty one. */
private fun chunkCount(sizeBytes: Long): Int = ((sizeBytes + MAX_RAW_BYTES - 1) / MAX_RAW_BYTES).toInt()

private fun interrupted(starts: Int): RequestFailure =
    RequestFailure(UPLOAD_INTERRUPTED, "its upload was cut off $starts times")

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }
