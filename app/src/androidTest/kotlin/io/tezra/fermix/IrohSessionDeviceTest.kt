package io.tezra.fermix

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.attest.DeviceKeys
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.demo.DEMO_PORT
import io.tezra.fermix.demo.DEMO_SEED
import io.tezra.fermix.demo.DemoDaemon
import io.tezra.fermix.demo.DemoTimes
import io.tezra.fermix.demo.carryRoute
import io.tezra.fermix.onboarding.instanceOf
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.Announcer
import io.tezra.fermix.session.Dialer
import io.tezra.fermix.session.InstanceDialer
import io.tezra.fermix.session.Link
import io.tezra.fermix.session.Pairing
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.session.RouteDialer
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.session.deviceModel
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.IrohRail
import io.tezra.fermix.transport.NetworkFacts
import io.tezra.fermix.transport.ROUTE_CLOSE_WAIT_MS
import io.tezra.fermix.transport.RouteOpener
import io.tezra.fermix.transport.RouteStream
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** How long a pairing or a session's connect may take on a slow emulator. */
private const val CONNECT_MILLIS = 60_000L

/** The demo approves this soon after `pair_request`: the test reads no code. */
private const val APPROVE_MILLIS = 500L

/** Past core-session's 5 s stable connection, so the daemon's lifetime close reconnects silently. */
private const val RENEW_AFTER_MILLIS = 6_000L

/** How long a test's end waits for what it started to end, before its threads are let go of. */
private const val END_MILLIS = 10_000L

/** How long a race's loser has to be closed after the winner connects. */
private const val SETTLE_MILLIS = 3_000L

/** A session idle this long sends its keepalive ping (core-session's KEEPALIVE_MS). */
private const val KEEPALIVE_MILLIS = 25_000L

/** The ping's way there and its pong's way back, with room to spare. */
private const val PONG_MARGIN_MILLIS = 5_000L

/** How often a test looks again at what the test daemon counted. */
private const val POLL_MILLIS = 100L

/** The daemon's hourly close (PROTOCOL.md "Close codes"). */
private const val LIFETIME_REASON = "Noise session lifetime reached"

private val ON_A_NETWORK =
    NetworkFacts(100L, defaultHasVpn = false, defaultHasCgnatAddress = false, otherUidVpnPresent = false)

/**
 * Sessions over the iroh rail on a device (M62 stage 6), the demo daemon behind a real iroh endpoint as fermix-iroh
 * is in front of the engine: each stream the test daemon takes is carried to the demo's own connection
 * (demo-daemon's carryRoute). The phone's side is the app's: its sessions and pairing parts (AppSessions) over the
 * hosts' dialer the test sets and the rail's. A pairing over the route alone, its session and the next one on the kept
 * connection; an idle session's ping and pong; a host and the route raced together making one session, every other
 * stream let go; a route that loses the race closed, both its halves ended; the hourly renewal as a second stream; a
 * network change.
 */
@RunWith(AndroidJUnit4::class)
class IrohSessionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The test's own threads, which it ends last: the demo's, the rail's, the sessions' and the Keystore's work. */
    private val threads = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + threads)
    private val demo = DemoDaemon(DEMO_SEED, scope, DemoTimes(reach = 0, check = 0, approve = APPROVE_MILLIS))
    private val fermix = demo.fermixes.first()
    private val active = AtomicInteger()

    /** Completed as the test daemon takes a stream: a test sets a new one to hear of the next stream it takes. */
    @Volatile
    private var routeTaken = CompletableDeferred<Unit>()

    /** The demo's end of each session the test daemon carried, in order. */
    private val carried = CopyOnWriteArrayList<CountedLink>()

    /** The daemon's end of each stream the test daemon took, in order. */
    private val taken = CopyOnWriteArrayList<RouteStream>()

    /** While true, the test daemon holds each stream it takes unanswered, until the test ends. */
    @Volatile
    private var holdRoute = false

    /** Whether a stream was closed as an hour's end already: only the first after [renewAfterMs] is set is. */
    private val renewed = AtomicBoolean(false)

    @Volatile
    private var renewAfterMs: Long? = null
    private val daemon = IrohTestDaemon(context, threads) { halves -> carry(framed(halves)) }
    private val rail = IrohRail(context, knownRelays = { emptySet() }, scope, daemon::plan)

    /** The phone's end of each stream the rail opened, in order, a race's loser among them. */
    private val opened = CopyOnWriteArrayList<RouteStream>()
    private val routes = RouteOpener { route -> rail.open(route).also { opened += it } }
    private val refusing = Dialer { throw TransportException.Unreachable(IOException("no host answers this test")) }

    @Volatile
    private var hosts: Dialer = refusing
    private val network = MutableStateFlow(ON_A_NETWORK)
    private val folder = File(context.cacheDir, "iroh-session-test-${UUID.randomUUID()}")
    private val keys = DeviceKeys()
    private val aliases = mutableListOf<String>()
    private val sessions =
        AppSessions(
            ProfileDatabases(context, folder),
            keys,
            { _, _ -> InstanceDialer(Dialer { hosts.dial(it) }, RouteDialer(routes)) },
            network,
            announcer = { _, _ -> Announcer { Announcement.NOT_ANNOUNCED } },
        ) { "test" }

    @After
    fun end() {
        runBlocking { rail.shutdown() }
        daemon.close()
        // What the test started ends before its threads do, bounded.
        runBlocking { withTimeout(END_MILLIS) { scope.coroutineContext.job.cancelAndJoin() } }
        threads.close()
        aliases.forEach { keys.delete(it) }
        folder.deleteRecursively()
    }

    /**
     * The demo's end of a session, carried over [stream] and counted; the first stream once [renewAfterMs] is set
     * closes as an hour's end.
     */
    private suspend fun carry(stream: RouteStream) {
        taken += stream
        routeTaken.complete(Unit)
        if (holdRoute) awaitCancellation()
        val renewing = renewAfterMs?.takeIf { renewed.compareAndSet(false, true) }
        renewing?.let { scope.launch { renew(stream, it) } }
        active.incrementAndGet()
        try {
            val demoEnd = checkNotNull(demo.dialerFor(DEMO_PORT, fermix.tlsFingerprint)).dial(fermix.route)
            val counted = CountedLink(demoEnd, scope)
            carried += counted
            carryRoute(stream, counted)
        } finally {
            active.decrementAndGet()
        }
    }

    private suspend fun renew(
        stream: RouteStream,
        afterMs: Long,
    ) {
        delay(afterMs)
        stream.close(1000, LIFETIME_REASON)
    }

    /** Pairs with the demo over the link's route, its hosts refused; the record the approval writes. */
    private suspend fun pairOverRoute(): Pair<Instance, Session> {
        val link = PairingLink.parse(demo.nextLink() + daemon.linkParameters(daemon.route()))
        val identity = PhoneIdentity("Iroh test", deviceModel(Build.MANUFACTURER, Build.MODEL), "test")
        // No store holds this test's records, so no hello names a revision (AppSessionsTest proves the reader).
        val parts = sessions.pairingParts(link, threads, scope) { null }
        val handle = Pairing.start(link, keys, identity, parts, scope)
        val state =
            withTimeout(CONNECT_MILLIS) {
                handle.state.first { it is PairingState.Approved || it is PairingState.Ended }
            }
        val approved = state as? PairingState.Approved ?: error("the pairing ended as $state")
        handle.commit { null }
        aliases += approved.facts.keyAlias
        return instanceOf(approved.facts, "Slate", identity.deviceName, System.currentTimeMillis()) to approved.session
    }

    private fun open(record: Instance): Session = sessions.open(record, scope) { record.route?.revision }

    @Test
    fun a_pairing_over_the_route_alone_hands_over_a_remote_session_and_the_next_session_rides_the_kept_connection() =
        runBlocking {
            val (record, paired) = pairOverRoute()
            assertEquals(daemon.route(), record.route)
            assertEquals(SessionState.Connected::class, connected(paired)::class)
            assertEquals(Candidate.Scope.REMOTE, connected(paired).scope)
            paired.close()

            val session = open(record)
            assertEquals(Candidate.Scope.REMOTE, connected(session).scope)
            assertEquals(daemon.route(), session.lastSuccessful.value)
            assertEquals(1, daemon.connections.size)
            assertEquals(2, daemon.streams.get())
            session.close()
        }

    @Test
    fun an_idle_session_over_the_route_keeps_alive_with_a_ping_and_the_daemons_pong() =
        runBlocking {
            val (record, paired) = pairOverRoute()
            paired.close()
            val session = open(record)
            withTimeout(CONNECT_MILLIS) { session.state.first { (it as? SessionState.Connected)?.caughtUp == true } }
            delay(SETTLE_MILLIS)
            val demoEnd = carried.last()
            val streams = daemon.streams.get()
            val (sent, heard) = demoEnd.sent.get() to demoEnd.heard.get()
            delay(KEEPALIVE_MILLIS + PONG_MARGIN_MILLIS)
            assertEquals("the phone's ping, and nothing else", sent + 1, demoEnd.sent.get())
            assertEquals("the demo's pong, and nothing else", heard + 1, demoEnd.heard.get())
            assertEquals("the same stream", streams, daemon.streams.get())
            assertEquals(Candidate.Scope.REMOTE, (session.state.value as SessionState.Connected).scope)
            session.close()
        }

    @Test
    fun two_winners_make_one_session_and_the_loser_is_closed() =
        runBlocking {
            val (record, paired) = pairOverRoute()
            paired.close()
            // The host answers only once the route's stream has reached the daemon (the pairing's reached it before):
            // both finish their handshakes together.
            val routeReached = CompletableDeferred<Unit>().also { routeTaken = it }
            val demoDialer = checkNotNull(demo.dialerFor(DEMO_PORT, fermix.tlsFingerprint))
            hosts =
                Dialer { candidate ->
                    routeReached.await()
                    demoDialer.dial(candidate)
                }
            val session = open(record)
            connected(session)
            delay(SETTLE_MILLIS)
            val routeWon = session.lastSuccessful.value is Candidate.Route
            assertEquals(if (routeWon) 1 else 0, active.get())
            assertTrue(session.state.value is SessionState.Connected)
            // Every stream but the session's has ended both halves and let go of them: the pairing's, and the
            // route's when it lost.
            val ended = opened.filter { it.closed.isCompleted }
            assertEquals(opened.size - if (routeWon) 1 else 0, ended.size)
            withTimeout(ROUTE_CLOSE_WAIT_MS + SETTLE_MILLIS) { ended.forEach { it.awaitReleased() } }
            session.close()
        }

    @Test
    fun a_route_that_loses_the_race_is_closed_and_its_stream_let_go() =
        runBlocking {
            val (record, paired) = pairOverRoute()
            paired.close()
            // The host answers once the daemon holds the route's stream unanswered: the host wins, and the route's
            // attempt is cancelled with its stream open.
            holdRoute = true
            val routeHeld = CompletableDeferred<Unit>().also { routeTaken = it }
            val demoDialer = checkNotNull(demo.dialerFor(DEMO_PORT, fermix.tlsFingerprint))
            hosts =
                Dialer { candidate ->
                    routeHeld.await()
                    demoDialer.dial(candidate)
                }
            val session = open(record)
            connected(session)
            assertTrue(session.lastSuccessful.value is Candidate.Host)
            assertEquals("the pairing's stream and the loser's", 2, opened.size)
            val heard = withTimeout(SETTLE_MILLIS) { taken.last().closed.await() } as TransportException.Closed
            assertEquals("the phone's close frame, as the daemon heard it", 1000 to true, heard.code to heard.byDaemon)
            // Its sending half finished or, unanswered, reset within the close's wait, and both halves let go.
            withTimeout(ROUTE_CLOSE_WAIT_MS + SETTLE_MILLIS) { opened.last().awaitReleased() }
            session.close()
        }

    @Test
    fun the_hourly_renewal_is_a_second_stream_on_the_kept_connection() =
        runBlocking {
            val (record, paired) = pairOverRoute()
            paired.close()
            val before = daemon.streams.get()
            renewAfterMs = RENEW_AFTER_MILLIS
            val session = open(record)
            connected(session)
            withTimeout(CONNECT_MILLIS) { while (daemon.streams.get() < before + 2) delay(POLL_MILLIS) }
            assertEquals(Candidate.Scope.REMOTE, connected(session).scope)
            assertEquals(1, daemon.connections.size)
            session.close()
        }

    @Test
    fun a_network_change_reconnects_over_the_route_on_the_endpoint_the_process_keeps() =
        runBlocking {
            val (record, paired) = pairOverRoute()
            paired.close()
            val session = open(record)
            connected(session)
            val before = daemon.streams.get()
            network.value = ON_A_NETWORK.copy(defaultNetwork = 101L)
            withTimeout(CONNECT_MILLIS) { while (daemon.streams.get() <= before) delay(POLL_MILLIS) }
            assertEquals(Candidate.Scope.REMOTE, connected(session).scope)
            session.close()
        }
}

/**
 * [link], the demo's end of one carried session, counting the phone's messages to the demo as [sent] and the demo's
 * to the phone as [heard]; each Noise message is one, whatever it carries.
 */
private class CountedLink(
    private val link: Link,
    scope: CoroutineScope,
) : Link by link {
    val sent = AtomicInteger()
    val heard = AtomicInteger()
    private val passed = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                for (message in link.incoming) {
                    heard.incrementAndGet()
                    passed.send(message)
                }
            } finally {
                passed.close()
            }
        }
    }

    override val incoming: ReceiveChannel<ByteArray> get() = passed

    override fun send(message: ByteArray): Boolean = link.send(message).also { if (it) sent.incrementAndGet() }
}

private suspend fun connected(session: Session): SessionState.Connected =
    withTimeout(CONNECT_MILLIS) { session.state.first { it is SessionState.Connected } as SessionState.Connected }
