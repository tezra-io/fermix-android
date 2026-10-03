package io.tezra.fermix.session

import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.protocol.CandidateScope
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.protocol.Profile
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.net.URLEncoder
import java.util.Base64
import kotlin.random.Random
import io.tezra.fermix.protocol.Candidate as WireCandidate

/** The daemon's name in the link, the host the onboarding screens name. */
internal const val HOST_NAME = "suj-mbp"

/** The link's profile: the daemon's own, which its instance record keeps (design D11). */
internal const val LINK_PROFILE = "work"

internal const val DEVICE_ID = "device-7"

/** The link's port. */
internal const val LINK_PORT = 8743

/** The phone as the owner named it, and its model. */
internal val IDENTITY = PhoneIdentity("Pixel 9 Pro", "Google Pixel 9 Pro", "0.1.0")

/** The daemon's approval at protocol v2: its device id, one tailnet route, `main`, a push salt and FCM. */
internal val APPROVAL =
    ServerEvent.PairApproved(
        deviceId = DEVICE_ID,
        candidates = listOf(WireCandidate("100.101.102.104", "utun4", CandidateScope.TAILNET)),
        profiles = listOf(Profile(PROFILE, "Fermix")),
        pushSalt = Base64.getEncoder().encodeToString(ByteArray(32) { 9 }),
        push = listOf(PushPlatform.FCM),
    )

/**
 * One pairing against the fake daemon, on the test's virtual clock: the link it scans, the software
 * Keystore, the Keystore's dispatcher on the test's scheduler, and every state the handle showed, in
 * order. The daemon keeps its own copy of the secret, as a daemon does, since the phone zeroes the link's.
 */
internal class PairingHarness(
    private val test: TestScope,
    private val collecting: CoroutineDispatcher = Dispatchers.Unconfined,
) {
    val daemon = FakeDaemon()
    val keys = SoftwareDeviceKeys()
    val store = MemoryStore()
    val network = MutableStateFlow(ONLINE)
    val secret = ByteArray(32) { (it + 1).toByte() }
    val tlsFingerprint = ByteArray(32) { 0x4f }
    val states = mutableListOf<PairingState>()

    /** Each dialer the ceremony asked for, as its port and its pin in hex. */
    val dialersMade = mutableListOf<Pair<Int, String>>()

    /** What a scope from [faultScope] failed with, in order. */
    val faults = mutableListOf<Throwable>()
    lateinit var link: PairingLink
    lateinit var handle: PairingHandle

    /** A version-2 link as the daemon writes it, form-encoded. */
    fun linkText(candidates: List<String> = listOf(TAILNET.host)): String {
        val parameters =
            listOf(
                "v" to "2",
                "candidates" to candidates.joinToString(",", "[", "]") { "\"$it\"" },
                "port" to "$LINK_PORT",
                "tls_fp" to tlsFingerprint.toHexString(),
                "gateway_pk" to Base64.getEncoder().encodeToString(daemon.gatewayKey.publicKey),
                "secret" to Base64.getEncoder().encodeToString(secret),
                "name" to HOST_NAME,
                "profile" to LINK_PROFILE,
            )
        val query =
            parameters.joinToString(
                "&",
            ) { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        return "fermix://pair?$query"
    }

    /**
     * What a pairing runs on here: the fake daemon behind every dialer, the test's clock and seed, and
     * [sessionScope] for the session an approval hands over.
     */
    fun parts(sessionScope: CoroutineScope): PairingParts =
        PairingParts(
            dialerFor = { port, pin -> daemon.also { dialersMade += port to pin.toHexString() } },
            profileId = PROFILE,
            store = store,
            announcer = RecordingAnnouncer(),
            network = network,
            keystore = StandardTestDispatcher(test.testScheduler),
            sessionScope = sessionScope,
            clock = test.testScheduler.timeSource,
            random = Random(SEED),
        )

    /**
     * Parses [text] and starts pairing over it with [keys] in [scope], the test's background scope at first;
     * the session an approval hands over runs in [sessionScope], the pairing's own scope unless a test names another.
     */
    fun start(
        text: String = linkText(),
        keys: DeviceKeyFacade = this.keys,
        scope: CoroutineScope = test.backgroundScope,
        sessionScope: CoroutineScope = scope,
    ): PairingHandle {
        link = PairingLink.parse(text)
        handle = Pairing.start(link, keys, IDENTITY, parts(sessionScope), scope)
        // Unconfined, so every state the handle publishes is seen as it is set, none conflated into the next.
        test.backgroundScope.launch(collecting) { handle.state.collect { states += it } }
        return handle
    }

    /** A child of the background scope that ends on its own, as a screen's does. */
    fun childScope(): CoroutineScope =
        CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))

    /** A child of the background scope whose coroutines' faults land in [faults] instead of failing the test. */
    fun faultScope(): CoroutineScope =
        CoroutineScope(
            test.backgroundScope.coroutineContext +
                SupervisorJob(test.backgroundScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, fault -> faults += fault },
        )

    /** The first state that is an [S]; a virtual three minutes without one fails the test. */
    suspend inline fun <reified S : PairingState> state(): S =
        withTimeout(WAIT_MS) { handle.state.first { it is S } as S }

    /** The daemon's side of the race's one connection, its IKpsk2 handshake answered. */
    suspend fun paired(): DaemonConnection = daemon.accept().also { it.pair(secret) }

    /** The ceremony up to the daemon's `pair_approved`, the daemon's side answered as it goes. */
    suspend fun approved(): PairingState.Approved {
        val connection = paired()
        connection.pairRequest()
        connection.send(APPROVAL)
        return state<PairingState.Approved>()
    }

    /** Lets every coroutine that can run, run, without moving the clock. */
    suspend fun settle() {
        repeat(SETTLE_YIELDS) { yield() }
    }

    companion object {
        const val SEED = 51L
        const val SETTLE_YIELDS = 200
        const val WAIT_MS = 180_000L

        /**
         * How many yields a test that races a cancel against a handle call steps through, one interleaving
         * each: past every dispatch the call, its join of the ceremony and the ceremony it starts make.
         */
        const val RACE_YIELDS = 8
    }
}
