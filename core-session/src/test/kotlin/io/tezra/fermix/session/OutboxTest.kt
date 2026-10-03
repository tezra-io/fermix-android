package io.tezra.fermix.session

import io.tezra.fermix.protocol.ActiveTurn
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.ToolPhase
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
 * reconciliation, a request persisted while the drain reads included, against a store that suspends. A
 * `msg` made while a turn runs waits in the outbox until the last turn ends (design section 13.6, "queued ·
 * sends after this reply", released by `turn_done`), and an item is marked written before its frame first
 * goes, so one never written can still be removed (design section 13.6, review R16).
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
            assertEquals(listOf(OutboxItem(msg("m1"), written = true)), harness.store.items)
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
            assertEquals(listOf(OutboxItem(msg("m1"), failure, written = true)), harness.store.items)
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
            assertEquals(listOf(OutboxItem(msg("m2", retryOf = "m1"), written = true)), harness.store.items)
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
    fun `a failed request is removed from the outbox, and one written to a socket is not`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            harness.session.send(msg("m2"))
            connection.send(ServerEvent.Error("client_message_conflict", "content differs", clientMsgId = "m1"))
            harness.settle()
            assertTrue(harness.session.remove("m1"))
            assertFalse(harness.session.remove("m2"))
            assertFalse(harness.session.remove("m3"))
            assertEquals(listOf(OutboxItem(msg("m2"), written = true)), harness.store.items)
        }

    @Test
    fun `a request made while away is never written, so it can be removed, and nothing is sent for it`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            harness.session.send(msg("m1"))
            harness.session.send(msg("m2"))
            assertEquals(listOf(OutboxItem(msg("m1")), OutboxItem(msg("m2"))), harness.store.items)
            assertTrue(harness.session.remove("m1"))
            val connection = harness.daemon.accept()
            connection.connect()
            assertEquals(ClientEvent.RequestStatus(listOf("m2")), connection.expect<ClientEvent.RequestStatus>())
            connection.send(emptyStatus)
            assertEquals(msg("m2"), connection.expect<ClientEvent.Msg>())
            assertEquals(listOf(OutboxItem(msg("m2"), written = true)), harness.store.items)
        }

    @Test
    fun `a request removed while its write is marked is not sent`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val marked = CompletableDeferred<Unit>()
            harness.store.writtenGate = marked
            val sending = launch { harness.session.send(msg("m1")) }
            harness.settle()
            assertTrue(harness.session.remove("m1"))
            marked.complete(Unit)
            sending.join()
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.next().event)
            assertTrue(harness.store.items.isEmpty())
        }

    @Test
    fun `a message made while a turn runs waits unwritten, and goes when the last turn ends`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            connection.expect<ClientEvent.Msg>()
            connection.send(ServerEvent.Accepted("m1", duplicate = false))
            connection.send(ServerEvent.TurnStarted(PROFILE, "turn-cron", "cron-1"))
            harness.settle()
            harness.session.send(msg("m2"))
            harness.settle()
            assertEquals(listOf(OutboxItem(msg("m2"))), harness.store.items)
            connection.send(ServerEvent.TurnDone("turn-m1"))
            connection.send(row(1uL))
            // The ack is the next frame: m2 waits for the cron turn too.
            assertEquals(ClientEvent.Ack(1uL), connection.next().event)
            assertTrue(harness.session.remove("m2"))
            harness.session.send(msg("m3"))
            connection.send(ServerEvent.TurnError("turn-cron", "turn_failed", "no"))
            assertEquals(msg("m3"), connection.expect<ClientEvent.Msg>())
            assertEquals(listOf(OutboxItem(msg("m3"), written = true)), harness.store.items)
        }

    @Test
    fun `a command goes while a turn runs, since stop and the model cannot wait for it`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            connection.expect<ClientEvent.Msg>()
            connection.send(ServerEvent.Accepted("m1", duplicate = false))
            harness.settle()
            val stop = ClientEvent.Command("c1", PROFILE, "stop", "")
            harness.session.send(stop)
            assertEquals(stop, connection.expect<ClientEvent.Command>())
        }

    @Test
    fun `a message waiting for a turn the reconnect finds still running waits on, and goes when it ends`() =
        runTest {
            val harness = Harness(this)
            harness.store.items += OutboxItem(msg("m2"))
            harness.open()
            val connection = harness.daemon.accept()
            val active = HELLO_ACK.copy(activeTurns = listOf(ActiveTurn("turn-m1", "m1")))
            connection.connect(active)
            assertEquals(ClientEvent.RequestStatus(listOf("m2")), connection.expect<ClientEvent.RequestStatus>())
            connection.send(emptyStatus)
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.next().event)
            connection.send(ServerEvent.TurnDone("turn-m1"))
            assertEquals(msg("m2"), connection.expect<ClientEvent.Msg>())
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
    fun `stop is sent once when connected and never queued, so it never stops a later turn`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            assertTrue(harness.session.stop("s1"))
            assertEquals(ClientEvent.Command("s1", PROFILE, "stop", null), connection.expect<ClientEvent.Command>())
            assertTrue(harness.store.items.isEmpty())
            harness.session.suspend()
            assertFalse(harness.session.stop("s2"))
            assertTrue(harness.store.items.isEmpty())
        }

    @Test
    fun `a stop opens no card and its answer ends its own turn, so a message made after it goes`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            connection.expect<ClientEvent.Msg>()
            connection.send(ServerEvent.Accepted("m1", duplicate = false))
            harness.settle()
            assertTrue(harness.session.stop("s1"))
            connection.expect<ClientEvent.Command>()
            // The daemon answers a command inline, with a bare text_done and no turn_done after it.
            connection.send(ServerEvent.Accepted("s1", duplicate = false))
            connection.send(ServerEvent.TextDone("turn-s1", 1uL, "Stopped Fermix execution"))
            connection.send(ServerEvent.TurnError("turn-m1", "cancelled", "Stopped"))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            harness.session.send(msg("m2"))
            assertEquals(msg("m2"), connection.expect<ClientEvent.Msg>())
            val effects =
                listOf(
                    TurnEffect.CardShown("turn-m1"),
                    TurnEffect.BubbleOpened("turn-s1", 1, fromCard = false),
                    TurnEffect.BubbleSealed("turn-s1", 1, 1uL),
                    TurnEffect.TurnEnded("turn-s1", TurnOutcome.Completed),
                    TurnEffect.CardRemoved("turn-m1"),
                    TurnEffect.TurnEnded("turn-m1", TurnOutcome.Stopped),
                )
            assertEquals(effects, harness.turnEffects())
        }

    @Test
    fun `a command's turn the daemon runs shows as any turn, from its turn_started to its turn_done`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val compact = ClientEvent.Command("c1", PROFILE, "compact", null)
            harness.session.send(compact)
            connection.expect<ClientEvent.Command>()
            connection.send(ServerEvent.Accepted("c1", duplicate = false))
            connection.send(ServerEvent.TurnStarted(PROFILE, "turn-c1", "c1"))
            connection.send(ServerEvent.TextDone("turn-c1", 1uL, "Compacted."))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            harness.session.send(msg("m2"))
            harness.settle()
            assertEquals(listOf(OutboxItem(msg("m2"))), harness.store.items)
            connection.send(ServerEvent.TurnDone("turn-c1"))
            assertEquals(msg("m2"), connection.expect<ClientEvent.Msg>())
            val effects =
                listOf(
                    TurnEffect.CardShown("turn-c1"),
                    TurnEffect.BubbleOpened("turn-c1", 1, fromCard = true),
                    TurnEffect.BubbleSealed("turn-c1", 1, 1uL),
                    TurnEffect.TurnEnded("turn-c1", TurnOutcome.Completed),
                )
            assertEquals(effects, harness.turnEffects())
        }

    @Test
    fun `each turn step says whether the daemon speaks on its card, a heading or a running tool`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("m1"))
            connection.expect<ClientEvent.Msg>()
            connection.send(ServerEvent.Accepted("m1", duplicate = false))
            connection.send(ServerEvent.ToolEvent("turn-m1", "shell", ToolPhase.START, status = null))
            connection.send(ServerEvent.ToolEvent("turn-m1", "shell", ToolPhase.STOP, status = "ok"))
            harness.settle()
            val steps = harness.events.filterIsInstance<SessionEvent.Turn>()
            assertEquals(listOf(false, true, false), steps.map { it.daemonSpeaking })
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
            assertEquals(listOf(OutboxItem(msg("m1"), written = true)), harness.store.items)
        }

    private fun List<ClientEvent.Msg>.ids(): List<String> = map { it.clientMsgId }
}
