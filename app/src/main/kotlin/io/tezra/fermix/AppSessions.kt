package io.tezra.fermix

import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceGone
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabase
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.RoomSessionStore
import io.tezra.fermix.data.stagedUploads
import io.tezra.fermix.instance.TestOutcome
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.session.Announcer
import io.tezra.fermix.session.Dialer
import io.tezra.fermix.session.Link
import io.tezra.fermix.session.PairedInstance
import io.tezra.fermix.session.PairingParts
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionParts
import io.tezra.fermix.session.WebSocketDialer
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.CandidateRacer
import io.tezra.fermix.transport.NetworkFacts
import io.tezra.fermix.transport.PinnedTrust
import io.tezra.fermix.transport.RaceResult
import io.tezra.fermix.transport.WebSocketConnector
import io.tezra.fermix.transport.candidateOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

/** A close with nothing wrong (RFC 6455, 7.4.1): the connection test's, of the link it won. */
private const val NORMAL_CLOSURE = 1000

/** "Test connection" gives up after this long: past every candidate's connect timeout, as one race is. */
internal const val TEST_TIMEOUT_MILLIS = 20_000L

/**
 * How the app makes what talks to a daemon: each instance's session, over the link [dialerFor] dials for its
 * port and pin (its pinned WebSocket, but in the debug app's demo) with its Keystore key, its main profile's
 * store and the [announcer] made for it over the same database, racing the candidate the record kept from the
 * last `hello` first; a pairing's parts; and the Instance screen's connection test. Opening a session reads the
 * Keystore and opens the database, so the supervisor calls it off the main thread. A session, its store and its
 * announcer, holds its database for its life, outside ProfileDatabases' count of readers: the supervisor closes
 * each session it keeps before its instance is removed (SessionSupervisor.remove), and the close returns only once
 * the session's run and every request made before it have ended (Session.close). A pairing's session is the
 * supervisor's only once handed over (SessionSupervisor.adopt); before that nothing orders it against a removal
 * of the same daemon. "Pair again" merges only into a row in a trust state, whose session's run is over.
 */
internal class AppSessions(
    private val databases: ProfileDatabases,
    private val keys: DeviceKeyFacade,
    private val dialerFor: (port: Int, pin: ByteArray) -> Dialer,
    private val network: StateFlow<NetworkFacts>,
    private val announcer: (instanceId: String, database: Lazy<ProfileDatabase>) -> Announcer,
    private val appVersion: () -> String,
) : SessionOpener {
    override fun open(
        instance: Instance,
        scope: CoroutineScope,
    ): Session {
        if (instance.candidates.isEmpty()) throw SessionUnavailable("${instance.id} has no route to race")
        val parts = sessionParts(instance)
        val paired = PairedInstance(instance.deviceId, MAIN_PROFILE, instance.gatewayPublicKey())
        return Session.open(paired, instance.candidates, parts, scope, lastSuccessful = instance.lastCandidate)
    }

    /**
     * What [instance]'s session runs on: its Keystore key, its pinned WebSocket, its main profile's store and
     * announcer, and the full pull FCM's dropped messages asked for. It reads the Keystore and opens the database.
     */
    internal fun sessionParts(instance: Instance): SessionParts {
        val key = staticKey(instance.keyAlias)
        val database = mainProfile(instance.id)
        return SessionParts(
            appVersion = appVersion(),
            staticKey = key,
            dialer = dialerFor(instance.port, instance.tlsFingerprint()),
            store = RoomSessionStore(database, databases.stagedUploads(instance.id, MAIN_PROFILE)),
            announcer = announcer(instance.id, lazyOf(database)),
            network = network,
            // FCM dropped pushes for this phone: an empty cache pulls every row, not the newest page.
            fullPull = instance.historyPullDue,
        )
    }

    /**
     * What a pairing over [link] runs on: the app's dialers ([dialerFor]), the first profile's store and its
     * announcer, both opening their database at first use, off the main thread the pairing starts on, the
     * [keystore] dispatcher it reads the Keystore on, and [sessionScope], the supervisor's, for the session
     * the approval hands over. That session reads its store before the record is written, so its open lets
     * the daemon's files be opened again first, as a removal of it earlier in this process refuses them.
     */
    fun pairingParts(
        link: PairingLink,
        keystore: CoroutineDispatcher,
        sessionScope: CoroutineScope,
    ): PairingParts {
        val instanceId = instanceIdOf(link.gatewayPublicKey)
        val database =
            lazy {
                databases.admit(instanceId)
                databases.open(instanceId, MAIN_PROFILE)
            }
        return PairingParts(
            dialerFor = dialerFor,
            profileId = MAIN_PROFILE,
            store = RoomSessionStore(database, databases.stagedUploads(instanceId, MAIN_PROFILE)),
            announcer = announcer(instanceId, database),
            network = network,
            keystore = keystore,
            sessionScope = sessionScope,
        )
    }

    /** "Test connection": one race over [instance]'s candidates, as a session's, over the dialer its session dials. */
    suspend fun test(instance: Instance): TestOutcome {
        val dialer = dialerFor(instance.port, instance.tlsFingerprint())
        return raceOnce(instance.candidates) { candidate -> LinkCloser(dialer.dial(candidate)) }
    }

    /** [instanceId]'s main profile, or [SessionUnavailable] once a removal deleted its files. */
    private fun mainProfile(instanceId: String): ProfileDatabase =
        try {
            databases.open(instanceId, MAIN_PROFILE)
        } catch (removed: InstanceGone) {
            throw SessionUnavailable("$instanceId was removed", removed)
        }

    /** [alias]'s Keystore key, or [SessionUnavailable] when the Keystore cannot give it now. */
    private fun staticKey(alias: String): StaticKey =
        try {
            keys.staticKey(alias)
        } catch (fault: GeneralSecurityException) {
            throw SessionUnavailable("the Keystore refused $alias", fault)
        } catch (fault: ProviderException) {
            throw SessionUnavailable("the Keystore failed on $alias", fault)
        } catch (missing: IllegalStateException) {
            throw SessionUnavailable("the Keystore holds no key under $alias", missing)
        }
}

/**
 * One race of [open] over [candidates], as a session's (design section 5.1); the winner closes at once. Each
 * candidate whose own attempt failed before the race ended is named, the others it cancelled are not; with no
 * winner after [TEST_TIMEOUT_MILLIS], nothing answered.
 */
internal suspend fun <T : AutoCloseable> raceOnce(
    candidates: List<Candidate>,
    open: suspend (Candidate) -> T,
): TestOutcome {
    if (candidates.isEmpty()) return TestOutcome.NotReached(failed = emptySet())
    val started = TimeSource.Monotonic.markNow()
    val failed = ConcurrentHashMap.newKeySet<Candidate>()
    val result =
        withTimeoutOrNull(TEST_TIMEOUT_MILLIS) {
            CandidateRacer.race(
                candidateOrder(candidates),
            ) { candidate -> noting(candidate, failed) { open(candidate) } }
        }
    return when (result) {
        is RaceResult.Won -> {
            result.value.close()
            TestOutcome.Reached(result.candidate, started.elapsedNow().inWholeMilliseconds, failed.toSet())
        }

        is RaceResult.PinMismatch -> {
            TestOutcome.WrongIdentity
        }

        is RaceResult.AllFailed, null -> {
            TestOutcome.NotReached(failed.toSet())
        }
    }
}

/** The production dialers: each daemon's pinned WebSocket (core-transport), over one connector. */
internal fun webSocketDialers(): (port: Int, pin: ByteArray) -> Dialer {
    val connector = WebSocketConnector()
    return { port, pin -> WebSocketDialer(connector, port, PinnedTrust(pin)) }
}

/** A link the connection test won, which it closes at once, normally. */
private class LinkCloser(
    private val link: Link,
) : AutoCloseable {
    override fun close() = link.close(NORMAL_CLOSURE, "")
}

/**
 * [open] for [candidate], whose failure, any Exception as the race takes it (CandidateRacer), is noted in
 * [failed] and thrown on to the race. A cancelled attempt is the race's end, not the candidate's failure:
 * ensureActive throws it on first.
 */
private suspend fun <T> noting(
    candidate: Candidate,
    failed: MutableSet<Candidate>,
    open: suspend () -> T,
): T =
    try {
        open()
    } catch (expectedFailure: Exception) {
        currentCoroutineContext().ensureActive()
        failed += candidate
        throw expectedFailure
    }

/** An instance's id, as data keys its files: `sha256(gateway_pk)` in lowercase hex (design section 9.1). */
private fun instanceIdOf(gatewayPublicKey: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gatewayPublicKey))
