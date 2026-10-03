package io.tezra.fermix

import io.tezra.fermix.data.Instance
import io.tezra.fermix.onboarding.SessionHandover
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.session.TurnEffect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Out of sight this long, every session is put aside (design section 12.5); back in sight within it, nothing is. */
const val BACKGROUND_GRACE_MILLIS = 5_000L

/** How long "Unpair" waits for the daemon's `4003` after `unpair` before the instance goes anyway. */
const val UNPAIR_WAIT_MILLIS = 5_000L

/** An instance whose session cannot be opened now: its key is missing from the Keystore, or it has no route. */
class SessionUnavailable(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Opens [instance]'s session in [scope]: the app's over the network, the tests' a fake that never connects. */
fun interface SessionOpener {
    /** Throws [SessionUnavailable] when the instance cannot have a session now. */
    fun open(
        instance: Instance,
        scope: CoroutineScope,
    ): Session
}

/** Where a session's events go that the app keeps: the record's, the chat's and the notified set's (AppServices). */
fun interface EventSink {
    suspend fun take(
        instanceId: String,
        event: SessionEvent,
    )
}

/**
 * The one keeper of sessions (design section 12.5): one Session per paired instance, keyed by its id, opened
 * from the records while the app is in sight, its events taken by one collector each. Out of sight past
 * [BACKGROUND_GRACE_MILLIS] every session is suspended, by one timer that a return to sight cancels; back in
 * sight, each resumes, and one that ended reconnects, unless the owner has to decide (revoked, identity
 * changed). An approved pairing's session is taken over as its record is stored ([adopt]), so no second
 * socket opens. Its sessions run in [scope], whose end closes every one, as the process's end does. Unpairing
 * asks the daemon to forget the phone through [forget], which says whether `unpair` went out (AppServices's
 * sends it over the session's live connection, if one is up).
 */
class SessionSupervisor(
    private val records: Flow<List<Instance>>,
    private val opener: SessionOpener,
    private val sink: EventSink,
    private val scope: CoroutineScope,
    private val log: (String, Throwable?) -> Unit,
    private val forget: suspend (Session) -> Boolean,
) : SessionHandover {
    private val lock = Mutex()
    private var started = false
    private val held = MutableStateFlow<Map<String, Session>>(emptyMap())
    private val collectors = mutableMapOf<String, Job>()

    /** Each instance's turns running now, by turn id: a cron or background turn runs beside the phone's. */
    private val liveTurns = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Whether the app is in sight; the process's lifecycle sets it. */
    val inSight = MutableStateFlow(false)

    /** Whether sessions may run: in sight, or out of it for less than the grace. */
    private var active = false

    val sessions: StateFlow<Map<String, Session>> = held.asStateFlow()

    /** The instances with any turn running, from their sessions' turn effects: the rows' "thinking…". */
    val thinking: StateFlow<Set<String>> =
        liveTurns.map { it.keys }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Follows the records and the app's sight until [scope] ends, on [io], since opening a session reads the
     * Keystore and opens its database, while each session runs on [scope]'s own dispatcher. Start it once,
     * after the launch check.
     */
    fun start(io: CoroutineDispatcher) {
        check(!started) { "the supervisor is started once" }
        started = true
        scope.launch(io) { records.collect { lock.withLock { reconcile() } } }
        scope.launch(io) {
            inSight.collectLatest { visible ->
                if (!visible) delay(BACKGROUND_GRACE_MILLIS)
                lock.withLock { if (visible) cameIntoSight() else putAside() }
            }
        }
    }

    override suspend fun adopt(
        instanceId: String,
        session: Session,
        record: suspend () -> String?,
    ): String? {
        require(INSTANCE_ID.matches(instanceId)) { "$instanceId is not an instance id" }
        return lock.withLock {
            // "Pair again" merges only into a row in a trust state, and record() deletes that row's files: its
            // session's run ended with that state, and an ended session takes no request, so nothing touches
            // them, and the next reconcile drops it.
            val replaced = record()
            drop(instanceId)
            keep(instanceId, session)
            if (!active) session.suspend()
            replaced
        }
    }

    /**
     * Removes [instanceId] from this phone through [removal], its session closed first; with [unpair], the
     * daemon is asked to forget the phone ([forget]) and given [UNPAIR_WAIT_MILLIS] to close, when `unpair`
     * went out.
     */
    suspend fun remove(
        instanceId: String,
        unpair: Boolean,
        removal: suspend (String) -> Unit,
    ) {
        require(INSTANCE_ID.matches(instanceId)) { "$instanceId is not an instance id" }
        lock.withLock {
            val session = held.value[instanceId]
            if (unpair && session != null && forget(session)) {
                withTimeoutOrNull(UNPAIR_WAIT_MILLIS) { session.state.first { it is SessionState.Ended } }
            }
            drop(instanceId)
            removal(instanceId)
        }
    }

    /** Opens what the records hold and no session serves, while active, and closes what they no longer hold. */
    private suspend fun reconcile() {
        val current = records.first()
        val ids = current.map { it.id }.toSet()
        for (gone in held.value.keys - ids) drop(gone)
        if (!active) return
        current.filter { it.id !in held.value }.forEach { open(it) }
    }

    /** In sight again: everything resumes, and a session that ended and needs no decision reconnects. */
    private suspend fun cameIntoSight() {
        active = true
        val current = records.first()
        for (record in current) {
            val session = held.value[record.id]
            when {
                session == null -> open(record)
                session.state.value.waitsForOwner() -> Unit
                session.state.value is SessionState.Ended -> reopen(record)
                else -> session.resume()
            }
        }
    }

    /** Out of sight past the grace: every running session is put aside. */
    private suspend fun putAside() {
        active = false
        held.value.values
            .filter { it.state.value !is SessionState.Ended }
            .forEach { it.suspend() }
    }

    private suspend fun reopen(record: Instance) {
        drop(record.id)
        open(record)
    }

    private fun open(record: Instance) {
        val session =
            try {
                opener.open(record, scope)
            } catch (unavailable: SessionUnavailable) {
                log("no session for ${record.id}", unavailable)
                return
            }
        keep(record.id, session)
    }

    private fun keep(
        instanceId: String,
        session: Session,
    ) {
        check(instanceId !in held.value) { "$instanceId has a session already" }
        held.update { it + (instanceId to session) }
        collectors[instanceId] =
            scope.launch {
                session.events.collect { event -> take(instanceId, event) }
                // The session ended, and no turn of its runs on; a session put in its place keeps its own.
                if (held.value[instanceId] === session) liveTurns.update { it - instanceId }
            }
    }

    /** Closes [instanceId]'s session, if any, and forgets it with its collector and its turn. */
    private suspend fun drop(instanceId: String) {
        val session = held.value[instanceId] ?: return
        held.update { it - instanceId }
        liveTurns.update { it - instanceId }
        collectors.remove(instanceId)?.cancel()
        session.close()
    }

    private suspend fun take(
        instanceId: String,
        event: SessionEvent,
    ) {
        if (event is SessionEvent.Turn) {
            liveTurns.update { all ->
                val running = turnsAfter(all[instanceId].orEmpty(), event.effect)
                if (running.isEmpty()) all - instanceId else all + (instanceId to running)
            }
        }
        sink.take(instanceId, event)
    }
}

/** The turns running after [effect]: its turn runs until it ends, whatever the others do. */
internal fun turnsAfter(
    running: Set<String>,
    effect: TurnEffect,
): Set<String> = if (effect is TurnEffect.TurnEnded) running - effect.turnId else running + effect.turnId

/** A trust state: the owner pairs again or removes the instance (design section 9.4); nothing reconnects it. */
private fun SessionState.waitsForOwner(): Boolean = this == SessionState.Revoked || this == SessionState.IdentityChanged

/** Sends `unpair` over [session]; false when no connection is up, or the session ended just now, which [log] notes. */
internal suspend fun askToForget(
    session: Session,
    log: (String, Throwable?) -> Unit,
): Boolean {
    if (session.state.value is SessionState.Ended) return false
    return try {
        session.unpair()
    } catch (ended: IllegalStateException) {
        log("the session ended before unpair went out", ended)
        false
    }
}
