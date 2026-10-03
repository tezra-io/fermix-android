package io.tezra.fermix.session

import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.random.Random

internal val ONLINE = NetworkFacts(1L, defaultHasVpn = true, defaultHasCgnatAddress = true, otherUidVpnPresent = false)
internal val TAILNET = Candidate("100.101.102.103", Candidate.Scope.TAILNET, Candidate.Kind.IP)
internal val LAN = Candidate("192.168.1.20", Candidate.Scope.LAN, Candidate.Kind.IP)

internal val EMPTY_CURSORS =
    StoredCursors(
        lastServerSeq = 0uL,
        readUpToSeq = 0uL,
        lastMutationSeq = 0uL,
        announcedUpToSeq = 0uL,
        lastUnannouncedSeq = 0uL,
    )

/**
 * The session store in memory: what the data layer's Room implementation keeps on disk, and as strict:
 * marking an item failed that the outbox does not hold fails. A test may hold the outbox read after its
 * snapshot, or an enqueue after its write, as a Room read or write that suspends.
 */
internal class MemoryStore(
    var cursors: StoredCursors = EMPTY_CURSORS,
) : SessionStore {
    val items = mutableListOf<OutboxItem>()
    val mutations = mutableListOf<MutationRow>()
    val rebuilds = mutableListOf<ULong>()

    /** The next outbox read takes its snapshot, then waits for this; it is used once. */
    var outboxGate: CompletableDeferred<Unit>? = null

    /** The next enqueue writes, then waits for this; it is used once. */
    var enqueueGate: CompletableDeferred<Unit>? = null

    /** The next mark of an item written waits for this, then writes; it is used once. */
    var writtenGate: CompletableDeferred<Unit>? = null

    /** How long each cursor write takes. */
    var cursorWriteMs = 0L

    /** What every cursor write throws, when set. */
    var cursorFault: Exception? = null

    override suspend fun cursors(): StoredCursors = cursors

    override suspend fun setServerCursor(
        seq: ULong,
        announcedUpToSeq: ULong,
        lastUnannouncedSeq: ULong,
    ) {
        cursorFault?.let { throw it }
        delay(cursorWriteMs)
        cursors =
            cursors.copy(
                lastServerSeq = seq,
                announcedUpToSeq = announcedUpToSeq,
                lastUnannouncedSeq = lastUnannouncedSeq,
            )
    }

    override suspend fun setReadFrontier(seq: ULong) {
        cursors = cursors.copy(readUpToSeq = seq)
    }

    override suspend fun applyMutations(
        rows: List<MutationRow>,
        lastMutationSeq: ULong,
    ) {
        mutations += rows
        cursors = cursors.copy(lastMutationSeq = lastMutationSeq)
    }

    override suspend fun rebuildCache(mutationHeadSeq: ULong) {
        rebuilds += mutationHeadSeq
        cursors = cursors.copy(lastServerSeq = 0uL, lastMutationSeq = mutationHeadSeq)
    }

    override suspend fun outbox(): List<OutboxItem> {
        val snapshot = items.toList()
        outboxGate?.also { outboxGate = null }?.await()
        return snapshot
    }

    override suspend fun enqueue(item: OutboxItem) {
        items += item
        enqueueGate?.also { enqueueGate = null }?.await()
    }

    override suspend fun dequeue(clientMsgId: String) {
        items.removeAll { it.clientMsgId == clientMsgId }
    }

    override suspend fun markWritten(clientMsgId: String): Boolean {
        writtenGate?.also { writtenGate = null }?.await()
        val held = items.any { it.clientMsgId == clientMsgId }
        items.replaceAll { if (it.clientMsgId == clientMsgId) it.copy(written = true) else it }
        return held
    }

    override suspend fun withdraw(clientMsgId: String): Boolean =
        items.removeAll { it.clientMsgId == clientMsgId && (it.failure != null || !it.written) }

    override suspend fun markFailed(
        clientMsgId: String,
        failure: RequestFailure,
    ) {
        require(items.any { it.clientMsgId == clientMsgId }) { "$clientMsgId is not in the outbox" }
        items.replaceAll { if (it.clientMsgId == clientMsgId) it.copy(failure = failure) else it }
    }
}

/**
 * The app's announcer: it records each row as persisted, then answers as [answer] says, [delayMs]
 * later, and records when it answered. With [hold] set it persists and then waits, as an app the OS
 * froze before it announced the row; [during] runs inside the call, as an app calling the session back.
 */
internal class RecordingAnnouncer : Announcer {
    val rows = mutableListOf<TimelineRow>()
    val announcedAt = mutableMapOf<ULong, Long>()
    var answer: (TimelineRow) -> Announcement = { Announcement.NOTIFIED }
    var hold: CompletableDeferred<Unit>? = null
    var delayMs = 0L
    var during: suspend (TimelineRow) -> Unit = {}
    var clock: () -> Long = { 0L }

    override suspend fun announce(row: TimelineRow): Announcement {
        rows += row
        hold?.await()
        delay(delayMs)
        during(row)
        announcedAt[row.serverSeq] = clock()
        return answer(row)
    }
}

/** One session against the fake daemon, on the test's virtual clock. */
internal class Harness(
    private val test: TestScope,
) {
    val daemon = FakeDaemon()
    val store = MemoryStore()
    val announcer = RecordingAnnouncer()
    val network = MutableStateFlow(ONLINE)
    val events = mutableListOf<SessionEvent>()
    val states = mutableListOf<SessionState>()
    lateinit var session: Session
    lateinit var eventsCollected: Job
    private val started = test.testScheduler.timeSource.markNow()

    /** Virtual milliseconds since the harness was made. */
    val now: Long get() = started.elapsedNow().inWholeMilliseconds

    init {
        announcer.clock = { now }
    }

    /**
     * Opens the session in [scope], the test's background scope unless a test ends its own, with
     * [lastSuccessful] as the candidate an earlier session last reached.
     */
    fun open(
        candidates: List<Candidate> = listOf(TAILNET),
        scope: CoroutineScope = test.backgroundScope,
        lastSuccessful: Candidate? = null,
    ): Session {
        val parts =
            SessionParts(
                appVersion = "0.1.0",
                staticKey = SoftwareKey.generate(),
                dialer = daemon,
                store = store,
                announcer = announcer,
                network = network,
                clock = test.testScheduler.timeSource,
                random = Random(SEED),
            )
        val instance = PairedInstance("device-1", PROFILE, daemon.gatewayKey.publicKey)
        session = Session.open(instance, candidates, parts, scope, lastSuccessful)
        eventsCollected = test.backgroundScope.launch { session.events.collect { events += it } }
        test.backgroundScope.launch { session.state.collect { states += it } }
        return session
    }

    /**
     * Opens the session and completes its first connection with [ack], then lets the reconciliation run
     * as far as it goes without the daemon.
     */
    suspend fun connect(ack: ServerEvent.HelloAck = HELLO_ACK): DaemonConnection {
        open()
        val connection = daemon.accept()
        connection.connect(ack)
        settle()
        return connection
    }

    fun turnEffects(): List<TurnEffect> = events.filterIsInstance<SessionEvent.Turn>().map { it.effect }

    fun diagnostics(): List<Diagnostic> = session.diagnostics.value

    /** Lets every coroutine that can run, the session's included, run, without moving the clock. */
    suspend fun settle() {
        repeat(SETTLE_YIELDS) { yield() }
    }

    /** The session's state once [done] holds for it; a virtual minute without it fails the test. */
    suspend fun stateWhen(done: (SessionState) -> Boolean): SessionState =
        withTimeout(WAIT_MS) { session.state.first(done) }

    private companion object {
        const val SEED = 51L
        const val SETTLE_YIELDS = 200
        const val WAIT_MS = 60_000L
    }
}
