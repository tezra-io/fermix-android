package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows

/**
 * The outbox (design section 13.6, PROTOCOL.md "Delivery and failure behavior"): persisted before it is
 * sent, cleared by `accepted`, kept failed by `error{client_msg_id}`, run again under a new id whether it
 * failed before `accepted` or after it, and resent in order once per connection after the
 * reconciliation, a request persisted while the drain reads included, against a store that suspends.
 */
class OutboxTest {
    private val emptyStatus = ServerEvent.RequestStatusPage(emptyList())

    @Test
    fun `a request is persisted, then sent`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            assertEquals(msg("m1"), connection.expect<ClientEvent.Msg>())
            assertEquals(listOf(OutboxItem(msg("m1"))), harness.store.items)
        }

    @Test
    fun `a request its version's rules refuse is neither persisted nor sent`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val refused = runCatching { harness.session.send(msg("m1", text = "")) }.exceptionOrNull()
            assertInstanceOf<ProtocolException>(refused)
            assertTrue(harness.store.items.isEmpty())
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
        }

    @Test
    fun `accepted clears the item and shows the card, and a duplicate clears it with no card`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            harness.session.send(msg("m2"))
            connection.send(ServerEvent.Accepted("m1", duplicate = false))
            connection.send(ServerEvent.Accepted("m2", duplicate = true, serverSeq = 9uL))
            harness.settle()
            assertTrue(harness.store.items.isEmpty())
            val accepted = harness.events.filterIsInstance<SessionEvent.Accepted>()
            assertEquals(listOf(SessionEvent.Accepted("m1", false), SessionEvent.Accepted("m2", true)), accepted)
            assertEquals(listOf<TurnEffect>(TurnEffect.CardShown("turn-m1")), harness.turnEffects())
        }

    @Test
    fun `an error naming the request marks it failed and keeps it`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            connection.send(ServerEvent.Error("client_message_conflict", "content differs", clientMsgId = "m1"))
            harness.settle()
            val failure = RequestFailure("client_message_conflict", "content differs")
            assertEquals(listOf(OutboxItem(msg("m1"), failure)), harness.store.items)
            assertTrue(SessionEvent.RequestFailed("m1", failure, inOutbox = true) in harness.events)
        }

    @Test
    fun `a request refused before accepted is run again as a new request that names it in retry_of`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            connection.expect<ClientEvent.Msg>()
            connection.send(ServerEvent.Error("request_failed", "the model did not answer", clientMsgId = "m1"))
            harness.settle()
            harness.session.retry(msg("m1"), "m2")
            assertEquals(msg("m2", retryOf = "m1"), connection.expect<ClientEvent.Msg>())
            assertEquals(listOf(OutboxItem(msg("m2", retryOf = "m1"))), harness.store.items)
        }

    @Test
    fun `a run that failed after accepted is run again as a new request that names it in retry_of`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            connection.expect<ClientEvent.Msg>()
            connection.send(ServerEvent.Accepted("m1", duplicate = false))
            connection.send(ServerEvent.Error("request_failed", "the model did not answer", clientMsgId = "m1"))
            harness.settle()
            assertTrue(harness.store.items.isEmpty())
            val failure = RequestFailure("request_failed", "the model did not answer")
            assertTrue(SessionEvent.RequestFailed("m1", failure, inOutbox = false) in harness.events)
            harness.session.retry(msg("m1"), "m2")
            assertEquals(msg("m2", retryOf = "m1"), connection.expect<ClientEvent.Msg>())
        }

    @Test
    fun `a turn that failed is run again as a new request that names it in retry_of`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            connection.expect<ClientEvent.Msg>()
            connection.send(ServerEvent.Accepted("m1", duplicate = false))
            connection.send(ServerEvent.TurnStarted(PROFILE, "turn-m1", "m1"))
            connection.send(ServerEvent.TurnError("turn-m1", "turn_failed", "The model did not answer"))
            harness.settle()
            val failed = TurnOutcome.Failed("turn_failed", "The model did not answer")
            assertTrue(TurnEffect.TurnEnded("turn-m1", failed) in harness.turnEffects())
            harness.session.retry(msg("m1"), "m2")
            assertEquals(msg("m2", retryOf = "m1"), connection.expect<ClientEvent.Msg>())
        }

    @Test
    fun `a request the outbox still sends is never run again, nor under its own id`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            harness.session.send(msg("m1"))
            assertThrows<IllegalArgumentException> { harness.session.retry(msg("m1"), "m2") }
            assertThrows<IllegalArgumentException> { harness.session.retry(msg("m1"), "m1") }
            assertEquals(listOf(OutboxItem(msg("m1"))), harness.store.items)
        }

    @Test
    fun `the outbox resends in order after the reconciliation, once per connection, never a failed item`() =
        runTest {
            val harness = Harness(this)
            val failed = RequestFailure("request_failed", "no")
            harness.store.items += listOf(OutboxItem(msg("a")), OutboxItem(msg("b"), failed), OutboxItem(msg("c")))
            val first = harness.connect()
            assertEquals(ClientEvent.RequestStatus(listOf("a", "c")), first.expect<ClientEvent.RequestStatus>())
            first.send(emptyStatus)
            assertEquals(
                listOf("a", "c"),
                listOf(first.expect<ClientEvent.Msg>(), first.expect<ClientEvent.Msg>()).ids(),
            )
            first.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), first.expect<ClientEvent.Ack>())
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            val second = harness.daemon.accept()
            second.connect()
            assertEquals(ClientEvent.Ack(1uL), second.expect<ClientEvent.Ack>())
            second.expect<ClientEvent.RequestStatus>()
            second.send(emptyStatus)
            assertEquals(
                listOf("a", "c"),
                listOf(second.expect<ClientEvent.Msg>(), second.expect<ClientEvent.Msg>()).ids(),
            )
            second.send(row(2uL))
            assertEquals(ClientEvent.Ack(2uL), second.expect<ClientEvent.Ack>())
        }

    @Test
    fun `a request persisted while the drain reads the outbox is sent after what the drain read`() =
        runTest {
            val harness = Harness(this)
            harness.store.items += OutboxItem(msg("a"))
            harness.open()
            val connection = harness.daemon.accept()
            connection.connect()
            connection.expect<ClientEvent.RequestStatus>()
            val read = CompletableDeferred<Unit>()
            harness.store.outboxGate = read
            connection.send(emptyStatus)
            harness.settle()
            harness.session.send(msg("m1"))
            read.complete(Unit)
            val sent = listOf(connection.expect<ClientEvent.Msg>(), connection.expect<ClientEvent.Msg>())
            assertEquals(listOf("a", "m1"), sent.ids())
        }

    @Test
    fun `a request the drain sent is not sent again when its own send resumes on the same connection`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            delay(STABLE_CONNECTION_MS)
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            val second = harness.daemon.accept()
            val written = CompletableDeferred<Unit>()
            harness.store.enqueueGate = written
            val sending = launch { harness.session.send(msg("m1")) }
            harness.settle()
            second.connect()
            assertEquals(ClientEvent.RequestStatus(listOf("m1")), second.expect<ClientEvent.RequestStatus>())
            second.send(emptyStatus)
            assertEquals(msg("m1"), second.expect<ClientEvent.Msg>())
            written.complete(Unit)
            sending.join()
            second.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), second.next().event)
        }

    @Test
    fun `a request the drain sent and the daemon accepted is not sent again when its own send resumes`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            delay(STABLE_CONNECTION_MS)
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            val second = harness.daemon.accept()
            val written = CompletableDeferred<Unit>()
            harness.store.enqueueGate = written
            val sending = launch { harness.session.send(msg("m1")) }
            harness.settle()
            second.connect()
            assertEquals(ClientEvent.RequestStatus(listOf("m1")), second.expect<ClientEvent.RequestStatus>())
            second.send(emptyStatus)
            assertEquals(msg("m1"), second.expect<ClientEvent.Msg>())
            second.send(ServerEvent.Accepted("m1", duplicate = false))
            harness.settle()
            written.complete(Unit)
            sending.join()
            second.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), second.next().event)
        }

    @Test
    fun `a failed request is removed from the outbox, and one the outbox still sends is not`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            harness.session.send(msg("m2"))
            connection.send(ServerEvent.Error("client_message_conflict", "content differs", clientMsgId = "m1"))
            harness.settle()
            harness.session.remove("m1")
            assertThrows<IllegalArgumentException> { harness.session.remove("m2") }
            assertThrows<IllegalArgumentException> { harness.session.remove("m3") }
            assertEquals(listOf(OutboxItem(msg("m2"))), harness.store.items)
        }

    @Test
    fun `a request made while away waits for the reconciliation`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            harness.session.send(msg("m1"))
            val connection = harness.daemon.accept()
            connection.connect()
            assertEquals(ClientEvent.RequestStatus(listOf("m1")), connection.expect<ClientEvent.RequestStatus>())
            connection.send(emptyStatus)
            assertEquals(msg("m1"), connection.expect<ClientEvent.Msg>())
        }

    @Test
    fun `cancel is sent once when connected and never queued`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            assertTrue(harness.session.cancel("m1"))
            assertEquals(ClientEvent.Cancel(PROFILE, "m1"), connection.expect<ClientEvent.Cancel>())
            harness.session.suspend()
            assertFalse(harness.session.cancel("m2"))
        }

    @Test
    fun `request_backlog_full is surfaced as the refusal it is`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            val full = ServerEvent.Error("request_backlog_full", "32 requests already waiting")
            connection.send(full)
            harness.settle()
            assertTrue(SessionEvent.Refused(full) in harness.events)
            assertEquals(listOf(OutboxItem(msg("m1"))), harness.store.items)
        }

    private fun List<ClientEvent.Msg>.ids(): List<String> = map { it.clientMsgId }
}
