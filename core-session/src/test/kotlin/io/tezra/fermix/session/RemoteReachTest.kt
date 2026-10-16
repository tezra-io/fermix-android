package io.tezra.fermix.session

import io.tezra.fermix.demo.MemoryLink
import io.tezra.fermix.demo.carryRoute
import io.tezra.fermix.protocol.CandidateScope
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Relay
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.RouteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import io.tezra.fermix.protocol.Candidate as WireCandidate

private const val NODE = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"
private const val REV = "374b4c8f103627f7"

/** The route as the race holds it, and as hello_ack carries it. */
internal val ROUTE =
    Candidate.Route(
        NODE,
        listOf(Candidate.Relay("us1.relay.fermix.ai", 7842), Candidate.Relay("eu1.relay.fermix.ai", 7842)),
        REV,
    )
private val WIRE_ROUTE =
    WireCandidate.Iroh(
        NODE,
        listOf(Relay("https://us1.relay.fermix.ai/", 7842), Relay("https://eu1.relay.fermix.ai/", 7842)),
        REV,
    )
private val WIRE_LAN = WireCandidate.Host("192.168.1.20", "en0", CandidateScope.LAN)

/**
 * M62 on the phone (PROTOCOL.md "Remote reach"): the route a `hello_ack` carries joins the race last and is kept
 * when an ack names none, a kind the app does not know is skipped and noted once, `hello` names the stored
 * revision, the route wins when every host fails, and a session over the route's stream keeps itself alive as one
 * over a WebSocket does.
 */
class RemoteReachTest {
    private fun ackWith(vararg candidates: WireCandidate) = HELLO_ACK.copy(candidates = candidates.toList())

    @Test
    fun `a hello_ack's route joins the hosts, last, for the record and the next race`() =
        runTest {
            val harness = Harness(this)
            harness.connect(ackWith(WIRE_ROUTE, HELLO_ACK.candidates.single(), WIRE_LAN))
            val listed =
                harness.events
                    .filterIsInstance<SessionEvent.Candidates>()
                    .single()
                    .candidates
            assertEquals(listOf(TAILNET, LAN, ROUTE), listed)
        }

    @Test
    fun `an ack with no route keeps the route the session holds, and one with no host keeps its hosts`() =
        runTest {
            val harness = Harness(this)
            harness.open(candidates = listOf(TAILNET, ROUTE))
            val connection = harness.daemon.accept()
            connection.connect(ackWith(WIRE_LAN))
            harness.settle()
            assertEquals(
                listOf(LAN, ROUTE),
                harness.events
                    .filterIsInstance<SessionEvent.Candidates>()
                    .last()
                    .candidates,
            )
            connection.close(1000, "Noise session lifetime reached")
            harness.daemon.accept().connect(ackWith(WIRE_ROUTE.copy(relaysRev = "0000000000000002")))
            harness.settle()
            val kept =
                harness.events
                    .filterIsInstance<SessionEvent.Candidates>()
                    .last()
                    .candidates
            assertEquals(listOf(LAN, ROUTE.copy(revision = "0000000000000002")), kept)
        }

    @Test
    fun `a candidate of a kind this app does not know is skipped, never dialled, and noted once for its list`() =
        runTest {
            val harness = Harness(this)
            val wireguard = WireCandidate.Skipped(JsonObject(mapOf("kind" to JsonPrimitive("wireguard"))))
            val numbered = WireCandidate.Skipped(JsonObject(mapOf("kind" to JsonPrimitive(7))))
            harness.connect(ackWith(wireguard, HELLO_ACK.candidates.single(), numbered))
            assertEquals(
                listOf(TAILNET),
                harness.events
                    .filterIsInstance<SessionEvent.Candidates>()
                    .single()
                    .candidates,
            )
            val notes = harness.diagnostics().filter { it.kind == DiagnosticKind.ROUTE }
            assertEquals(1, notes.size)
            assertTrue("'wireguard'" in notes.single().detail, notes.single().detail)
            assertTrue("a kind that is not text" in notes.single().detail, notes.single().detail)
        }

    @Test
    fun `hello names the revision the record has stored, and none without a route`() =
        runTest {
            val stored = Harness(this)
            stored.relayListRev = { REV }
            stored.open(candidates = listOf(TAILNET, ROUTE))
            val withRoute = stored.daemon.accept()
            withRoute.handshake()
            assertEquals(REV, (withRoute.next().event as ClientEvent.Hello).relayListRev)

            val none = Harness(this)
            none.open()
            val bare = none.daemon.accept()
            bare.handshake()
            assertNull((bare.next().event as ClientEvent.Hello).relayListRev)
        }

    @Test
    fun `when every host fails the route wins, and the session is connected from anywhere`() =
        runTest {
            val harness = Harness(this)
            harness.daemon.refusal =
                { candidate -> IOException("no route to $candidate").takeIf { candidate is Candidate.Host } }
            harness.open(candidates = listOf(TAILNET, LAN, ROUTE))
            val connection = harness.daemon.accept()
            assertEquals(ROUTE, connection.candidate)
            connection.connect()
            val connected = harness.stateWhen { it is SessionState.Connected } as SessionState.Connected
            assertEquals(Candidate.Scope.REMOTE, connected.scope)
            assertEquals(ROUTE, harness.session.lastSuccessful.value)
        }

    @Test
    fun `a session over the route's stream sends its ping after 25 s, and its pong comes back`() =
        runTest {
            val harness = Harness(this)
            harness.dialer = routeDialer(harness.daemon, backgroundScope)
            harness.open(candidates = listOf(ROUTE))
            val connection = harness.daemon.accept()
            connection.connect()
            harness.stateWhen { it is SessionState.Connected }
            val connectedAt = harness.now
            connection.expect<ClientEvent.Ping>()
            assertEquals(KEEPALIVE_MS, harness.now - connectedAt)
            delay(40)
            connection.send(ServerEvent.Pong)
            val connected = harness.stateWhen { it is SessionState.Connected && it.latencyMs == 40L }
            assertEquals(SessionState.Connected(Candidate.Scope.REMOTE, 40, caughtUp = true), connected)
        }

    @Test
    fun `the daemon's hourly close over the route's stream reconnects silently over a second stream`() =
        runTest {
            val harness = Harness(this)
            harness.dialer = routeDialer(harness.daemon, backgroundScope)
            harness.open(candidates = listOf(ROUTE))
            val first = harness.daemon.accept()
            first.connect()
            delay(STABLE_CONNECTION_MS)
            val closedAt = harness.now
            first.close(1000, LIFETIME_REASON)
            val second = harness.daemon.accept()
            assertEquals(closedAt, harness.now)
            assertEquals(ROUTE, second.candidate)
            second.connect()
            harness.settle()
            val connected = SessionState.Connected(Candidate.Scope.REMOTE, 0, caughtUp = true)
            assertEquals(listOf(SessionState.Connecting, connected), harness.states)
        }
}

/**
 * [daemon]'s sessions over the route's stream: each dial opens a stream in memory whose daemon end [carryRoute]
 * carries to the socket [daemon] serves, as fermix-iroh carries a stream to the daemon's socket handler.
 */
private fun routeDialer(
    daemon: FakeDaemon,
    scope: CoroutineScope,
): Dialer =
    RouteDialer { route ->
        val socket = daemon.dial(route) as MemoryLink
        val stream = MemoryRoute()
        val daemonEnd = RouteStream(stream.daemon, scope)
        scope.launch { carryRoute(daemonEnd, socket) }
        RouteStream(stream.phone, scope)
    }
