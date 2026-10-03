package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException

/**
 * A connection's life (design section 5.1, PROTOCOL.md "Close codes"): the keepalive, the hourly close,
 * the close codes that stop the session or back it off, the daemon's identity, suspend and resume, the
 * bound on races, and how the session ends when the phone fails or its scope goes.
 */
class LifetimeTest {
    @Test
    fun `a ping goes after 25 s with nothing sent, and its pong is the latency`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val connectedAt = harness.now
            connection.expect<ClientEvent.Ping>()
            assertEquals(KEEPALIVE_MS, harness.now - connectedAt)
            delay(40)
            connection.send(ServerEvent.Pong)
            harness.settle()
            val connected = SessionState.Connected(Candidate.Scope.TAILNET, 40, caughtUp = true)
            assertEquals(connected, harness.session.state.value)
        }

    @Test
    fun `two pongs missed in a row close the link and the session reconnects at once`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val connectedAt = harness.now
            connection.expect<ClientEvent.Ping>()
            connection.expect<ClientEvent.Ping>()
            assertNull(connection.receive())
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertEquals(3 * KEEPALIVE_MS, harness.now - connectedAt)
            harness.daemon.accept()
            assertEquals(3 * KEEPALIVE_MS, harness.now - connectedAt)
            assertTrue(harness.diagnostics().any { it.detail == Ending.KeepaliveLost.toString() })
        }

    @Test
    fun `pongs that come 26 s after their pings lose the link, and the latency shown is theirs`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val connectedAt = harness.now
            backgroundScope.launch { connection.answerPings(backgroundScope, 26_000, mutableListOf()) }
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertEquals(3 * KEEPALIVE_MS, harness.now - connectedAt)
            assertTrue(SessionState.Connected(Candidate.Scope.TAILNET, 26_000, caughtUp = true) in harness.states)
        }

    @Test
    fun `pongs that come 24 s after their pings keep the link`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            backgroundScope.launch { connection.answerPings(backgroundScope, 24_000, mutableListOf()) }
            delay(5 * 60_000L)
            assertEquals(1, harness.daemon.dials)
            val connected = SessionState.Connected(Candidate.Scope.TAILNET, 24_000, caughtUp = true)
            assertEquals(connected, harness.session.state.value)
        }

    @Test
    fun `an announcer that holds the reader for minutes is no lost link, since the pongs are read after it`() =
        runTest {
            val harness = Harness(this)
            harness.announcer.delayMs = 120_000
            val connection = harness.connect()
            val others = mutableListOf<ClientEvent>()
            backgroundScope.launch { connection.answerPings(backgroundScope, 50, others) }
            connection.send(row(1uL))
            connection.send(row(2uL))
            delay(5 * 60_000L)
            assertEquals(1, harness.daemon.dials)
            assertEquals(listOf<ClientEvent>(ClientEvent.Ack(1uL), ClientEvent.Ack(2uL)), others)
            assertTrue(harness.diagnostics().none { it.kind == DiagnosticKind.CLOSED })
        }

    @Test
    fun `the hourly 1000 close reconnects at once and says nothing to the UI`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            delay(STABLE_CONNECTION_MS)
            val closedAt = harness.now
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            val second = harness.daemon.accept()
            assertEquals(closedAt, harness.now)
            second.connect()
            harness.settle()
            val connected = SessionState.Connected(Candidate.Scope.TAILNET, 0, caughtUp = true)
            assertEquals(listOf(SessionState.Connecting, connected), harness.states)
        }

    @Test
    fun `a silent reconnect stays caught up while it reconciles, so the subtitle says nothing`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            delay(STABLE_CONNECTION_MS)
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            val second = harness.daemon.accept()
            second.connect(HELLO_ACK.copy(historyHeadSeq = 1uL))
            val newest = ClientEvent.HistoryPull(PROFILE, beforeSeq = 2uL, limit = 50)
            assertEquals(newest, second.expect<ClientEvent.HistoryPull>())
            harness.settle()
            val connected = SessionState.Connected(Candidate.Scope.TAILNET, 0, caughtUp = true)
            assertEquals(listOf(SessionState.Connecting, connected), harness.states)
        }

    @Test
    fun `a silent reconnect with no link up within its grace says Connecting`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            delay(STABLE_CONNECTION_MS)
            val closedAt = harness.now
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            harness.daemon.accept()
            assertEquals(SessionState.Connecting, harness.stateWhen { it == SessionState.Connecting })
            assertEquals(SILENT_GRACE_MS, harness.now - closedAt)
        }

    @Test
    fun `a 1000 for shutting down is not the hourly close, and Connecting shows at once`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            delay(STABLE_CONNECTION_MS)
            first.close(NORMAL_CLOSURE, "shutting down")
            // The daemon takes the socket and never answers the handshake: no race outcome speaks for it.
            harness.daemon.accept()
            harness.settle()
            assertEquals(SessionState.Connecting, harness.session.state.value)
        }

    @Test
    fun `a 1000 within 5 s of hello_ack waits the backoff, so a daemon that closes at once is never raced hot`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            val closedAt = harness.now
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            harness.daemon.accept()
            assertTrue(harness.now - closedAt in 500L..1_000L, "waited ${harness.now - closedAt} ms")
        }

    @Test
    fun `past its bound of races the session is suspended, and resume races again`() =
        runTest {
            val harness = Harness(this)
            harness.daemon.refusal = { TransportException.Unreachable(IOException("no route")) }
            harness.open()
            withTimeout(2 * 24 * 60 * 60_000L) { harness.session.state.first { it == SessionState.Suspended } }
            assertEquals(MAX_RACES, harness.daemon.dials)
            assertTrue(harness.diagnostics().any { it.kind == DiagnosticKind.BOUND })
            delay(10 * 60_000L)
            assertEquals(MAX_RACES, harness.daemon.dials)
            harness.session.resume()
            harness.settle()
            assertEquals(MAX_RACES + 1, harness.daemon.dials)
        }

    @Test
    fun `a lost keepalive reconnects as Connecting, unlike the hourly close`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            first.expect<ClientEvent.Ping>()
            first.expect<ClientEvent.Ping>()
            harness.daemon.accept().connect()
            harness.settle()
            val connected = SessionState.Connected(Candidate.Scope.TAILNET, 0, caughtUp = true)
            assertEquals(listOf(SessionState.Connecting, connected, SessionState.Connecting, connected), harness.states)
        }

    @ParameterizedTest
    @ValueSource(ints = [1002, 1003, 1008, 1009, 1011])
    fun `a daemon's close for a fault reconnects after the backoff's wait`(code: Int) =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            val closedAt = harness.now
            first.close(code, "")
            harness.daemon.accept()
            assertTrue(harness.now - closedAt in 500L..1_000L, "waited ${harness.now - closedAt} ms")
        }

    @Test
    fun `the daemon's 1002 on a live connection is a protocol error in the diagnostics, never an ending`() =
        runTest {
            val harness = Harness(this)
            harness.connect().close(PROTOCOL_ERROR, "mobile protocol error")
            harness.settle()

            assertEquals(DiagnosticKind.PROTOCOL_ERROR, harness.diagnostics().last().kind)
            assertFalse(harness.session.state.value is SessionState.Ended)
        }

    @Test
    fun `a live connection the daemon closes with 1002 reads Connecting through the backoff's wait`() =
        runTest {
            val harness = Harness(this)
            harness.connect().close(PROTOCOL_ERROR, "mobile protocol error")
            // Inside the backoff's first wait, 500 ms at the least: no stale Connected (design section 13.5).
            delay(100)

            assertEquals(SessionState.Connecting, harness.session.state.value)
            assertEquals(DiagnosticKind.PROTOCOL_ERROR, harness.diagnostics().last().kind)
        }

    @Test
    fun `a handshake the daemon closes with 1002 is a protocol error in the diagnostics, after the race's failure`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            harness.daemon.accept().refuseHandshake(PROTOCOL_ERROR, "mobile protocol error")
            harness.settle()

            val kinds = harness.diagnostics().map { it.kind }
            assertEquals(listOf(DiagnosticKind.RACE_FAILED, DiagnosticKind.PROTOCOL_ERROR), kinds)
            assertFalse(harness.session.state.value is SessionState.Ended)
        }

    @Test
    fun `a close for any other fault is no protocol error`() =
        runTest {
            val harness = Harness(this)
            harness.connect().close(1011, "")
            harness.settle()

            assertEquals(DiagnosticKind.CLOSED, harness.diagnostics().last().kind)
        }

    @Test
    fun `a connection that completes hello resets the backoff, which grew while hello never completed`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            harness.daemon.accept().close(1011, "")
            var closedAt = harness.now
            harness.daemon.accept().close(1011, "")
            val firstWait = harness.now - closedAt
            closedAt = harness.now
            val third = harness.daemon.accept()
            val secondWait = harness.now - closedAt
            third.connect()
            harness.settle()
            third.close(1011, "")
            closedAt = harness.now
            harness.daemon.accept()
            val thirdWait = harness.now - closedAt
            assertTrue(firstWait in 500L..1_000L, "waited $firstWait ms first")
            assertTrue(secondWait in 1_000L..2_000L, "waited $secondWait ms second")
            assertTrue(thirdWait in 500L..1_000L, "waited $thirdWait ms after hello completed")
        }

    @Test
    fun `4001 ends the session as replaced, and it races no more`() =
        runTest {
            val harness = Harness(this)
            harness.connect().close(4001, "connection replaced")
            assertEquals(SessionState.Replaced, harness.stateWhen { it is SessionState.Ended })
            delay(10 * 60_000L)
            assertEquals(1, harness.daemon.dials)
        }

    @Test
    fun `4003 while live ends the session as revoked`() =
        runTest {
            val harness = Harness(this)
            harness.connect().close(4003, "device revoked")
            assertEquals(SessionState.Revoked, harness.stateWhen { it is SessionState.Ended })
        }

    @Test
    fun `4004 at a reconnect's hello ends the session as revoked`() =
        runTest {
            val harness = Harness(this)
            harness.connect().close(NORMAL_CLOSURE, "Noise session lifetime reached")
            val second = harness.daemon.accept()
            second.handshake()
            second.next()
            second.close(4004, "device not paired")
            assertEquals(SessionState.Revoked, harness.stateWhen { it is SessionState.Ended })
        }

    @Test
    fun `4004 inside the handshake ends the session as revoked too`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            val connection = harness.daemon.accept()
            connection.close(4004, "device not paired")
            assertEquals(SessionState.Revoked, harness.stateWhen { it is SessionState.Ended })
        }

    @Test
    fun `a certificate that is not pinned is the daemon's identity changing, never retried`() =
        runTest {
            val harness = Harness(this)
            harness.daemon.refusal = { TransportException.PinMismatch(IOException("another leaf")) }
            harness.open()
            assertEquals(SessionState.IdentityChanged, harness.stateWhen { it is SessionState.Ended })
            delay(10 * 60_000L)
            assertEquals(1, harness.daemon.dials)
        }

    @Test
    fun `a Noise key that does not authenticate is the daemon's identity changing`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            harness.daemon.accept().answerAsAnotherDaemon()
            assertEquals(SessionState.IdentityChanged, harness.stateWhen { it is SessionState.Ended })
        }

    @Test
    fun `suspend closes the socket and races no more until resume`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.suspend()
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertEquals(SessionState.Suspended, harness.session.state.value)
            delay(10 * 60_000L)
            assertEquals(1, harness.daemon.dials)
            harness.session.resume()
            harness.daemon.accept().connect()
            assertTrue(harness.stateWhen { it is SessionState.Connected } is SessionState.Connected)
        }

    @Test
    fun `close ends the session and its events`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.close()
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertEquals(SessionState.Closed, harness.session.state.value)
            harness.session.resume()
            delay(60_000)
            assertEquals(1, harness.daemon.dials)
            assertTrue(harness.eventsCollected.isCompleted)
        }

    @Test
    fun `close returns only once a request writing the store has returned, in its caller's coroutine`() =
        runTest {
            val harness = Harness(this)
            harness.connect()
            val written = CompletableDeferred<Unit>()
            harness.store.enqueueGate = written
            val sending = launch { harness.session.send(msg("m1")) }
            harness.settle()
            val closing = launch { harness.session.close() }
            harness.settle()
            assertEquals(SessionState.Closed, harness.session.state.value)
            assertFalse(closing.isCompleted, "close returned while a request was writing the store")
            written.complete(Unit)
            harness.settle()
            assertTrue(closing.isCompleted, "close did not return once the request had")
            assertTrue(sending.isCompleted && !sending.isCancelled, "the request did not return")
            assertEquals(listOf("m1"), harness.store.items.map { it.clientMsgId })
        }

    @Test
    fun `an ended session refuses markRead, retry and remove, and leaves the store as another session wrote it`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors =
                EMPTY_CURSORS.copy(lastServerSeq = 20uL, readUpToSeq = 10uL, announcedUpToSeq = 20uL)
            harness.connect(HELLO_ACK.copy(historyHeadSeq = 20uL, readUpToSeq = 10uL))
            harness.session.close()
            harness.store.cursors = harness.store.cursors.copy(readUpToSeq = 20uL)
            assertThrows<IllegalStateException> { harness.session.markRead(15uL) }
            assertThrows<IllegalStateException> { harness.session.retry(msg("m1"), "m2") }
            assertThrows<IllegalStateException> { harness.session.remove("m1") }
            assertEquals(20uL, harness.store.cursors.readUpToSeq)
        }

    @Test
    fun `an announcer that closes the session ends it as Closed, from inside its own call`() =
        runTest {
            val harness = Harness(this)
            harness.announcer.during = { harness.session.close() }
            val connection = harness.connect()
            connection.send(row(1uL))
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertEquals(SessionState.Closed, harness.stateWhen { it is SessionState.Ended })
            delay(10 * 60_000L)
            assertEquals(1, harness.daemon.dials)
        }

    @Test
    fun `a store that fails ends the session as Failed, with the link closed and the events ended`() =
        runTest {
            val harness = Harness(this)
            val fault = IOException("disk full")
            harness.store.cursorFault = fault
            val connection = harness.connect()
            connection.send(row(1uL))
            assertFailedWith(fault, harness.stateWhen { it is SessionState.Ended })
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            harness.eventsCollected.join()
            assertTrue(harness.diagnostics().any { it.kind == DiagnosticKind.FAILED })
        }

    @Test
    fun `a CancellationException from the announcer is a fault, not the session's own stop`() =
        runTest {
            val harness = Harness(this)
            val fault = CancellationException("the app's scope went")
            harness.announcer.during = { throw fault }
            val connection = harness.connect()
            connection.send(row(1uL))
            assertFailedWith(fault, harness.stateWhen { it is SessionState.Ended })
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            delay(10 * 60_000L)
            assertEquals(1, harness.daemon.dials)
        }

    @Test
    fun `cancelling the session's scope ends it as Closed and ends its events`() =
        runTest {
            val harness = Harness(this)
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
            harness.open(scope = scope)
            val connection = harness.daemon.accept()
            connection.connect()
            harness.settle()
            scope.cancel()
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            harness.eventsCollected.join()
            assertEquals(SessionState.Closed, harness.session.state.value)
        }

    @Test
    fun `a suspended session whose scope is cancelled ends as Closed too`() =
        runTest {
            val harness = Harness(this)
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
            harness.open(scope = scope)
            harness.daemon.accept().connect()
            harness.session.suspend()
            scope.cancel()
            harness.eventsCollected.join()
            assertEquals(SessionState.Closed, harness.session.state.value)
        }

    @Test
    fun `without a network the session waits for one, and a change of network races again`() =
        runTest {
            val harness = Harness(this)
            harness.network.value = NetworkFacts.NONE
            harness.open()
            assertEquals(SessionState.WaitingForNetwork, harness.stateWhen { it == SessionState.WaitingForNetwork })
            delay(60_000)
            assertEquals(0, harness.daemon.dials)
            harness.network.value = ONLINE
            val first = harness.daemon.accept()
            first.connect()
            harness.network.value = ONLINE.copy(defaultNetwork = 2L)
            assertEquals(NORMAL_CLOSURE, first.phoneClosed().code)
            harness.daemon.accept().connect()
            assertFalse(harness.session.state.value is SessionState.Ended)
        }

    @Test
    fun `every candidate failing for 30 s with a network is Can't reach, and the session keeps racing`() =
        runTest {
            val harness = Harness(this)
            harness.daemon.refusal = { TransportException.Unreachable(IOException("no route")) }
            harness.open()
            assertEquals(SessionState.CannotReach, harness.stateWhen { it == SessionState.CannotReach })
            assertTrue(harness.now in 30_000L..31_000L, "Can't reach at ${harness.now} ms")
            val dials = harness.daemon.dials
            delay(5 * 60_000L)
            assertTrue(harness.daemon.dials > dials)
        }

    /**
     * [state] is Failed with [fault]: the same exception, or the copy coroutines' stack-trace recovery
     * makes of it as it crosses a coroutine, which holds the original as its cause.
     */
    private fun assertFailedWith(
        fault: Exception,
        state: SessionState,
    ) {
        val cause = assertInstanceOf<SessionState.Failed>(state).cause
        assertTrue(cause === fault || cause.cause === fault, "failed with $cause, not $fault")
    }
}
