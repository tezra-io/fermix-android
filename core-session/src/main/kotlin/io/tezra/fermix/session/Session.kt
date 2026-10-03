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

/** The daemon's command that stops the reply (design section 8.1, "Stop generation"). */
private const val STOP_COMMAND = "stop"

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

    /**
     * The candidate the last completed `hello` went over, none before the first: the live one while [state]
     * is [SessionState.Connected] (the Instance screen's candidates), and the first one the next race tries.
     */
    val lastSuccessful: StateFlow<Candidate?> get() = core.lastSuccessful

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
    suspend fun send(request: ClientEvent): Unit = withContext(confined) { runner.request { requests.submit(request) } }

    /**
     * "Run again" (design section 13.5): runs [failed], the `msg` or `command` whose run failed or which
     * the daemon refused, again as a new request, [newClientMsgId], which names it in `retry_of` (design
     * section 7, the `msg.retry_of?` row). The app passes the request as it sent it, since one the daemon
     * accepted left the outbox then; a refused one still in the outbox leaves it now.
     */
    suspend fun retry(
        failed: ClientEvent,
        newClientMsgId: String,
    ): Unit = withContext(confined) { runner.request { requests.retry(failed, newClientMsgId) } }

    /**
     * Stop on the composer (design section 8.1, the "Stop generation" row: `command{name:"stop"}`) as
     * [clientMsgId]: sent once if connected and never queued, since a stop that waited in the outbox for a
     * later connection would stop whatever runs then; false when no connection is up. An ended session refuses
     * it.
     */
    suspend fun stop(clientMsgId: String): Boolean =
        withContext(confined) {
            core.requireOpen()
            require(clientMsgId.isNotEmpty()) { "a stop names itself" }
            val live = core.live ?: return@withContext false
            core.commands.written(clientMsgId)
            live.post(ClientEvent.Command(clientMsgId, core.instance.profileId, STOP_COMMAND, null))
            true
        }

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
            runner.request {
                core.requireOpen()
                if (core.timeline().read(upToSeq, fromDaemon = false)) core.live?.report()
            }
        }

    /**
     * "Remove from outbox", and a queued message's Edit or Remove (design section 13.6): [clientMsgId]'s
     * request leaves the outbox if the daemon refused it or its frame was never written to a socket
     * ([OutboxItem.written]); whether it left. One written and not refused stays, since it may have reached
     * the daemon, and so does an id the outbox does not hold.
     */
    suspend fun remove(clientMsgId: String): Boolean =
        withContext(confined) { runner.request { requests.remove(clientMsgId) } }

    /**
     * "Unpair from {host}…" (design section 13.7): asks the daemon to forget this phone with `unpair`, sent
     * once if a connection is up and never queued for a later one. The daemon answers with close `4003`,
     * which ends the session as [SessionState.Revoked]. False when no connection is up, and then the daemon
     * keeps listing the phone until the owner removes it there. An ended session refuses it.
     */
    suspend fun unpair(): Boolean =
        withContext(confined) {
            core.requireOpen()
            val live = core.live ?: return@withContext false
            live.post(ClientEvent.Unpair)
            true
        }

    /** Closes the socket and races no more until [resume] (design section 12.5). */
    suspend fun suspend(): Unit = withContext(confined) { runner.pause() }

    suspend fun resume(): Unit = withContext(confined) { runner.resume() }

    /**
     * Ends the session for good: [SessionState.Closed]. It returns once the run has stopped and each of [send],
     * [retry], [markRead] and [remove] called before it has returned, so nothing of this session's touches its
     * store after it.
     */
    suspend fun close(): Unit = withContext(confined) { runner.close() }

    companion object {
        /**
         * Starts a session in [scope], whose dispatcher it runs on one coroutine at a time, racing
         * [candidates], at most 16, for [instance]'s daemon, [lastSuccessful] first when [candidates] holds
         * it: the candidate an earlier session's last `hello` went over, which the app keeps with the
         * instance (design section 5.1, "last successful first"). It runs until it ends or [scope] does,
         * which ends it as [SessionState.Closed]. The caller opens one session per (instance, profile) at a
         * time: its store is that session's alone (SessionStore).
         */
        fun open(
            instance: PairedInstance,
            candidates: List<Candidate>,
            parts: SessionParts,
            scope: CoroutineScope,
            lastSuccessful: Candidate? = null,
        ): Session =
            start(instance, candidates, parts, scope) { core ->
                core.lastSuccessful.value = lastSuccessful?.takeIf { it in candidates }
            }

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
        ): Session = start(instance, candidates, parts, scope) { core -> core.adopted = adopted }

        /** A session as [open] and [adopt] start it, its first race set up by [first] before it runs. */
        private fun start(
            instance: PairedInstance,
            candidates: List<Candidate>,
            parts: SessionParts,
            scope: CoroutineScope,
            first: (SessionCore) -> Unit,
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
            first(core)
            // The scope's job completes once every coroutine of the session has, so nothing else runs then.
            core.scopeWatch = scopeJob.invokeOnCompletion { core.end(SessionState.Closed) }
            val requests = Requests(core)
            val runner = Runner(core, requests, scope + confined)
            runner.start()
            return Session(core, requests, runner, confined)
        }
    }
}
