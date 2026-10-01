package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ack follows the announcement, never the write (tla/specs/mobile_push, PUSH-2): an ack tells the
 * daemon this phone told its owner, and the daemon then sends no push. It holds across sessions, goes to
 * every new socket again, and goes within 1 s of the announcement on a page too. A row on screen is read
 * at once, and the read frontier moves forward only, never past the head (design sections 7 and 10).
 */
class AnnouncementTest {
    @Test
    fun `a row persisted and not announced is never acked, nor any row after it, until it is read`() =
        runTest {
            val harness = Harness(this)
            harness.announcer.answer =
                { if (it.serverSeq == 1uL) Announcement.NOT_ANNOUNCED else Announcement.NOTIFIED }
            val connection = harness.connect()
            connection.send(row(1uL))
            connection.send(row(2uL))
            assertEquals(ClientEvent.Ping, connection.next().event)
            assertEquals(listOf(1uL, 2uL), harness.announcer.rows.map { it.serverSeq })
            assertEquals(2uL, harness.store.cursors.lastServerSeq)
            harness.session.markRead(2uL)
            assertEquals(ClientEvent.Ack(2uL), connection.expect<ClientEvent.Ack>())
            assertEquals(ClientEvent.ReadState(PROFILE, 2uL), connection.expect<ClientEvent.ReadState>())
        }

    @Test
    fun `the ack waits for the announcement, and goes within 1 s of it`() =
        runTest {
            val harness = Harness(this)
            val hold = CompletableDeferred<Unit>()
            harness.announcer.hold = hold
            val connection = harness.connect()
            connection.send(row(1uL))
            assertNull(withTimeoutOrNull(5_000) { connection.receive() })
            assertEquals(1, harness.announcer.rows.size)
            assertEquals(0uL, harness.store.cursors.lastServerSeq)
            val announcedAt = harness.now
            hold.complete(Unit)
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            assertTrue(harness.now - announcedAt <= 1_000)
            assertEquals(1uL, harness.store.cursors.lastServerSeq)
        }

    @Test
    fun `an own message the owner knows of is acked like an announced row`() =
        runTest {
            val harness = Harness(this)
            harness.announcer.answer = { Announcement.ALREADY_KNOWN }
            val connection = harness.connect()
            connection.send(row(1uL, role = "user", clientMsgId = "m1"))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
        }

    @Test
    fun `a row that lands on screen sends read_state at once`() =
        runTest {
            val harness = Harness(this)
            harness.announcer.answer = { Announcement.ON_SCREEN }
            val connection = harness.connect()
            val sentAt = harness.now
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            assertEquals(ClientEvent.ReadState(PROFILE, 1uL), connection.expect<ClientEvent.ReadState>())
            assertEquals(sentAt, harness.now)
            assertEquals(1uL, harness.store.cursors.readUpToSeq)
        }

    @Test
    fun `the read frontier never moves back, and never past the head`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            (1uL..5uL).forEach { connection.send(row(it)) }
            repeat(5) { connection.expect<ClientEvent.Ack>() }
            connection.send(ServerEvent.ReadState(PROFILE, 4uL))
            connection.send(ServerEvent.ReadState(PROFILE, 2uL))
            harness.settle()
            harness.session.markRead(3uL)
            connection.send(row(6uL))
            assertEquals(ClientEvent.Ack(6uL), connection.expect<ClientEvent.Ack>())
            assertEquals(4uL, harness.store.cursors.readUpToSeq)
            assertEquals(
                listOf(SessionEvent.ReadFrontier(4uL)),
                harness.events.filterIsInstance<SessionEvent.ReadFrontier>(),
            )
            harness.session.markRead(100uL)
            assertEquals(ClientEvent.ReadState(PROFILE, 6uL), connection.expect<ClientEvent.ReadState>())
            assertEquals(6uL, harness.store.cursors.readUpToSeq)
        }

    @Test
    fun `a frontier read on another device lets a held ack go`() =
        runTest {
            val harness = Harness(this)
            harness.announcer.answer = { Announcement.NOT_ANNOUNCED }
            val connection = harness.connect()
            connection.send(row(1uL))
            connection.send(ServerEvent.ReadState(PROFILE, 1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            harness.settle()
            assertTrue(SessionEvent.ReadFrontier(1uL) in harness.events)
        }

    @Test
    fun `a row not announced before a restart is still never acked after it, until it is read`() =
        runTest {
            val harness = Harness(this)
            harness.announcer.answer =
                { if (it.serverSeq == 1uL) Announcement.NOT_ANNOUNCED else Announcement.NOTIFIED }
            val first = harness.connect()
            first.send(row(1uL))
            harness.settle()
            assertEquals(EMPTY_CURSORS.copy(lastServerSeq = 1uL, lastUnannouncedSeq = 1uL), harness.store.cursors)
            harness.session.close()
            harness.open()
            val second = harness.daemon.accept()
            second.connect(HELLO_ACK.copy(historyHeadSeq = 1uL))
            second.send(row(2uL))
            assertEquals(ClientEvent.Ping, second.next().event)
            harness.session.markRead(1uL)
            assertEquals(ClientEvent.Ack(2uL), second.expect<ClientEvent.Ack>())
            assertEquals(ClientEvent.ReadState(PROFILE, 1uL), second.expect<ClientEvent.ReadState>())
        }

    @Test
    fun `once a read reaches the row not announced, every row announced after it is acked within 1 s`() =
        runTest {
            val harness = Harness(this)
            harness.announcer.answer =
                { if (it.serverSeq == 1uL) Announcement.NOT_ANNOUNCED else Announcement.NOTIFIED }
            val connection = harness.connect()
            connection.send(row(1uL))
            connection.send(row(2uL))
            connection.send(ServerEvent.ReadState(PROFILE, 1uL))
            assertEquals(ClientEvent.Ack(2uL), connection.expect<ClientEvent.Ack>())
            connection.send(row(3uL))
            assertEquals(ClientEvent.Ack(3uL), connection.expect<ClientEvent.Ack>())
            assertTrue(harness.now - harness.announcer.announcedAt.getValue(3uL) <= 1_000)
        }

    @Test
    fun `a hello_ack whose read frontier reaches the row not announced releases the ack on that connection`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors =
                EMPTY_CURSORS.copy(lastServerSeq = 2uL, announcedUpToSeq = 0uL, lastUnannouncedSeq = 1uL)
            val connection = harness.connect(HELLO_ACK.copy(historyHeadSeq = 2uL, readUpToSeq = 1uL))
            assertEquals(ClientEvent.Ack(2uL), connection.expect<ClientEvent.Ack>())
            connection.send(row(3uL))
            assertEquals(ClientEvent.Ack(3uL), connection.expect<ClientEvent.Ack>())
        }

    @Test
    fun `every ack sent is kept with its row and its wait, apart from the diagnostics`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(row(1uL))
            connection.send(row(2uL))
            assertEquals(listOf(1uL, 2uL), connection.acksThrough(2uL))
            val kept = harness.session.acks.value
            assertEquals(listOf("ack 1, 0 ms after its row", "ack 2, 0 ms after its row"), kept.map { it.detail })
            assertTrue(kept.all { it.kind == DiagnosticKind.ACK })
            assertTrue(harness.diagnostics().none { it.kind == DiagnosticKind.ACK })
        }

    @Test
    fun `each connection is told the ack again, since a socket's acked cursor goes with it`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            first.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), first.expect<ClientEvent.Ack>())
            delay(STABLE_CONNECTION_MS)
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            val second = harness.daemon.accept()
            second.connect(HELLO_ACK.copy(historyHeadSeq = 1uL))
            assertEquals(ClientEvent.Ack(1uL), second.expect<ClientEvent.Ack>())
        }

    @Test
    fun `on a page each row's ack goes before the next row is announced, within 1 s of its own`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors =
                EMPTY_CURSORS.copy(lastServerSeq = 10uL, readUpToSeq = 10uL, announcedUpToSeq = 10uL)
            harness.announcer.delayMs = 20
            val connection = harness.connect(HELLO_ACK.copy(historyHeadSeq = 210uL, readUpToSeq = 10uL))
            assertEquals(ClientEvent.Ack(10uL), connection.expect<ClientEvent.Ack>())
            connection.expect<ClientEvent.HistoryPull>()
            val ackedAt = mutableMapOf<ULong, Long>()
            val acks = launch { repeat(200) { ackedAt[connection.expect<ClientEvent.Ack>().serverSeq] = harness.now } }
            connection.sendRun(page((11uL..210uL).map { message(it) }, nextAfterSeq = 210uL, head = 210uL), parts = 2)
            acks.join()
            (11uL..210uL).forEach { seq ->
                val acked = ackedAt.filterKeys { it >= seq }.values.min()
                val held = acked - harness.announcer.announcedAt.getValue(seq)
                assertTrue(held in 0L..1_000L, "row $seq's ack went $held ms after its announcement")
            }
        }

    @Test
    fun `the announcer may call the session back, marking the row it shows read`() =
        runTest {
            val harness = Harness(this)
            harness.announcer.answer = { Announcement.ON_SCREEN }
            harness.announcer.during = { harness.session.markRead(it.serverSeq) }
            val connection = harness.connect()
            connection.send(row(1uL))
            connection.send(row(2uL))
            assertEquals(ClientEvent.ReadState(PROFILE, 1uL), connection.next().event)
            assertEquals(ClientEvent.Ack(1uL), connection.next().event)
            assertEquals(ClientEvent.ReadState(PROFILE, 2uL), connection.next().event)
            assertEquals(ClientEvent.Ack(2uL), connection.next().event)
        }

    @Test
    fun `an ack that waited 500 ms or more is noted with how long, and a prompt one is not`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            harness.store.cursorWriteMs = 600
            connection.send(row(2uL))
            assertEquals(ClientEvent.Ack(2uL), connection.expect<ClientEvent.Ack>())
            val noted = harness.diagnostics().filter { it.kind == DiagnosticKind.ACK }
            assertEquals(listOf("ack 2, 600 ms after its row"), noted.map { it.detail })
        }
}
