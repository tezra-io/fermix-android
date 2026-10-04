package io.tezra.fermix.session

import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.selects.select

/** Events the app has not collected yet; past this the session waits for the app rather than drop one. */
internal const val EVENT_BUFFER = 256

/**
 * What one session keeps across its connections: its state and events, its diagnostics and the acks it
 * sent, the timeline's cursors, the turns and approval cards it shows, the commands it wrote, the candidates
 * it races, and the connection that is up, if one is. Touched only from the session's dispatcher, one
 * coroutine at a time.
 */
internal class SessionCore(
    val instance: PairedInstance,
    val parts: SessionParts,
    var candidates: List<Candidate>,
) {
    private val started = parts.clock.markNow()
    private val loaded = CompletableDeferred<Timeline>()
    private var book = TurnBook()

    /** Completed as the session ends, which lets an [emit] waiting on the app go. */
    private val ending = CompletableDeferred<Unit>()

    val events = Channel<SessionEvent>(EVENT_BUFFER)
    val state = MutableStateFlow<SessionState>(SessionState.Connecting)
    val diagnostics = DiagnosticsLog()
    val acks = DiagnosticsLog()
    val approvals = ShownApprovals()
    val commands = WrittenCommands()
    val lastSuccessful = MutableStateFlow<Candidate?>(null)
    var live: Live? = null

    /** Each attachment's upload by its `attach_id`, across connections, until its item leaves the outbox (Uploads). */
    val uploads = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())

    /** Whether the connection that is up has an item's upload under way or queued (Uploads). */
    val uploading = MutableStateFlow(false)

    /** The watch on the session's scope, which ends the session with it; let go once the session ends. */
    var scopeWatch: DisposableHandle? = null

    /** A pairing's connection the first attempt takes over (Session.adopt); closed if the session ends first. */
    var adopted: Adopted? = null

    fun takeAdopted(): Adopted? = adopted.also { adopted = null }

    /** Milliseconds since the session opened, on its monotonic clock. */
    fun now(): Long = started.elapsedNow().inWholeMilliseconds

    /** The timeline, its cursors read from the store the first time, its read frontier said to the app. */
    suspend fun timeline(): Timeline {
        if (!loaded.isCompleted) {
            val timeline =
                Timeline(parts.store.cursors(), parts.store, parts.announcer, ::now) {
                    emit(SessionEvent.ReadFrontier(it))
                }
            loaded.complete(timeline)
        }
        return loaded.await()
    }

    /**
     * Says [event] to the app, waiting while [EVENT_BUFFER] events wait for it; once the session has ended,
     * nothing more is said, and an emit that was waiting lets go. A request runs in its caller's coroutine
     * (markRead's read frontier, an approval's answer), which no stop of the run reaches, and the supervisor
     * stops collecting before it closes a session (SessionSupervisor.drop): so a close never waits on an emit no
     * collector will take (Runner.close).
     */
    suspend fun emit(event: SessionEvent) {
        select {
            ending.onAwait {}
            events.onSend(event) {}
        }
    }

    /** A live state; a session that ended stays ended. */
    fun publish(next: SessionState) {
        if (state.value !is SessionState.Ended) state.value = next
    }

    /** The session ends as [ended], unless it ended already; its events end with it, and an adopted link not taken. */
    fun end(ended: SessionState.Ended) {
        publish(ended)
        ending.complete(Unit)
        events.close()
        scopeWatch?.dispose()
        takeAdopted()?.won?.close()
    }

    fun requireOpen() {
        check(state.value !is SessionState.Ended) { "the session has ended: ${state.value}" }
    }

    fun log(
        kind: DiagnosticKind,
        detail: String,
    ) {
        diagnostics.add(Diagnostic(now(), kind, detail))
    }

    /**
     * Moves the turn book by [step] and shows what it says to. Once the last turn ends, the messages made
     * while it ran go (design section 8.2, `turn_done` → "queue drains").
     */
    suspend fun turns(step: (TurnBook) -> TurnBookStep) {
        val wasLive = book.anyLive
        val next = step(book)
        book = next.book
        next.effects.forEach { emit(SessionEvent.Turn(it, book.stateOf(it.turnId).daemonSpeaking)) }
        // Every outbox item is offered again, in order, to the connection that is up: those that waited go.
        val connection = live
        if (wasLive && !book.anyLive && connection != null) {
            connection.offer(parts.store.outbox())
        }
    }

    /** Whether a turn shows, so a `msg` made now waits for it (Live.offer). */
    val turnsLive: Boolean get() = book.anyLive

    fun turnRequests(): List<String> = book.requestIds()

    /**
     * Whether a `text_done` on [turnId] is a command's answer the daemon wrote inline: the turn answers a command
     * this session wrote, and nothing of it shows yet, as no `turn_started`, thought or tool came for it.
     */
    fun answersCommandInline(turnId: String): Boolean =
        book.stateOf(turnId) == TurnState.Idle && commands.ownsTurn(turnId)
}
