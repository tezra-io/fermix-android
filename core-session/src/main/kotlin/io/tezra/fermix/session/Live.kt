package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import kotlinx.coroutines.channels.SendChannel

/**
 * Forward and newest pulls one connection makes; each moves the cursor on (Dispatch), so a catch-up of
 * 2,000,000 rows fits. Past it the connection pulls no more, and the next one's `hello_ack` pulls on.
 */
internal const val MAX_FORWARD_PULLS = 10_000

/** An ack this long after the announcement that made it due is noted in the diagnostics too. */
internal const val ACK_NOTABLE_MS = 500L

/** Which way a `history_pull` reads. */
internal enum class PullKind { FORWARD, NEWEST, OLDER }

/**
 * One `history_pull` on its way: a forward one holds rows after [afterSeq], a backward one rows before
 * [beforeSeq] (PROTOCOL.md "History pages"; design section 7, `history_pull.before_seq?`).
 */
internal class Pull(
    val kind: PullKind,
    val afterSeq: ULong? = null,
    val beforeSeq: ULong? = null,
) {
    fun holds(seq: ULong): Boolean = (afterSeq == null || seq > afterSeq) && (beforeSeq == null || seq < beforeSeq)
}

/** Where a connection's outbox drain stands; an item offered while the drain reads the store waits for it. */
private enum class Drain { WAITING, READING, DONE }

/**
 * One connection after its `hello_ack`, as the session sends on it: every frame out, the history pulls
 * it waits on, which outbox items it has sent, and how its link is shown, [connected]. A request is sent
 * at most once per connection, its `accepted` notwithstanding, and only once the reconnect reconciliation
 * has drained the outbox (design section 8.2); the ids it sent last as long as the connection, which the
 * daemon closes within the hour, one id per request the owner made in it. A `msg` made while a turn shows
 * waits in the outbox, unwritten, until the last turn ends: design section 13.6's "queued ·
 * sends after this reply", released by `turn_done` (SessionCore.turns). A `command` never waits, since
 * `/stop` is one.
 */
internal class Live(
    private val core: SessionCore,
    val channel: SecureChannel,
    val keepalive: Keepalive,
    private var connected: SessionState.Connected,
) {
    private val pulls = ArrayDeque<Pull>()
    private val sent = HashSet<String>()
    private val waiting = mutableListOf<OutboxItem>()
    private var drain = Drain.WAITING
    private var historyOpen = false
    private var forwardPulls = 0

    private val profile: String get() = core.instance.profileId

    fun post(event: ClientEvent) {
        channel.send(event)
        keepalive.sent(core.now())
    }

    /** A `pong`: its round trip is the subtitle's latency. */
    fun pong() {
        val latencyMs = keepalive.pong(core.now()) ?: return
        connected = connected.copy(latencyMs = latencyMs)
        core.publish(connected)
    }

    /**
     * Hands [input] to the actor, which takes it once it is done with the one before. Until it does the
     * reader reads nothing, pongs included, so the keepalive counts none of that time against the link.
     */
    suspend fun handOff(
        inputs: SendChannel<Input>,
        input: Input,
    ) {
        keepalive.stall(core.now())
        inputs.send(input)
        keepalive.unstall(core.now())
    }

    /**
     * A request to send: once the drain is done it goes, unless it failed, went on this connection
     * already, or is a `msg` while a turn shows; while the drain reads the store it waits for it; before,
     * the drain's read will hold it. Its id is taken before it is marked written, so a second offer of it
     * meanwhile sends nothing, and one removed meanwhile is not sent.
     */
    suspend fun offer(item: OutboxItem) {
        if (drain == Drain.READING) waiting += item
        val waitsForTurn = item.request is ClientEvent.Msg && core.turnsLive
        val goes = drain == Drain.DONE && item.failure == null && item.clientMsgId !in sent && !waitsForTurn
        if (!goes) return
        sent += item.clientMsgId
        if (!core.parts.store.markWritten(item.clientMsgId)) return
        if (item.request is ClientEvent.Command) core.commands.written(item.clientMsgId)
        post(item.request)
    }

    /**
     * The reconciliation's last step: every item [read] returns, in order, then those offered while it
     * read, which it may or may not hold; each goes once.
     */
    suspend fun drain(read: suspend () -> List<OutboxItem>) {
        drain = Drain.READING
        val items = read()
        drain = Drain.DONE
        val offered = items + waiting
        waiting.clear()
        for (item in offered) offer(item)
    }

    /**
     * Sends the `ack` and the `read_state` the timeline owes, each once. Every ack is kept with the acks,
     * and one that waited long is noted in the diagnostics too.
     */
    suspend fun report() {
        val due = core.timeline().due()
        due.ack?.let { ack ->
            post(ClientEvent.Ack(ack))
            val entry = Diagnostic(core.now(), DiagnosticKind.ACK, "ack $ack, ${due.ackHeldMs} ms after its row")
            core.acks.add(entry)
            if (due.ackHeldMs >= ACK_NOTABLE_MS) core.diagnostics.add(entry)
        }
        due.read?.let { post(ClientEvent.ReadState(profile, it)) }
    }

    /**
     * Pulls the rows after the cursor, unless a forward or newest pull is on its way, whose answer pulls
     * on, or the reconciliation has not reached the history yet, which pulls then.
     */
    suspend fun pullForward() {
        if (!historyOpen || pulls.any { it.kind != PullKind.OLDER }) return
        if (forwardPulls >= MAX_FORWARD_PULLS) return core.log(DiagnosticKind.BOUND, "$MAX_FORWARD_PULLS forward pulls")
        val after = core.timeline().cursor
        forwardPulls++
        pulls.addLast(Pull(PullKind.FORWARD, afterSeq = after))
        post(ClientEvent.HistoryPull(profile, afterSeq = after, limit = FORWARD_PAGE_LIMIT))
    }

    /**
     * The reconciliation's first pull: the rows after the cursor, or for an empty cache the newest page,
     * backward from past the head. Protocol v2's backward cursor (design section 7, the
     * `history_pull.before_seq?` row) replaces protocol v1's pull of the whole history from row 0.
     */
    suspend fun openHistory() {
        historyOpen = true
        val timeline = core.timeline()
        if (timeline.cursor == 0uL && timeline.head > 0uL) {
            val before = timeline.head + 1uL
            forwardPulls++
            pulls.addLast(Pull(PullKind.NEWEST, beforeSeq = before))
            post(ClientEvent.HistoryPull(profile, beforeSeq = before, limit = BACKWARD_PAGE_LIMIT))
        } else if (timeline.cursor < timeline.head) {
            pullForward()
        }
    }

    /** One older page at a time, by protocol v2's `before_seq` (design section 7, `history_pull.before_seq?`). */
    fun pullOlder(beforeSeq: ULong): Boolean {
        val free = pulls.none { it.kind == PullKind.OLDER }
        if (free) {
            pulls.addLast(Pull(PullKind.OLDER, beforeSeq = beforeSeq))
            post(ClientEvent.HistoryPull(profile, beforeSeq = beforeSeq, limit = BACKWARD_PAGE_LIMIT))
        }
        return free
    }

    /** The pull a `history_page` answers. */
    fun answered(): Pull = pulls.removeFirstOrNull() ?: throw SessionProtocolError("a history_page no pull asked for")

    /**
     * Once the reconciliation is done and no forward or newest pull is on its way, this connection has
     * caught up, and the subtitle's `Updating…` ends (design section 13.5, SessionState.Connected).
     */
    fun checkCaughtUp() {
        if (connected.caughtUp || drain != Drain.DONE || pulls.any { it.kind != PullKind.OLDER }) return
        connected = connected.copy(caughtUp = true)
        core.publish(connected)
    }
}
