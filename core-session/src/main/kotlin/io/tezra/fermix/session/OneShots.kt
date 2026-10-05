package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.channels.Channel

/** Searches and model pulls one connection has waiting at most; past it a call is [OneShot.Busy]. */
internal const val MAX_ASKED = 8

/** Fetches one connection has waiting at most: the daemon's backlog (PROTOCOL.md "Media downloads"). */
internal const val MAX_FETCHES = 8

/** Pages of one `models` answer the session takes; past them the pull ends with what came, and says so. */
internal const val MAX_MODEL_PAGES = 64

/** Hits a `history_search` asks for: design section 13.7's page of 20. */
internal const val SEARCH_PAGE = 20

/**
 * Replies a one-shot's caller has not read yet, at most: a pull's every page fits, and a fetch's blob is held
 * in memory no further than that many 60 KiB chunks ahead of its file (Waiter.deliver).
 */
internal const val MAX_QUEUED_REPLIES = MAX_MODEL_PAGES + 1

/** What the actor hands a one-shot's caller, in order. */
internal sealed interface Reply {
    /** An answer's event: a search's page, a models page, or a blob's `media_begin`, chunk with its bytes, or end. */
    class Event(
        val event: ServerEvent.Known,
        val raw: ByteArray = ByteArray(0),
    ) : Reply

    /** A frame of a blob before this one's: the daemon's queue moves, and this fetch's wait is not silence. */
    data object Progress : Reply

    class Refused(
        val code: String,
    ) : Reply

    /** The blob's frames broke its own `media_begin`, out of order or past its size: nothing more comes. */
    class Broken(
        val reason: String,
    ) : Reply

    data object Interrupted : Reply

    /** No more replies come: a models answer past its bound, or a broken blob after its reason. Never sent. */
    data object Ended : Reply
}

/** What a one-shot waits for. */
internal enum class Asking { SEARCH, MODELS, MEDIA }

/**
 * One one-shot sent on a connection: the replies its caller reads, until it gives up and cancels them, after
 * which what comes for it is dropped. A search keeps its query, which its answer echoes, and a search or a pull
 * when it was asked, on the session's clock ([askedAtMs]); a fetch keeps how far its blob has come, which the
 * actor checks.
 */
internal class Waiter(
    val asking: Asking,
    val ref: String? = null,
    val query: String? = null,
    val askedAtMs: Long = 0L,
) {
    val replies = Channel<Reply>(MAX_QUEUED_REPLIES)
    var pages = 0
    var begin: ServerEvent.MediaBegin? = null
    var nextIndex = 0
    var bytes = 0L

    /** Why the replies ended before their answer did: its caller fell behind them; none while it keeps up. */
    @Volatile
    var overrun: String? = null

    /** Whether its caller stopped reading, answered or not; set in the caller's coroutine (OneShotCalls). */
    @Volatile
    var gaveUp = false

    /**
     * Hands [reply] on; one its caller gave up on, or whose replies ended, is dropped. A caller [MAX_QUEUED_REPLIES]
     * replies behind would hold a whole blob in memory: its replies end there, and it hears why ([overrun]).
     */
    fun deliver(reply: Reply) {
        val sent = replies.trySend(reply)
        if (!sent.isFailure || sent.isClosed) return
        overrun = "the fetch fell $MAX_QUEUED_REPLIES replies behind its blob"
        replies.close()
    }
}

/**
 * One connection's searches and model pulls (Session.search, Session.pullModels), apart from the outbox: sent
 * once on this connection, never stored and never resent, and each answered here, or not at all once the
 * connection ends ([interrupt]). A `search_results` echoes its query (design section 7, the `history_search`
 * row), so it answers the oldest search waiting for that query; the daemon answers a connection's requests in
 * the order it reads them, so a `models` page answers the oldest pull, until the page that says no `next`. An
 * `error` naming neither a request nor a ref is never taken for either: no code is one only they can get
 * (PROTOCOL.md "Errors"), so the app hears it, and a search or pull the daemon refused so ends as
 * [OneShot.TimedOut]. A caller that gave up keeps its place for the 30 s an answer takes, so an answer on its
 * way is not taken for a later one's; past them it is let go ([retire]), and an answer that still comes for it
 * answers no one waiting. Touched from the session's dispatcher alone.
 */
internal class Asked(
    private val core: SessionCore,
    private val post: (ClientEvent) -> Unit,
) {
    private val waiting = ArrayDeque<Waiter>()

    /** A `history_search` for [query], before [beforeSeq] when given; none while [MAX_ASKED] wait. */
    fun search(
        query: String,
        beforeSeq: ULong?,
    ): Waiter? {
        val request = ClientEvent.HistorySearch(core.instance.profileId, query, SEARCH_PAGE, beforeSeq)
        return ask(Waiter(Asking.SEARCH, query = query, askedAtMs = core.now()), request)
    }

    /** A `models_pull`; none while [MAX_ASKED] wait. */
    fun pullModels(): Waiter? = ask(Waiter(Asking.MODELS, askedAtMs = core.now()), ClientEvent.ModelsPull)

    private fun ask(
        waiter: Waiter,
        request: ClientEvent,
    ): Waiter? {
        retire()
        if (waiting.size >= MAX_ASKED) return null
        waiting.addLast(waiter)
        post(request)
        return waiter
    }

    /**
     * The answer of the oldest search waiting for [event]'s query. A page no search waits for, an answer to one
     * let go ([retire]), is dropped, said in a diagnostic that never quotes its words.
     */
    fun searchResults(event: ServerEvent.SearchResults) {
        retire()
        val waiter = waiting.firstOrNull { it.asking == Asking.SEARCH && it.query == event.query }
        if (waiter == null) {
            core.log(DiagnosticKind.BOUND, "a search_results no search waits for; dropped")
            return
        }
        waiting.remove(waiter)
        waiter.deliver(Reply.Event(event))
    }

    /**
     * A page of the oldest pull's answer, the last one that says no `next`; false when no pull waits. Past
     * [MAX_MODEL_PAGES] the caller has what came, and the rest of that answer is dropped as it comes.
     */
    fun models(event: ServerEvent.Models): Boolean {
        retire()
        val waiter = waiting.firstOrNull { it.asking == Asking.MODELS } ?: return false
        waiter.pages++
        val last = event.next != true
        if (last) waiting.remove(waiter)
        waiter.deliver(Reply.Event(event))
        if (!last && waiter.pages == MAX_MODEL_PAGES) {
            core.log(DiagnosticKind.BOUND, "models past $MAX_MODEL_PAGES pages; the rest is dropped")
            waiter.replies.close()
        }
        return true
    }

    /** The connection ended: every one waiting is told so, and none waits any more. */
    fun interrupt() {
        waiting.forEach { it.deliver(Reply.Interrupted) }
        waiting.clear()
    }

    /**
     * Lets go of each search and pull whose caller gave up [ANSWER_TIMEOUT_MS] or more after it was asked: its
     * caller waits no longer than that for an answer, and one the daemon refused with an `error` naming nothing
     * gets none, so it would take the next answer to its query or the next pull's, and count against
     * [MAX_ASKED], until the connection ended.
     */
    private fun retire() {
        val now = core.now()
        waiting.removeAll { it.gaveUp && now - it.askedAtMs >= ANSWER_TIMEOUT_MS }
    }
}

/**
 * One connection's blob fetches (Session.fetchMedia), apart from the outbox like [Asked]'s. Every media frame
 * goes by its ref: a fetch's blob streams to that fetch from `media_begin` to `media_end` or `error{ref}`, in
 * order and within the size its `media_begin` says. A media reply is pushed as it is written, between a
 * fetch's chunks too (the engine's socket handler, though PROTOCOL.md "Media downloads" says two blobs never
 * mix), so a blob no fetch waits for goes to the app and ends nothing; every media frame not a fetch's own
 * tells the fetches still waiting that the daemon's queue moves. Touched from the session's dispatcher alone.
 */
internal class Fetches(
    private val post: (ClientEvent) -> Unit,
) {
    private val waiting = ArrayDeque<Waiter>()

    /**
     * A `media_fetch` of [ref]; none while [MAX_FETCHES] wait, which the daemon would refuse. It waits only once it
     * went: one the codec refuses holds no place.
     */
    fun fetch(ref: String): Waiter? {
        if (waiting.size >= MAX_FETCHES) return null
        val waiter = Waiter(Asking.MEDIA, ref)
        post(ClientEvent.MediaFetch(ref))
        waiting.addLast(waiter)
        return waiter
    }

    /**
     * A blob's start: the fetch waiting for its ref, which streams from now on; false when none waits for it, a
     * media reply's. A second start of a blob a fetch streams would mix two blobs of one ref, and breaks the
     * connection.
     */
    fun begin(event: ServerEvent.MediaBegin): Boolean {
        if (streamingOf(event.ref) != null) throw SessionProtocolError("a media_begin of a blob that streams")
        val waiter = waiting.firstOrNull { it.ref == event.ref }
        waiter?.begin = event
        waiter?.deliver(Reply.Event(event))
        progress(waiter)
        return waiter != null
    }

    /** A chunk of a blob a fetch streams, in order and within its size; false when it is no fetch's. */
    fun chunk(
        event: ServerEvent.MediaChunk,
        raw: ByteArray,
    ): Boolean {
        val waiter = streamingOf(event.ref)
        progress(waiter)
        if (waiter == null) return false
        val size = checkNotNull(waiter.begin).sizeBytes
        when {
            event.index != waiter.nextIndex -> broken(waiter, "chunk ${event.index} where ${waiter.nextIndex} was due")
            waiter.bytes + raw.size > size -> broken(waiter, "more than the $size bytes its media_begin said")
            else -> waiter.took(event, raw)
        }
        return true
    }

    /** The end of a blob a fetch streams; false when it is no fetch's. */
    fun end(event: ServerEvent.MediaEnd): Boolean {
        val waiter = streamingOf(event.ref)
        progress(waiter)
        if (waiter == null) return false
        waiting.remove(waiter)
        waiter.deliver(Reply.Event(event))
        return true
    }

    /** `error{ref}`: the fetch of that ref refused, streaming or waiting; false when none waits for it. */
    fun refused(error: ServerEvent.Error): Boolean {
        val waiter = streamingOf(error.ref) ?: waiting.firstOrNull { it.ref == error.ref }
        waiter?.let(waiting::remove)
        waiter?.deliver(Reply.Refused(error.code))
        return waiter != null
    }

    /** The connection ended: every fetch waiting is told so, and none waits any more. */
    fun interrupt() {
        waiting.forEach { it.deliver(Reply.Interrupted) }
        waiting.clear()
    }

    /** The fetch whose blob [ref] streams, none when no fetch's does. */
    private fun streamingOf(ref: String?): Waiter? = waiting.firstOrNull { it.ref == ref && it.begin != null }

    /** Every fetch waiting for its blob but [moving] hears the queue move. */
    private fun progress(moving: Waiter?) {
        waiting.filter { it !== moving && it.begin == null }.forEach { it.deliver(Reply.Progress) }
    }

    /** [waiter]'s blob broke: its caller hears why, and the rest of it is dropped until it ends. */
    private fun broken(
        waiter: Waiter,
        reason: String,
    ) {
        waiter.deliver(Reply.Broken(reason))
        waiter.replies.close()
    }

    private fun Waiter.took(
        event: ServerEvent.MediaChunk,
        raw: ByteArray,
    ) {
        nextIndex++
        bytes += raw.size
        deliver(Reply.Event(event, raw))
    }
}
