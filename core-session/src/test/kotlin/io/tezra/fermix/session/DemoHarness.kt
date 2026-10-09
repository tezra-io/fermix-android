package io.tezra.fermix.session

import io.tezra.fermix.demo.DEMO_PORT
import io.tezra.fermix.demo.DEMO_SEED
import io.tezra.fermix.demo.DemoDaemon
import io.tezra.fermix.demo.DemoFermix
import io.tezra.fermix.demo.SoftwareKey
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.PairingLink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/** The wall clock the demo stamps rows with in a test: 2026-09-27 09:30 UTC, plus the test's virtual time. */
private const val WALL_MS = 1_790_501_400_000L

/** A virtual ten minutes: the longest a demo test waits for one thing before it fails. */
internal const val DEMO_WAIT_MS = 600_000L

/**
 * The debug app's demo against core-session's own pairing and session, on the test's virtual clock: the demo
 * daemon with [seed], its rows stamped from a fixed morning, and the phone's store, announcer and network as the
 * other tests keep them. Every session event lands in [events] with the virtual time it came at, from the
 * harness's start; a pairing's states are read on [collecting], as each comes.
 */
internal class DemoHarness(
    private val test: TestScope,
    seed: Long = DEMO_SEED,
    scope: CoroutineScope = test.backgroundScope,
    private val collecting: CoroutineDispatcher = Dispatchers.Unconfined,
) {
    private val started = test.testScheduler.timeSource.markNow()
    val demo =
        DemoDaemon(
            seed,
            scope,
            clock = test.testScheduler.timeSource,
            wallMs = { WALL_MS + now },
        )
    val store = MemoryStore()
    val announcer = RecordingAnnouncer()
    val network = MutableStateFlow(ONLINE)
    val events = mutableListOf<Pair<Long, SessionEvent>>()
    val phoneKey = SoftwareKey.generate()
    lateinit var session: Session

    /** Virtual milliseconds since the harness was made. */
    val now: Long get() = started.elapsedNow().inWholeMilliseconds

    /** The phone's dialer for [fermix], as the debug app's AppSessions asks the demo for it. */
    fun dialerOf(fermix: DemoFermix): Dialer = checkNotNull(demo.dialerFor(DEMO_PORT, fermix.tlsFingerprint))

    /** A pairing's parts with the demo behind its dialer, its session in [sessionScope]. */
    fun pairingParts(sessionScope: CoroutineScope): PairingParts =
        PairingParts(
            dialerFor = { port, pin -> checkNotNull(demo.dialerFor(port, pin)) { "no demo Fermix on $port" } },
            profileId = PROFILE,
            store = store,
            announcer = announcer,
            network = network,
            keystore = StandardTestDispatcher(test.testScheduler),
            sessionScope = sessionScope,
            clock = test.testScheduler.timeSource,
            random = Random(SEED),
        )

    /**
     * Starts pairing over the demo's next link, pasted [waitedMs] after the demo opened its window; every state
     * lands in [states] with its virtual time.
     */
    suspend fun pair(
        keys: SoftwareDeviceKeys,
        states: MutableList<Pair<Long, PairingState>>,
        waitedMs: Long = 0L,
    ): PairingHandle {
        val link = PairingLink.parse(demo.nextLink())
        delay(waitedMs)
        val handle = Pairing.start(link, keys, IDENTITY, pairingParts(test.backgroundScope), test.backgroundScope)
        test.backgroundScope.launch(collecting) { handle.state.collect { states += now to it } }
        return handle
    }

    /**
     * Opens a session on [fermix] as a phone paired with it opens one, by [deviceId], with this harness's store,
     * which keeps its cursors across sessions; the demo takes the phone's key at its word, as after a restart.
     */
    fun open(
        fermix: DemoFermix = demo.fermixes.first(),
        deviceId: String = "demo-${fermix.index}-1",
    ): Session {
        val parts =
            SessionParts(
                appVersion = "0.1.0",
                staticKey = phoneKey,
                dialer = dialerOf(fermix),
                store = store,
                announcer = announcer,
                network = network,
                clock = test.testScheduler.timeSource,
                random = Random(SEED),
                io = StandardTestDispatcher(test.testScheduler),
            )
        val instance = PairedInstance(deviceId, PROFILE, fermix.gatewayKey.publicKey)
        session = Session.open(instance, listOf(fermix.route), parts, test.backgroundScope)
        val opened = session
        test.backgroundScope.launch { opened.events.collect { events += now to it } }
        return session
    }

    /** The session once its connection is up and caught up with the history. */
    suspend fun caughtUp() {
        withTimeout(DEMO_WAIT_MS) { session.state.first { it is SessionState.Connected && it.caughtUp } }
    }

    /** Sends [text] as the owner's message [clientMsgId]. */
    suspend fun say(
        clientMsgId: String,
        text: String,
    ) {
        check(session.send(ClientEvent.Msg(clientMsgId, PROFILE, text, emptyList()))) { "the outbox is full" }
    }

    /** The first session event [take] picks, of those after the first [after], waited for. */
    suspend fun <T : Any> awaitEvent(
        after: Int = 0,
        take: (SessionEvent) -> T?,
    ): T = waitFor { events.toList().drop(after).firstNotNullOfOrNull { take(it.second) } }

    /** The first row the announcer took whose words [match], waited for. */
    suspend fun awaitRow(match: (String) -> Boolean): TimelineRow =
        waitFor { announcer.rows.toList().firstOrNull { match(wordsOf(it)) } }

    /** What [found] finds, looked for again every [POLL_MS] of virtual time, for [DEMO_WAIT_MS] at most. */
    private suspend fun <T : Any> waitFor(found: () -> T?): T =
        withTimeout(DEMO_WAIT_MS) {
            var value = found()
            while (value == null) {
                delay(POLL_MS)
                value = found()
            }
            value
        }

    /** [turnId]'s effects as the phone drew them, each with the virtual time it came at. */
    fun effectsOf(turnId: String): List<Pair<Long, TurnEffect>> =
        events.mapNotNull { (at, event) ->
            (event as? SessionEvent.Turn)?.effect?.takeIf { it.turnId == turnId }?.let { at to it }
        }

    /** Waits for [turnId] to end, and how it did. */
    suspend fun ended(turnId: String): TurnOutcome =
        awaitEvent { event ->
            ((event as? SessionEvent.Turn)?.effect as? TurnEffect.TurnEnded)?.takeIf { it.turnId == turnId }?.outcome
        }

    private companion object {
        const val SEED = 51L
        const val POLL_MS = 10L
    }
}

/** A row's words: a message's content, or a sealed reply's text. */
internal fun wordsOf(row: TimelineRow): String =
    when (row) {
        is TimelineRow.Message -> row.message.content
        is TimelineRow.Reply -> row.text
    }
