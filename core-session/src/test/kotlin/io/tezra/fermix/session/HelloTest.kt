package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.VersionDirection
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Hello first (PROTOCOL.md "Envelope, ordering, and version negotiation", design section 7): the
 * `hello` at seq 1 with the stored cursors, the version window, a protocol v1 daemon's refusal, and
 * the seq rules that close `1002`.
 */
class HelloTest {
    /** Long enough for a race of two candidates to dial both, inside the first backoff. */
    private val raceMs = 600L
    private val refusal =
        ServerEvent.Error(
            code = ServerEvent.Error.UNSUPPORTED_PROTOCOL_VERSION,
            message = "unsupported mobile protocol version",
            direction = VersionDirection.CLIENT_TOO_NEW,
            clientVersion = 2,
            minVersion = 1,
            maxVersion = 1,
        )

    @Test
    fun `hello is the first event, at seq 1, with the stored cursors and protocol 2`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors =
                EMPTY_CURSORS.copy(
                    lastServerSeq = 7uL,
                    readUpToSeq = 5uL,
                    lastMutationSeq = 3uL,
                    announcedUpToSeq = 7uL,
                )
            harness.open()
            val connection = harness.daemon.accept()
            connection.handshake()
            val hello = connection.next()
            assertEquals(2, hello.v)
            assertEquals(1uL, hello.seq)
            assertEquals(ClientEvent.Hello("device-1", "0.1.0", 7uL, 2, 3uL), hello.event)
        }

    @Test
    fun `a hello_ack whose window holds 2 connects over its candidate, its round trip the first latency`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            val connection = harness.daemon.accept()
            connection.handshake()
            connection.receive()
            assertEquals(null, harness.session.lastSuccessful.value)
            delay(12)
            connection.send(HELLO_ACK)
            val connected = harness.stateWhen { it is SessionState.Connected && it.caughtUp }
            assertEquals(SessionState.Connected(Candidate.Scope.TAILNET, 12, caughtUp = true), connected)
            assertEquals(TAILNET, harness.session.lastSuccessful.value)
            harness.settle()
            assertTrue(SessionEvent.Server(HELLO_ACK) in harness.events)
        }

    @Test
    fun `a session opened with the candidate an earlier one last reached races it first`() =
        runTest {
            val harness = Harness(this)
            harness.daemon.refusal = { IOException("unreachable") }
            harness.open(candidates = listOf(TAILNET, LAN), lastSuccessful = LAN)
            assertEquals(LAN, harness.session.lastSuccessful.value)
            delay(raceMs)
            assertEquals(listOf(LAN, TAILNET), harness.daemon.dialed.take(2))
        }

    @Test
    fun `a candidate an earlier session reached that the list no longer holds is not raced`() =
        runTest {
            val harness = Harness(this)
            harness.daemon.refusal = { IOException("unreachable") }
            harness.open(candidates = listOf(TAILNET), lastSuccessful = LAN)
            assertEquals(null, harness.session.lastSuccessful.value)
            delay(raceMs)
            assertEquals(listOf(TAILNET), harness.daemon.dialed.take(1))
        }

    @Test
    fun `a protocol v1 daemon's refusal ends the session as an older daemon`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            val connection = harness.daemon.accept()
            connection.handshake()
            connection.receive()
            connection.send(refusal, v = 1)
            connection.close(PROTOCOL_ERROR, "unsupported mobile protocol version")
            assertEquals(SessionState.OlderDaemon, harness.stateWhen { it is SessionState.Ended })
            harness.settle()
            assertEquals(listOf(SessionEvent.Refused(refusal)), harness.events)
            assertEquals(1, harness.daemon.dials)
        }

    @Test
    fun `a refusal that the phone is too old ends the session as a newer daemon`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            val connection = harness.daemon.accept()
            connection.handshake()
            connection.receive()
            connection.send(refusal.copy(direction = VersionDirection.CLIENT_TOO_OLD, minVersion = 3, maxVersion = 3))
            assertEquals(SessionState.NewerDaemon, harness.stateWhen { it is SessionState.Ended })
        }

    @Test
    fun `a window below 2 is an older daemon and one above it a newer daemon`() =
        runTest {
            val older = Harness(this)
            older.connect(HELLO_ACK.copy(minVersion = 1, maxVersion = 1))
            assertEquals(SessionState.OlderDaemon, older.stateWhen { it is SessionState.Ended })
            val newer = Harness(this)
            newer.connect(HELLO_ACK.copy(minVersion = 3, maxVersion = 4))
            assertEquals(SessionState.NewerDaemon, newer.stateWhen { it is SessionState.Ended })
        }

    @Test
    fun `a gap in the daemon's seq closes 1002 and the session races again after the backoff`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.sendAt(3uL, row(1uL))
            assertEquals(PROTOCOL_ERROR, connection.phoneClosed().code)
            val closedAt = harness.now
            val next = harness.daemon.accept()
            assertTrue(harness.now - closedAt in 500L..1_000L)
            next.handshake()
            assertEquals(1uL, next.next().seq)
            val refused = harness.diagnostics().single { it.kind == DiagnosticKind.PROTOCOL_ERROR }
            assertTrue("seq 3 arrived where 2 was due" in refused.detail, refused.detail)
        }

    @Test
    fun `a replayed seq closes 1002`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.sendAt(1uL, row(1uL))
            assertEquals(PROTOCOL_ERROR, connection.phoneClosed().code)
            assertTrue(harness.announcer.rows.isEmpty())
        }

    @Test
    fun `seq starts at 1 again on every connection, both ways`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            first.send(row(1uL))
            assertEquals(2uL, first.next().seq)
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            val second = harness.daemon.accept()
            val hello = second.connect()
            assertEquals(1uL, hello.seq)
            second.send(row(2uL))
            val acks = listOf(second.next(), second.next())
            assertEquals(listOf(2uL, 3uL), acks.map { it.seq })
            assertEquals(listOf(ClientEvent.Ack(1uL), ClientEvent.Ack(2uL)), acks.map { it.event })
        }

    @Test
    fun `an event this app does not know is noted and ignored`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.sendUnknown("future_event")
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            val unknown = harness.diagnostics().single { it.kind == DiagnosticKind.UNKNOWN_EVENT }
            assertEquals("future_event", unknown.detail)
        }

    @Test
    fun `a frame of protocol v1 after hello closes 1002`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(row(1uL), v = 1)
            assertEquals(PROTOCOL_ERROR, connection.phoneClosed().code)
            assertTrue(harness.diagnostics().any { it.kind == DiagnosticKind.PROTOCOL_ERROR && "v1" in it.detail })
        }
}
