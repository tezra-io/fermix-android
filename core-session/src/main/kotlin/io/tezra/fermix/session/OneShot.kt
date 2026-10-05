package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.encodeClientEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/** A search's query at most, in Unicode scalar values (design section 7, the `history_search` row). */
const val MAX_QUERY_SCALARS = 256

/**
 * Replies one fetch reads at most: its blob's frames, one chunk a step, and the queue's moves before it. A
 * 20 MiB blob is 342 chunks of 60 KiB; past the bound the fetch gives up as [OneShot.TimedOut].
 */
private const val MAX_FETCH_REPLIES = 1_000_000

/** Random bytes in an approval answer's client_msg_id after its card's id. */
private const val ANSWER_ID_BYTES = 8

/**
 * How a one-shot request ended (Session.search, Session.pullModels, Session.fetchMedia): one asked of the
 * connection that is up, never kept in the outbox, never sent again by the session.
 */
sealed interface OneShot<out T> {
    data class Answered<out T>(
        val value: T,
    ) : OneShot<T>

    /** No connection was up and reconciled: nothing went, and nothing waits for a later one. */
    data object Offline : OneShot<Nothing>

    /** As many of its kind wait on the connection already as it takes (8 searches and pulls; 8 fetches). */
    data object Busy : OneShot<Nothing>

    /**
     * The daemon refused it with [code]: a fetch, by `error{ref}`. A search or a pull it refuses with an `error`
     * naming nothing ends as [TimedOut], the app hearing the `error` (Asked).
     */
    data class Refused(
        val code: String,
    ) : OneShot<Nothing>

    /** No answer within 30 s, or for a paged or streamed one no next part (ANSWER_TIMEOUT_MS). */
    data object TimedOut : OneShot<Nothing>

    /** The connection ended before the answer was whole. */
    data object Interrupted : OneShot<Nothing>

    /**
     * The request is past what one frame carries, its header over 4,096 bytes (PROTOCOL.md), as a fetch of a ref the
     * wire gave at any length is: nothing went, and nothing waits.
     */
    data object TooLong : OneShot<Nothing>
}

/** A blob [fetchMedia] wrote whole: as its `media_begin` described it, its [sha256] the one its bytes hash to. */
data class FetchedMedia(
    val kind: String,
    val mime: String,
    val sizeBytes: Long,
    val sha256: String,
    val filename: String?,
)

/**
 * A fetched blob's bytes broke what its frames said of them, its order, its size or its digest, or came further
 * ahead of its file than a fetch holds in memory.
 */
class MediaMismatchException(
    message: String,
) : IOException(message)

/**
 * Asks the daemon's full-text index for [query], newest first, before [beforeSeq] when given (design section
 * 13.7, D24): the page of up to 20 hits, whose `next_before_seq` is the next page's cursor, never a hit on an
 * approval's answer, whose words hold the card's token (SideEvents). Asked once of the connection that is up and
 * reconciled, never queued: [OneShot.Offline] without one. The query goes nowhere but the socket; no diagnostic
 * quotes it.
 */
suspend fun Session.search(
    query: String,
    beforeSeq: ULong? = null,
): OneShot<ServerEvent.SearchResults> = calls.search(query, beforeSeq)

/**
 * The models the chat may switch to (design section 8.6): every page of the daemon's `models` answer to one
 * `models_pull`, assembled in order; asked as [search] is.
 */
suspend fun Session.pullModels(): OneShot<List<ModelEntry>> = calls.models()

/**
 * Downloads the blob [ref] names into [into], which it creates or empties (PROTOCOL.md "Media downloads"): its
 * chunks are written as they come, and at its end its size and SHA-256 are checked against its `media_begin`
 * and `media_end`. A blob whose bytes do not match throws [MediaMismatchException]; on that and on every other
 * way it ends unanswered the file is deleted. A [ref] past what one `media_fetch` carries is [OneShot.TooLong], asked
 * of no one. Asked as [search] is; 30 s without a frame of it, or of a blob
 * the daemon serves before it, is [OneShot.TimedOut]. [firstChunk] is handed a copy of the blob's first chunk as
 * it comes, in the caller's coroutine: an image's bubble paints its dominant colour from it while the rest
 * streams (design section 13.5), since the wire carries no colour of its own.
 */
suspend fun Session.fetchMedia(
    ref: String,
    into: File,
    firstChunk: (ByteArray) -> Unit = {},
): OneShot<FetchedMedia> = calls.fetch(ref, into, firstChunk)

/**
 * The owner's answer to the approval card [approvalId] (PROTOCOL.md "Approvals"): its approve or deny route
 * sent as the `command` the daemon expects, the leading "/" dropped, the first word the name and the rest the
 * args. It is an outbox item like any `command`, at least once, under an id that starts with
 * [APPROVAL_ANSWER_PREFIX]; the token travels in it and nowhere else. A card the session does not show, one
 * answered already or one whose `ttl_s` ran out is not answered, and says so; so is an answer past what one `command`
 * carries ([ApprovalAnswer.TooLong]) and one the full outbox refuses ([ApprovalAnswer.OutboxFull]).
 */
suspend fun Session.answerApproval(
    approvalId: String,
    approve: Boolean,
): ApprovalAnswer = calls.answer(approvalId, approve)

/**
 * The calls of [Session] that ask and wait (the functions above): each asks on the session's dispatcher,
 * [confined], and reads its answer in its caller's coroutine, which it gives up with when that is cancelled.
 * A fetch writes its file on [io].
 */
internal class OneShotCalls(
    private val core: SessionCore,
    private val requests: Requests,
    private val runner: Runner,
    private val confined: CoroutineDispatcher,
) {
    private val io: CoroutineDispatcher get() = core.parts.io

    suspend fun search(
        query: String,
        beforeSeq: ULong?,
    ): OneShot<ServerEvent.SearchResults> {
        require(query.isNotBlank()) { "a search has words" }
        require(query.codePointCount(0, query.length) <= MAX_QUERY_SCALARS) { "a query past $MAX_QUERY_SCALARS" }
        require(beforeSeq == null || beforeSeq > 0uL) { "there is no row before row 0" }
        return asking({ it.asked.search(query, beforeSeq) }) { waiter -> page(next(waiter)) }
    }

    suspend fun models(): OneShot<List<ModelEntry>> = asking({ it.asked.pullModels() }, ::modelPages)

    /**
     * The blob [ref] into [into]: [OneShot.TooLong] for a ref past what one `media_fetch` carries, which the wire holds
     * to no length, before anything goes or waits.
     */
    suspend fun fetch(
        ref: String,
        into: File,
        firstChunk: (ByteArray) -> Unit,
    ): OneShot<FetchedMedia> {
        require(ref.isNotEmpty()) { "a fetch names its blob" }
        if (headerPast(ClientEvent.MediaFetch(ref)) != null) return OneShot.TooLong
        return asking({ it.fetches.fetch(ref) }) { waiter -> blob(waiter, into, firstChunk) }
    }

    suspend fun answer(
        approvalId: String,
        approve: Boolean,
    ): ApprovalAnswer {
        require(approvalId.isNotEmpty()) { "an answer names its card" }
        return withContext(confined) {
            runner.request {
                core.requireOpen()
                core.approvals.refusal(approvalId, core.now()) ?: sendAnswer(approvalId, approve)
            }
        }
    }

    /**
     * The card's route into the outbox as a command, then the card waits on it and the app hears it went. The
     * command, the card's id in its own, is weighed as the codec encodes it first, at the largest seq: one past one
     * frame's header is [ApprovalAnswer.TooLong], and one the full outbox refuses [ApprovalAnswer.OutboxFull],
     * nothing stored of either.
     */
    private suspend fun sendAnswer(
        approvalId: String,
        approve: Boolean,
    ): ApprovalAnswer {
        val suffix =
            core.parts.random
                .nextBytes(ANSWER_ID_BYTES)
                .toHex()
        val clientMsgId = "$APPROVAL_ANSWER_PREFIX$approvalId:$suffix"
        val route = core.approvals.route(approvalId, approve)
        val answer = ClientEvent.Command(clientMsgId, core.instance.profileId, route.name, route.args)
        val refused =
            when {
                headerPast(answer) != null -> ApprovalAnswer.TooLong
                !requests.submit(answer) -> ApprovalAnswer.OutboxFull
                else -> null
            }
        if (refused != null) return refused
        core.approvals.answered(approvalId, clientMsgId)
        core.emit(SessionEvent.ApprovalAnswered(approvalId, approve, clientMsgId))
        return ApprovalAnswer.Sent(clientMsgId)
    }

    /**
     * [ask] of the connection that is up once it is reconciled, an ended session refusing it, then [read] of
     * its replies. Whatever comes after [read] returns is dropped, and so is all of it when the caller is
     * cancelled, even as the ask returns; the waiter is marked given up, which lets a search or a pull go once
     * its 30 s are over (Asked.retire).
     */
    private suspend fun <T> asking(
        ask: (Live) -> Waiter?,
        read: suspend (Waiter) -> OneShot<T>,
    ): OneShot<T> {
        var asked: Waiter? = null
        try {
            val unsent =
                withContext(confined) {
                    core.requireOpen()
                    val live = core.live?.takeIf { it.reconciled }
                    asked = live?.let(ask)
                    if (live == null) OneShot.Offline else OneShot.Busy.takeIf { asked == null }
                }
            return unsent ?: read(checkNotNull(asked))
        } finally {
            asked?.gaveUp = true
            asked?.replies?.cancel()
        }
    }

    /** A pull's pages, assembled until one says no `next`, or the bound ended them. */
    private suspend fun modelPages(waiter: Waiter): OneShot<List<ModelEntry>> {
        val entries = mutableListOf<ModelEntry>()
        var outcome: OneShot<List<ModelEntry>>? = null
        var pages = 0
        while (outcome == null && pages <= MAX_MODEL_PAGES) {
            outcome = modelPage(next(waiter), entries)
            pages++
        }
        return outcome ?: OneShot.Answered(entries.toList())
    }

    /**
     * The blob's frames into [into], which goes unless the blob came whole and matched. The stream is opened in
     * the guarded block and kept as it opens: a caller cancelled as the open returns, which drops what the open
     * returned, still has it closed and the file deleted.
     */
    private suspend fun blob(
        waiter: Waiter,
        into: File,
        firstChunk: (ByteArray) -> Unit,
    ): OneShot<FetchedMedia> {
        var out: OutputStream? = null
        var outcome: OneShot<FetchedMedia>? = null
        try {
            withContext(io) { out = FileOutputStream(into) }
            val write = BlobWrite(checkNotNull(out), io, firstChunk)
            var replies = 0
            while (outcome == null && replies < MAX_FETCH_REPLIES) {
                outcome = write.take(next(waiter))
                replies++
            }
        } finally {
            withContext(NonCancellable + io) {
                out?.close()
                if (outcome !is OneShot.Answered) Files.deleteIfExists(into.toPath())
            }
        }
        return outcome ?: OneShot.TimedOut
    }

    /**
     * [waiter]'s next reply within [ANSWER_TIMEOUT_MS]: none past it; once no more come, [Reply.Ended], or
     * [Reply.Broken] when they ended as the caller fell behind them (Waiter.overrun).
     */
    private suspend fun next(waiter: Waiter): Reply? =
        withTimeoutOrNull(ANSWER_TIMEOUT_MS) {
            waiter.replies.receiveCatching().getOrNull() ?: waiter.overrun?.let(Reply::Broken) ?: Reply.Ended
        }
}

/** A search's one reply as its outcome. */
private fun page(reply: Reply?): OneShot<ServerEvent.SearchResults> =
    ended(reply) ?: OneShot.Answered((reply as Reply.Event).event as ServerEvent.SearchResults)

/** One more page into [entries]: the whole answer once it says no `next`; none while more come. */
private fun modelPage(
    reply: Reply?,
    entries: MutableList<ModelEntry>,
): OneShot<List<ModelEntry>>? {
    val page = (reply as? Reply.Event)?.event as? ServerEvent.Models
    page?.let { entries += it.entries }
    val whole = reply == Reply.Ended || (page != null && page.next != true)
    return if (whole) OneShot.Answered(entries.toList()) else ended(reply)
}

/** How a reply ends a one-shot unanswered: timed out, refused, interrupted; none for an answer's part. */
private fun ended(reply: Reply?): OneShot<Nothing>? =
    when (reply) {
        null -> OneShot.TimedOut
        is Reply.Refused -> OneShot.Refused(reply.code)
        Reply.Interrupted -> OneShot.Interrupted
        else -> null
    }

/**
 * One blob as it comes, into [out] on [io]: its `media_begin`, its chunks hashed as they are written, the first
 * one handed to [firstChunk] too, and at its `media_end` its size and digest held to what both said.
 */
private class BlobWrite(
    private val out: OutputStream,
    private val io: CoroutineDispatcher,
    private val firstChunk: (ByteArray) -> Unit,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var begin: ServerEvent.MediaBegin? = null
    private var written = 0L

    /** What [reply] ends the fetch with; none while the blob still comes. */
    suspend fun take(reply: Reply?): OneShot<FetchedMedia>? =
        when (reply) {
            is Reply.Broken -> throw MediaMismatchException(reply.reason)
            Reply.Ended -> throw MediaMismatchException("the blob ended before its media_end")
            is Reply.Event -> event(reply.event, reply.raw)
            else -> ended(reply)
        }

    private suspend fun event(
        event: ServerEvent.Known,
        raw: ByteArray,
    ): OneShot<FetchedMedia>? =
        when (event) {
            is ServerEvent.MediaBegin -> null.also { begin = event }
            is ServerEvent.MediaChunk -> null.also { write(raw) }
            is ServerEvent.MediaEnd -> OneShot.Answered(whole(event))
            else -> error("a blob has no ${event::class.simpleName}")
        }

    private suspend fun write(raw: ByteArray) {
        if (written == 0L && raw.isNotEmpty()) firstChunk(raw.copyOf())
        digest.update(raw)
        written += raw.size
        withContext(io) { out.write(raw) }
    }

    private suspend fun whole(end: ServerEvent.MediaEnd): FetchedMedia {
        val begun = checkNotNull(begin) { "a media_end before its media_begin" }
        val actual = digest.digest().toHex()
        val matches = actual.equals(end.sha256, ignoreCase = true) && actual.equals(begun.sha256, ignoreCase = true)
        if (written != begun.sizeBytes) throw MediaMismatchException("$written bytes, not ${begun.sizeBytes}")
        if (!matches) throw MediaMismatchException("the bytes hash to $actual, not ${end.sha256}")
        withContext(io) { out.flush() }
        return FetchedMedia(begun.kind, begun.mime, begun.sizeBytes, actual, begun.filename)
    }
}

/** The codec's refusal of [event] as past one frame's header, encoded at the largest seq; none when it fits. */
private fun headerPast(event: ClientEvent): ProtocolException.HeaderTooLong? =
    try {
        encodeClientEvent(SESSION_VERSION, ULong.MAX_VALUE, event)
        null
    } catch (past: ProtocolException.HeaderTooLong) {
        past
    }

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }
