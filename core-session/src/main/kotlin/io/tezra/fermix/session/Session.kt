package io.tezra.fermix.session

import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.MAX_CANDIDATES
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.TimeSource

/** An X25519 public key's bytes: the daemon's `gateway_pk`. */
private const val GATEWAY_KEY_BYTES = 32

/** What the pairing left this phone with for one daemon and one of its profiles (design section 6.6). */
class PairedInstance(
    val deviceId: String,
    val profileId: String,
    gatewayPublicKey: ByteArray,
) {
    private val gatewayKey = gatewayPublicKey.copyOf()

    init {
        require(deviceId.isNotEmpty()) { "a paired instance has a device id" }
        require(profileId.isNotEmpty()) { "a paired instance has a profile" }
        require(
            gatewayKey.size == GATEWAY_KEY_BYTES,
        ) { "gateway_pk is ${gatewayKey.size} bytes, not $GATEWAY_KEY_BYTES" }
    }

    /** The daemon's static key, which the IK handshake authenticates; a copy, so no caller can change it. */
    val gatewayPublicKey: ByteArray get() = gatewayKey.copyOf()
}

/**
 * What a session runs on, all of it the app's: the phone's Keystore key, the dialer, the store and
 * the announcer of the data layer, the network facts core-transport's watcher reads, a monotonic
 * clock and the backoff's jitter. A test passes fakes and the virtual clock.
 */
data class SessionParts(
    val appVersion: String,
    val staticKey: StaticKey,
    val dialer: Dialer,
    val store: SessionStore,
    val announcer: Announcer,
    val network: StateFlow<NetworkFacts>,
    val clock: TimeSource = TimeSource.Monotonic,
    val random: Random = Random.Default,
)

/**
 * One paired session with one daemon, for one profile, from its first race until it ends: hello first,
 * the outbox, the cursors, keepalive, reconnect reconciliation, the ack and read rules, and the turn
 * machines (design sections 5, 7, 8 and 10). It reconnects by itself for as long as the close codes
 * allow, and says how its link stands in [state]. Its coroutines run one at a time on its scope's
 * dispatcher, and every call here joins them there.
 */
class Session private constructor(
    private val core: SessionCore,
    private val requests: Requests,
    private val runner: Runner,
    private val confined: CoroutineDispatcher,
) {
    val state: StateFlow<SessionState> get() = core.state

    /**
     * Everything the session tells the app besides its rows, in order. One collector, for as long as the
     * session runs: past 256 uncollected events the session waits for it rather than drop one.
     */
    val events: Flow<SessionEvent> = core.events.receiveAsFlow()

    /** The last 200 notable things, for the Instance screen's Diagnostics. */
    val diagnostics: StateFlow<List<Diagnostic>> get() = core.diagnostics.entries

    /**
     * The last 200 `ack`s sent, each with its row and how long it waited, for the Diagnostics' check that
     * every ack follows the row's render or notification (onboarding section 8, gotcha 14).
     */
    val acks: StateFlow<List<Diagnostic>> get() = core.acks.entries

    /**
     * Persists [request], a `msg` or a `command` of this session's profile, in the outbox, then sends
     * it if a connection is up and reconciled; otherwise the next one's outbox drain sends it. A request
     * its version's rules refuse throws before anything is stored.
     */
    suspend fun send(request: ClientEvent): Unit = withContext(confined) { requests.submit(request) }

    /**
     * "Run again" (design section 13.5): runs [failed], the `msg` or `command` whose run failed or which
     * the daemon refused, again as a new request, [newClientMsgId], which names it in `retry_of` (design
     * section 7, the `msg.retry_of?` row). The app passes the request as it sent it, since one the daemon
     * accepted left the outbox then; a refused one still in the outbox leaves it now.
     */
    suspend fun retry(
        failed: ClientEvent,
        newClientMsgId: String,
    ): Unit = withContext(confined) { requests.retry(failed, newClientMsgId) }

    /** Asks the daemon to stop [clientMsgId]'s turn: sent once if connected, never queued, never answered. */
    suspend fun cancel(clientMsgId: String): Boolean =
        withContext(confined) {
            require(clientMsgId.isNotEmpty()) { "a cancel names its request" }
            val live = core.live ?: return@withContext false
            live.post(ClientEvent.Cancel(core.instance.profileId, clientMsgId))
            true
        }

    /**
     * Asks for the page of rows before [beforeSeq], whose rows come as [SessionEvent.OlderLoaded], never
     * through the announcer. False when no connection is up or an older page is on its way already.
     */
    suspend fun loadOlder(beforeSeq: ULong): Boolean =
        withContext(confined) {
            require(beforeSeq > 0uL) { "there is no row before row 0" }
            core.live?.pullOlder(beforeSeq) ?: false
        }

    /**
     * The owner has read up to [upToSeq]: the frontier moves forward only, and the daemon hears it if
     * connected. An ended session refuses it, since its store may be another session's by then.
     */
    suspend fun markRead(upToSeq: ULong): Unit =
        withContext(confined) {
            core.requireOpen()
            if (core.timeline().read(upToSeq, fromDaemon = false)) core.live?.report()
        }

    /**
     * "Remove from outbox" (design section 13.6): [clientMsgId]'s request, which the daemon refused, leaves
     * the outbox. One the outbox still sends is never removed, since it may have reached the daemon.
     */
    suspend fun remove(clientMsgId: String): Unit = withContext(confined) { requests.remove(clientMsgId) }

    /** Closes the socket and races no more until [resume] (design section 12.5). */
    suspend fun suspend(): Unit = withContext(confined) { runner.pause() }

    suspend fun resume(): Unit = withContext(confined) { runner.resume() }

    /** Ends the session for good: [SessionState.Closed]. */
    suspend fun close(): Unit = withContext(confined) { runner.close() }

    companion object {
        /**
         * Starts a session in [scope], whose dispatcher it runs on one coroutine at a time, racing
         * [candidates], at most 16, for [instance]'s daemon. It runs until it ends or [scope] does, which
         * ends it as [SessionState.Closed]. The caller opens one session per (instance, profile) at a
         * time: its store is that session's alone (SessionStore).
         */
        fun open(
            instance: PairedInstance,
            candidates: List<Candidate>,
            parts: SessionParts,
            scope: CoroutineScope,
        ): Session = start(instance, candidates, parts, scope, adopted = null)

        /**
         * [open], but the first attempt takes over [adopted], a pairing's connection just approved, and
         * sends `hello` on it at its next seq instead of racing (PROTOCOL.md "Noise modes and pairing",
         * step 3), so the first chat opens without a second handshake. From the second attempt on the
         * session races [candidates] as [open]'s does. Pairing alone calls it.
         */
        internal fun adopt(
            instance: PairedInstance,
            candidates: List<Candidate>,
            parts: SessionParts,
            scope: CoroutineScope,
            adopted: Adopted,
        ): Session = start(instance, candidates, parts, scope, adopted)

        private fun start(
            instance: PairedInstance,
            candidates: List<Candidate>,
            parts: SessionParts,
            scope: CoroutineScope,
            adopted: Adopted?,
        ): Session {
            require(candidates.isNotEmpty()) { "a session needs a candidate to race" }
            require(candidates.size <= MAX_CANDIDATES) { "${candidates.size} candidates is past $MAX_CANDIDATES" }
            require(parts.appVersion.isNotEmpty()) { "hello carries the app's version" }
            val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
            requireNotNull(dispatcher) { "a session's scope names the dispatcher it runs on" }
            val scopeJob = scope.coroutineContext[Job]
            requireNotNull(scopeJob) { "a session's scope has a job, whose end ends the session" }
            val confined = dispatcher.limitedParallelism(1)
            val core = SessionCore(instance, parts, candidates)
            core.adopted = adopted
            // The scope's job completes once every coroutine of the session has, so nothing else runs then.
            core.scopeWatch = scopeJob.invokeOnCompletion { core.end(SessionState.Closed) }
            val requests = Requests(core)
            val runner = Runner(core, requests, scope + confined)
            runner.start()
            return Session(core, requests, runner, confined)
        }
    }
}
