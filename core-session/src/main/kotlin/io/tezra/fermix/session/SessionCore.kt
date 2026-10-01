package io.tezra.fermix.session

import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** Events the app has not collected yet; past this the session waits for the app rather than drop one. */
private const val EVENT_BUFFER = 256

/**
 * What one session keeps across its connections: its state and events, its diagnostics and the acks it
 * sent, the timeline's cursors, the turns and approval cards it shows, the candidates it races, and the
 * connection that is up, if one is. Touched only from the session's dispatcher, one coroutine at a time.
 */
internal class SessionCore(
    val instance: PairedInstance,
    val parts: SessionParts,
    var candidates: List<Candidate>,
) {
    private val started = parts.clock.markNow()
    private val loaded = CompletableDeferred<Timeline>()
    private var book = TurnBook()

    val events = Channel<SessionEvent>(EVENT_BUFFER)
    val state = MutableStateFlow<SessionState>(SessionState.Connecting)
    val diagnostics = DiagnosticsLog()
    val acks = DiagnosticsLog()
    val approvals = ShownApprovals()
    var lastSuccessful: Candidate? = null
    var live: Live? = null

    /** The watch on the session's scope, which ends the session with it; let go once the session ends. */
    var scopeWatch: DisposableHandle? = null

    /** Milliseconds since the session opened, on its monotonic clock. */
    fun now(): Long = started.elapsedNow().inWholeMilliseconds

    /** The timeline, its cursors read from the store the first time. */
    suspend fun timeline(): Timeline {
        if (!loaded.isCompleted) {
            loaded.complete(Timeline(parts.store.cursors(), parts.store, parts.announcer, ::now))
        }
        return loaded.await()
    }

    suspend fun emit(event: SessionEvent) {
        events.send(event)
    }

    /** A live state; a session that ended stays ended. */
    fun publish(next: SessionState) {
        if (state.value !is SessionState.Ended) state.value = next
    }

    /** The session ends as [ended], unless it ended already; its events end with it. */
    fun end(ended: SessionState.Ended) {
        publish(ended)
        events.close()
        scopeWatch?.dispose()
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

    /** Moves the turn book by [step] and shows what it says to. */
    suspend fun turns(step: (TurnBook) -> TurnBookStep) {
        val next = step(book)
        book = next.book
        next.effects.forEach { emit(SessionEvent.Turn(it)) }
    }

    fun turnRequests(): List<String> = book.requestIds()
}
