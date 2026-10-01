package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cursor (PROTOCOL.md "One timeline" and "History pages", design section 7): a live row at the
 * cursor plus one is applied, one at or below it dropped, a gap pulled; the pulls after `hello_ack`,
 * each page holding only what its pull asked for; the newest page of an empty cache and the older pages
 * behind it, which are never announced; and rows out in order.
 */
class TimelineTest {
    @Test
    fun `a row after the cursor is applied, and one at or below it is dropped`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            connection.send(row(1uL))
            connection.send(row(2uL))
            assertEquals(ClientEvent.Ack(2uL), connection.expect<ClientEvent.Ack>())
            assertEquals(listOf(1uL, 2uL), harness.announcer.rows.map { it.serverSeq })
            assertEquals(2uL, harness.store.cursors.lastServerSeq)
        }

    @Test
    fun `a gap is not shown but pulled from the cursor`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(row(1uL))
            connection.expect<ClientEvent.Ack>()
            connection.send(row(3uL))
            assertEquals(forward(1uL), connection.expect<ClientEvent.HistoryPull>())
            assertEquals(listOf(1uL), harness.announcer.rows.map { it.serverSeq })
            connection.send(page(listOf(message(2uL), message(3uL)), nextAfterSeq = 3uL, head = 3uL))
            assertEquals(listOf(2uL, 3uL), connection.acksThrough(3uL))
            assertEquals(listOf(1uL, 2uL, 3uL), harness.announcer.rows.map { it.serverSeq })
        }

    @Test
    fun `after hello_ack the session pulls forward until the page reaches the head, and then it has caught up`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors =
                EMPTY_CURSORS.copy(lastServerSeq = 10uL, readUpToSeq = 10uL, announcedUpToSeq = 10uL)
            val connection = harness.connect(HELLO_ACK.copy(historyHeadSeq = 250uL, readUpToSeq = 10uL))
            assertEquals(ClientEvent.Ack(10uL), connection.expect<ClientEvent.Ack>())
            assertEquals(forward(10uL), connection.expect<ClientEvent.HistoryPull>())
            connection.sendRun(page(rows(11uL..210uL), nextAfterSeq = 210uL, head = 250uL), parts = 2)
            assertEquals((11uL..210uL).toList(), connection.acksThrough(210uL))
            assertEquals(forward(210uL), connection.expect<ClientEvent.HistoryPull>())
            assertEquals(updating, harness.session.state.value)
            connection.sendRun(page(rows(211uL..250uL), nextAfterSeq = 250uL, head = 250uL), parts = 2)
            assertEquals((211uL..250uL).toList(), connection.acksThrough(250uL))
            connection.send(row(251uL))
            assertEquals(ClientEvent.Ack(251uL), connection.expect<ClientEvent.Ack>())
            assertEquals((11uL..251uL).toList(), harness.announcer.rows.map { it.serverSeq })
            harness.settle()
            assertEquals(updating.copy(caughtUp = true), harness.session.state.value)
        }

    @Test
    fun `a page holding a row its pull did not ask for closes 1002, so a pull never repeats`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors =
                EMPTY_CURSORS.copy(lastServerSeq = 10uL, readUpToSeq = 10uL, announcedUpToSeq = 10uL)
            val connection = harness.connect(HELLO_ACK.copy(historyHeadSeq = 250uL, readUpToSeq = 10uL))
            connection.expect<ClientEvent.Ack>()
            assertEquals(forward(10uL), connection.expect<ClientEvent.HistoryPull>())
            connection.send(page(listOf(message(5uL)), nextAfterSeq = 5uL, head = 250uL))
            assertEquals(PROTOCOL_ERROR, connection.phoneClosed().code)
            assertTrue(harness.diagnostics().any { it.kind == DiagnosticKind.PROTOCOL_ERROR && "row 5" in it.detail })
            assertTrue(harness.announcer.rows.isEmpty())
        }

    @Test
    fun `an empty cache pulls the newest page backward, and loadOlder backfills behind it, unannounced`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect(HELLO_ACK.copy(historyHeadSeq = 120uL))
            val newest = connection.expect<ClientEvent.HistoryPull>()
            assertEquals(ClientEvent.HistoryPull(PROFILE, beforeSeq = 121uL, limit = 50), newest)
            connection.sendRun(
                page(rows(71uL..120uL), nextAfterSeq = 120uL, head = 120uL, prevBeforeSeq = 71uL),
                parts = 2,
            )
            assertEquals((71uL..120uL).toList(), connection.acksThrough(120uL))
            harness.settle()
            assertTrue(SessionEvent.OlderLoaded(emptyList(), 71uL) in harness.events)
            assertTrue(harness.session.loadOlder(71uL))
            assertFalse(harness.session.loadOlder(71uL))
            val older = connection.expect<ClientEvent.HistoryPull>()
            assertEquals(ClientEvent.HistoryPull(PROFILE, beforeSeq = 71uL, limit = 50), older)
            connection.sendRun(
                page(rows(21uL..70uL), nextAfterSeq = 70uL, head = 120uL, prevBeforeSeq = 21uL),
                parts = 2,
            )
            connection.send(row(121uL))
            assertEquals(ClientEvent.Ack(121uL), connection.expect<ClientEvent.Ack>())
            assertEquals((71uL..121uL).toList(), harness.announcer.rows.map { it.serverSeq })
            val backfill = (21uL..70uL).map { TimelineRow.Message(message(it)) }
            assertTrue(SessionEvent.OlderLoaded(backfill, 21uL) in harness.events)
            assertEquals(121uL, harness.store.cursors.lastServerSeq)
        }

    @Test
    fun `a page that comes as an event_part run is joined and applied`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect(HELLO_ACK.copy(historyHeadSeq = 3uL))
            connection.expect<ClientEvent.HistoryPull>()
            connection.sendRun(page(rows(1uL..3uL), nextAfterSeq = 3uL, head = 3uL, prevBeforeSeq = 1uL), parts = 3)
            assertEquals(listOf(1uL, 2uL, 3uL), connection.acksThrough(3uL))
            assertEquals(listOf(1uL, 2uL, 3uL), harness.announcer.rows.map { it.serverSeq })
        }

    @Test
    fun `a connection with nothing to pull has caught up once its outbox has drained`() =
        runTest {
            val harness = Harness(this)
            harness.connect()
            assertEquals(updating.copy(caughtUp = true), harness.session.state.value)
        }

    @Test
    fun `a reply's text_done seals its bubble and is the row at its seq`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(ServerEvent.TextDone("turn-m1", 1uL, "Done."))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            val reply = TimelineRow.Reply(1uL, "turn-m1", "Done.", truncated = false, route = null)
            assertEquals(listOf<TimelineRow>(reply), harness.announcer.rows)
            assertTrue(harness.turnEffects().any { it is TurnEffect.BubbleSealed && it.serverSeq == 1uL })
        }

    /** A connection up over the tailnet whose history pulls have not reached the head: `Updating…`. */
    private val updating = SessionState.Connected(Candidate.Scope.TAILNET, 0, caughtUp = false)

    private fun rows(seqs: ULongRange) = seqs.map { message(it) }

    private fun forward(afterSeq: ULong) = ClientEvent.HistoryPull(PROFILE, afterSeq = afterSeq, limit = 200)
}
