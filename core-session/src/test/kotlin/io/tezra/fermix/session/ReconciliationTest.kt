package io.tezra.fermix.session

import io.tezra.fermix.protocol.ActiveTurn
import io.tezra.fermix.protocol.CandidateScope
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.protocol.RequestOutcome
import io.tezra.fermix.protocol.RequestState
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.TurnEffect.BubbleOpened
import io.tezra.fermix.session.TurnEffect.BubbleSealed
import io.tezra.fermix.session.TurnEffect.BubbleText
import io.tezra.fermix.session.TurnEffect.CardRemoved
import io.tezra.fermix.session.TurnEffect.CardShown
import io.tezra.fermix.session.TurnEffect.TurnEnded
import io.tezra.fermix.session.TurnOutcome.Completed
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import io.tezra.fermix.protocol.Candidate as WireCandidate

/**
 * Reconnect reconciliation (design section 8.2, review R2, R12, R16): between `hello_ack` and the
 * outbox, the ack this socket is owed, the turns `active_turns` no longer lists end, the cards
 * `pending_approvals` no longer lists close, `request_status` asks after the outbox and the shown turns,
 * the mutation feed is pulled to its end, and only then does the outbox resend.
 */
class ReconciliationTest {
    private val cursors =
        StoredCursors(
            lastServerSeq = 10uL,
            readUpToSeq = 10uL,
            lastMutationSeq = 2uL,
            announcedUpToSeq = 10uL,
            lastUnannouncedSeq = 0uL,
        )
    private val firstAck = HELLO_ACK.copy(historyHeadSeq = 10uL, readUpToSeq = 10uL, mutationHeadSeq = 2uL)
    private val approval =
        ServerEvent.Approval("a1", "sandbox", "Allow reading ~/Documents?", "tok", 60, "/confirm tok", "/deny tok")

    /** A first connection that sent m1, saw its turn start and an approval card, then closed at the hour. */
    private suspend fun Harness.awayWithTurnAndCard() {
        store.cursors = cursors
        val first = connect(firstAck)
        assertEquals(ClientEvent.Ack(10uL), first.expect<ClientEvent.Ack>())
        session.send(msg("m1"))
        first.expect<ClientEvent.Msg>()
        first.send(ServerEvent.Accepted("m1", duplicate = false))
        first.send(ServerEvent.TurnStarted(PROFILE, "turn-m1", "m1"))
        first.send(approval)
        first.send(row(11uL))
        first.expect<ClientEvent.Ack>()
        first.close(NORMAL_CLOSURE, LIFETIME_REASON)
    }

    @Test
    fun `the second connection reconciles in order, and only then resends the outbox`() =
        runTest {
            val harness = Harness(this)
            harness.awayWithTurnAndCard()
            harness.session.send(msg("m2"))
            val second = harness.daemon.accept()
            second.handshake()
            second.next()
            second.send(
                HELLO_ACK.copy(
                    historyHeadSeq = 12uL,
                    readUpToSeq = 10uL,
                    activeTurns = emptyList(),
                    pendingApprovals = emptyList(),
                    mutationHeadSeq = 5uL,
                ),
            )
            assertEquals(ClientEvent.Ack(11uL), second.expect<ClientEvent.Ack>())
            assertEquals(ClientEvent.RequestStatus(listOf("m2", "m1")), second.expect<ClientEvent.RequestStatus>())
            val m1 = RequestOutcome("m1", RequestState.COMPLETED, turnId = "turn-m1", resultServerSeq = 12uL)
            second.send(ServerEvent.RequestStatusPage(listOf(m1)))
            val pull = second.expect<ClientEvent.MutationsPull>()
            assertEquals(ClientEvent.MutationsPull(PROFILE, afterMutationSeq = 2uL, limit = 200), pull)
            val edited = MutationRow(serverSeq = 4uL, mutationSeq = 5uL, content = "transcribed")
            second.send(ServerEvent.MutationsPage(listOf(edited)))
            val history = second.expect<ClientEvent.HistoryPull>()
            assertEquals(ClientEvent.HistoryPull(PROFILE, afterSeq = 11uL, limit = 200), history)
            assertEquals(msg("m2"), second.expect<ClientEvent.Msg>())
            harness.settle()
            assertEquals(listOf(edited), harness.store.mutations)
            assertEquals(5uL, harness.store.cursors.lastMutationSeq)
            assertTrue(SessionEvent.RequestStatus(m1) in harness.events)
            assertTrue(SessionEvent.ApprovalClosedWhileAway("a1") in harness.events)
            assertTrue(TurnEnded("turn-m1", TurnOutcome.Over) in harness.turnEffects())
        }

    @Test
    fun `a turn still active and a card still pending stay as they are`() =
        runTest {
            val harness = Harness(this)
            harness.awayWithTurnAndCard()
            val second = harness.daemon.accept()
            val ack =
                HELLO_ACK.copy(
                    historyHeadSeq = 11uL,
                    readUpToSeq = 10uL,
                    activeTurns = listOf(ActiveTurn("turn-m1", "m1")),
                    pendingApprovals = listOf("a1"),
                    mutationHeadSeq = 2uL,
                )
            second.connect(ack)
            assertEquals(ClientEvent.Ack(11uL), second.expect<ClientEvent.Ack>())
            assertEquals(ClientEvent.RequestStatus(listOf("m1")), second.expect<ClientEvent.RequestStatus>())
            second.send(ServerEvent.RequestStatusPage(listOf(RequestOutcome("m1", RequestState.RUNNING))))
            second.send(row(12uL))
            assertEquals(ClientEvent.Ack(12uL), second.expect<ClientEvent.Ack>())
            assertTrue(harness.events.none { it is SessionEvent.ApprovalClosedWhileAway })
            assertTrue(harness.turnEffects().none { it is TurnEnded })
        }

    @Test
    fun `a hello_ack without active_turns or pending_approvals leaves the turns and cards as they are`() =
        runTest {
            val harness = Harness(this)
            harness.awayWithTurnAndCard()
            val second = harness.daemon.accept()
            second.connect(HELLO_ACK.copy(historyHeadSeq = 11uL, readUpToSeq = 10uL, mutationHeadSeq = 2uL))
            assertEquals(ClientEvent.Ack(11uL), second.expect<ClientEvent.Ack>())
            assertEquals(ClientEvent.RequestStatus(listOf("m1")), second.expect<ClientEvent.RequestStatus>())
            second.send(ServerEvent.RequestStatusPage(listOf(RequestOutcome("m1", RequestState.RUNNING))))
            second.send(row(12uL))
            assertEquals(ClientEvent.Ack(12uL), second.expect<ClientEvent.Ack>())
            assertTrue(harness.events.none { it is SessionEvent.ApprovalClosedWhileAway })
            assertTrue(harness.turnEffects().none { it is TurnEnded })
        }

    @Test
    fun `a shown turn's request that failed while away is told, and the outbox it left is not touched`() =
        runTest {
            val harness = Harness(this)
            harness.awayWithTurnAndCard()
            val second = harness.daemon.accept()
            second.connect(HELLO_ACK.copy(historyHeadSeq = 11uL, readUpToSeq = 10uL, activeTurns = emptyList()))
            second.expect<ClientEvent.Ack>()
            assertEquals(ClientEvent.RequestStatus(listOf("m1")), second.expect<ClientEvent.RequestStatus>())
            val failed = RequestOutcome("m1", RequestState.FAILED, turnId = "turn-m1", error = "turn_failed")
            second.send(ServerEvent.RequestStatusPage(listOf(failed)))
            second.send(row(12uL))
            assertEquals(ClientEvent.Ack(12uL), second.expect<ClientEvent.Ack>())
            assertTrue(SessionEvent.RequestStatus(failed) in harness.events)
            assertTrue(harness.session.state.value is SessionState.Connected)
        }

    @Test
    fun `a request still queued at a reconnect shows its card again, and its turn streams into it`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            harness.session.send(msg("m1"))
            first.expect<ClientEvent.Msg>()
            first.send(ServerEvent.Accepted("m1", duplicate = false))
            harness.settle()
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            val second = harness.daemon.accept()
            second.connect(HELLO_ACK.copy(activeTurns = emptyList()))
            assertEquals(ClientEvent.RequestStatus(listOf("m1")), second.expect<ClientEvent.RequestStatus>())
            second.send(ServerEvent.RequestStatusPage(listOf(RequestOutcome("m1", RequestState.ACCEPTED))))
            second.send(ServerEvent.TurnStarted(PROFILE, "turn-m1", "m1"))
            second.send(ServerEvent.TextDelta("turn-m1", "Hel"))
            harness.settle()
            val expected =
                listOf(
                    CardShown("turn-m1"),
                    CardRemoved("turn-m1"),
                    TurnEnded("turn-m1", TurnOutcome.Over),
                    CardShown("turn-m1"),
                    BubbleOpened("turn-m1", 1, true),
                    BubbleText("turn-m1", 1, "Hel"),
                )
            assertEquals(expected, harness.turnEffects())
        }

    @Test
    fun `hello_ack's routes replace the candidates the session races, the last successful first`() =
        runTest {
            val harness = Harness(this)
            val routes =
                listOf(
                    WireCandidate("192.168.1.20", "en0", CandidateScope.LAN),
                    WireCandidate("100.101.102.103", "utun4", CandidateScope.TAILNET),
                )
            val first = harness.connect(HELLO_ACK.copy(candidates = routes))
            assertTrue(SessionEvent.Candidates(listOf(LAN, TAILNET)) in harness.events)
            val unreachable = TransportException.Unreachable(IOException("no route"))
            harness.daemon.refusal = { if (it == TAILNET) unreachable else null }
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            val second = withTimeout(60_000) { harness.daemon.accept() }
            assertEquals(LAN, second.candidate)
            assertEquals(listOf(TAILNET, TAILNET, LAN), harness.daemon.dialed)
        }

    @Test
    fun `mutations_gone rebuilds the cache and pulls the newest page`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors = cursors
            val connection = harness.connect(firstAck.copy(historyHeadSeq = 300uL, mutationHeadSeq = 9_000uL))
            assertEquals(ClientEvent.Ack(10uL), connection.expect<ClientEvent.Ack>())
            connection.expect<ClientEvent.MutationsPull>()
            connection.send(ServerEvent.Error(ServerEvent.Error.MUTATIONS_GONE, "the feed no longer reaches 2"))
            val newest = connection.expect<ClientEvent.HistoryPull>()
            assertEquals(ClientEvent.HistoryPull(PROFILE, beforeSeq = 301uL, limit = 50), newest)
            harness.settle()
            assertEquals(listOf(9_000uL), harness.store.rebuilds)
            assertEquals(0uL, harness.store.cursors.lastServerSeq)
            assertEquals(10uL, harness.store.cursors.announcedUpToSeq)
            assertTrue(SessionEvent.RebuildProfileCache(9_000uL) in harness.events)
        }

    @Test
    fun `an empty cache adopts the mutation head and pulls no mutations`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect(HELLO_ACK.copy(historyHeadSeq = 5uL, mutationHeadSeq = 40uL))
            val newest = connection.expect<ClientEvent.HistoryPull>()
            assertEquals(ClientEvent.HistoryPull(PROFILE, beforeSeq = 6uL, limit = 50), newest)
            assertEquals(40uL, harness.store.cursors.lastMutationSeq)
        }

    @Test
    fun `request_status asks after at most 32 requests at a time, each batch once answered`() =
        runTest {
            val harness = Harness(this)
            val ids = (1..40).map { "m$it" }
            harness.store.items += ids.map { OutboxItem(msg(it)) }
            val connection = harness.connect()
            assertEquals(ClientEvent.RequestStatus(ids.take(32)), connection.expect<ClientEvent.RequestStatus>())
            connection.send(ServerEvent.RequestStatusPage(emptyList()))
            assertEquals(ClientEvent.RequestStatus(ids.drop(32)), connection.expect<ClientEvent.RequestStatus>())
            connection.send(ServerEvent.RequestStatusPage(emptyList()))
            assertEquals(ids, List(40) { connection.expect<ClientEvent.Msg>().clientMsgId })
        }

    @Test
    fun `a request the daemon ran while the phone was away leaves the outbox, and a failed one stays failed`() =
        runTest {
            val harness = Harness(this)
            harness.store.items += listOf(OutboxItem(msg("ran")), OutboxItem(msg("failed")), OutboxItem(msg("new")))
            val connection = harness.connect()
            connection.expect<ClientEvent.RequestStatus>()
            val outcomes =
                listOf(
                    RequestOutcome("ran", RequestState.COMPLETED, resultServerSeq = 3uL),
                    RequestOutcome("failed", RequestState.FAILED, error = "request_failed"),
                )
            connection.send(ServerEvent.RequestStatusPage(outcomes))
            assertEquals(msg("new"), connection.expect<ClientEvent.Msg>())
            val failed = RequestFailure("request_failed", "failed while this phone was away")
            assertEquals(listOf(OutboxItem(msg("failed"), failed), OutboxItem(msg("new"))), harness.store.items)
        }

    @Test
    fun `the ack and a frontier read while away reach the daemon first`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors = cursors.copy(readUpToSeq = 10uL)
            val connection = harness.connect(firstAck.copy(readUpToSeq = 7uL))
            assertEquals(ClientEvent.Ack(10uL), connection.expect<ClientEvent.Ack>())
            assertEquals(ClientEvent.ReadState(PROFILE, 10uL), connection.expect<ClientEvent.ReadState>())
        }

    @Test
    fun `a request request_status finds queued shows its card, and a bare text_done takes the card's place`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            harness.session.send(msg("m1"))
            val connection = harness.daemon.accept()
            connection.connect(HELLO_ACK.copy(activeTurns = emptyList()))
            assertEquals(ClientEvent.RequestStatus(listOf("m1")), connection.expect<ClientEvent.RequestStatus>())
            connection.send(ServerEvent.RequestStatusPage(listOf(RequestOutcome("m1", RequestState.ACCEPTED))))
            connection.send(ServerEvent.TextDone("turn-m1", 1uL, "Done."))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            val expected =
                listOf(CardShown("turn-m1"), BubbleOpened("turn-m1", 1, true), BubbleSealed("turn-m1", 1, 1uL))
            assertEquals(expected, harness.turnEffects())
            assertTrue(harness.store.items.isEmpty())
        }

    @Test
    fun `without active_turns, a shown turn whose request completed while away ends`() =
        runTest {
            val completed = RequestOutcome("m1", RequestState.COMPLETED, turnId = "turn-m1", resultServerSeq = 12uL)
            val effects = Harness(this).turnEffectsAfterStatus(completed)
            assertEquals(listOf(CardShown("turn-m1"), CardRemoved("turn-m1"), TurnEnded("turn-m1", Completed)), effects)
        }

    @Test
    fun `without active_turns, a shown turn whose request failed while away ends with its error`() =
        runTest {
            val failed = RequestOutcome("m1", RequestState.FAILED, turnId = "turn-m1", error = "turn_failed")
            val effects = Harness(this).turnEffectsAfterStatus(failed)
            val error = TurnOutcome.Failed("turn_failed", "failed while this phone was away")
            assertEquals(listOf(CardShown("turn-m1"), CardRemoved("turn-m1"), TurnEnded("turn-m1", error)), effects)
        }

    @Test
    fun `a mutations_page whose next does not move on closes 1002 and keeps the mutation cursor`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors = cursors
            val connection = harness.connect(firstAck.copy(mutationHeadSeq = 9_000uL))
            assertEquals(ClientEvent.Ack(10uL), connection.expect<ClientEvent.Ack>())
            connection.expect<ClientEvent.MutationsPull>()
            val stuck = ServerEvent.MutationsPage(listOf(MutationRow(serverSeq = 4uL, mutationSeq = 3uL)), next = 2uL)
            connection.send(stuck)
            assertEquals(PROTOCOL_ERROR, connection.phoneClosed().code)
            assertEquals(2uL, harness.store.cursors.lastMutationSeq)
            assertTrue(harness.store.mutations.isEmpty())
            val refused = harness.diagnostics().single { it.kind == DiagnosticKind.PROTOCOL_ERROR }
            assertTrue("mutations_page" in refused.detail, refused.detail)
        }

    @Test
    fun `a mutation feed that never ends is pulled 100 pages deep, and the reconciliation goes on`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors = cursors
            harness.store.items += OutboxItem(msg("a"))
            val connection = harness.connect(firstAck.copy(historyHeadSeq = 12uL, mutationHeadSeq = 9_000uL))
            connection.expect<ClientEvent.Ack>()
            connection.expect<ClientEvent.RequestStatus>()
            connection.send(ServerEvent.RequestStatusPage(emptyList()))
            repeat(100) { index ->
                val after = 2uL + index.toULong()
                val pull = ClientEvent.MutationsPull(PROFILE, after, 200)
                assertEquals(pull, connection.expect<ClientEvent.MutationsPull>())
                connection.send(ServerEvent.MutationsPage(emptyList(), next = after + 1uL))
            }
            assertEquals(ClientEvent.HistoryPull(PROFILE, afterSeq = 10uL, limit = 200), connection.next().event)
            assertEquals(msg("a"), connection.expect<ClientEvent.Msg>())
            assertEquals(102uL, harness.store.cursors.lastMutationSeq)
            assertTrue(harness.diagnostics().any { it.kind == DiagnosticKind.BOUND && "102" in it.detail })
        }

    @Test
    fun `a refused request_status or mutations_pull is skipped, and the outbox still drains on the same connection`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors = cursors
            harness.store.items += OutboxItem(msg("a"))
            val connection = harness.connect(firstAck.copy(historyHeadSeq = 12uL, mutationHeadSeq = 5uL))
            assertEquals(ClientEvent.Ack(10uL), connection.expect<ClientEvent.Ack>())
            assertEquals(ClientEvent.RequestStatus(listOf("a")), connection.expect<ClientEvent.RequestStatus>())
            val unsupported = ServerEvent.Error("unsupported_event", "request_status is not taken here")
            connection.send(unsupported)
            val pull = ClientEvent.MutationsPull(PROFILE, afterMutationSeq = 2uL, limit = 200)
            assertEquals(pull, connection.expect<ClientEvent.MutationsPull>())
            val invalid = ServerEvent.Error("invalid_field", "after_mutation_seq")
            connection.send(invalid)
            val history = ClientEvent.HistoryPull(PROFILE, afterSeq = 10uL, limit = 200)
            assertEquals(history, connection.expect<ClientEvent.HistoryPull>())
            assertEquals(msg("a"), connection.expect<ClientEvent.Msg>())
            harness.settle()
            assertEquals(2uL, harness.store.cursors.lastMutationSeq)
            val refusals = harness.events.filterIsInstance<SessionEvent.Refused>()
            assertEquals(listOf(SessionEvent.Refused(unsupported), SessionEvent.Refused(invalid)), refusals)
            assertTrue(harness.session.state.value is SessionState.Connected)
        }

    @Test
    fun `a rebuild keeps the ack frontier, so the rows below it are not judged again and the rows past it are`() =
        runTest {
            val harness = Harness(this)
            harness.store.cursors = cursors.copy(lastServerSeq = 12uL, readUpToSeq = 5uL, lastUnannouncedSeq = 11uL)
            harness.announcer.answer = {
                if (it.serverSeq == 9uL || it.serverSeq == 11uL) Announcement.NOT_ANNOUNCED else Announcement.NOTIFIED
            }
            val ack = firstAck.copy(historyHeadSeq = 12uL, readUpToSeq = 5uL, mutationHeadSeq = 9_000uL)
            val connection = harness.connect(ack)
            assertEquals(ClientEvent.Ack(10uL), connection.expect<ClientEvent.Ack>())
            connection.expect<ClientEvent.MutationsPull>()
            connection.send(ServerEvent.Error(ServerEvent.Error.MUTATIONS_GONE, "the feed no longer reaches 2"))
            val newest = ClientEvent.HistoryPull(PROFILE, beforeSeq = 13uL, limit = 50)
            assertEquals(newest, connection.expect<ClientEvent.HistoryPull>())
            val rows = (8uL..12uL).map { message(it) }
            connection.send(page(rows, nextAfterSeq = 12uL, head = 12uL, prevBeforeSeq = 8uL))
            connection.send(row(13uL))
            harness.settle()
            assertEquals((8uL..13uL).toList(), harness.announcer.rows.map { it.serverSeq })
            val held =
                cursors.copy(
                    lastServerSeq = 13uL,
                    readUpToSeq = 5uL,
                    lastMutationSeq = 9_000uL,
                    lastUnannouncedSeq = 11uL,
                )
            assertEquals(held, harness.store.cursors)
            connection.send(ServerEvent.ReadState(PROFILE, 11uL))
            assertEquals(ClientEvent.Ack(13uL), connection.expect<ClientEvent.Ack>())
        }

    @Test
    fun `an announcer slower than the answer timeout is the phone's own time, not the daemon's`() =
        runTest {
            val harness = Harness(this)
            harness.store.items += OutboxItem(msg("a"))
            harness.announcer.delayMs = ANSWER_TIMEOUT_MS + 1_000
            val connection = harness.connect()
            assertEquals(ClientEvent.RequestStatus(listOf("a")), connection.expect<ClientEvent.RequestStatus>())
            connection.send(row(1uL))
            delay(1_000)
            connection.send(ServerEvent.RequestStatusPage(emptyList()))
            assertEquals(ClientEvent.Ack(1uL), connection.nextBesidesPing().event)
            assertEquals(msg("a"), connection.nextBesidesPing().event)
            assertTrue(harness.diagnostics().none { it.kind == DiagnosticKind.PROTOCOL_ERROR })
        }

    /**
     * A first connection that showed m1's turn, then a second whose `hello_ack` leaves out `active_turns`
     * and whose `request_status_page` says [outcome]: the turn effects of both, the card shown first.
     */
    private suspend fun Harness.turnEffectsAfterStatus(outcome: RequestOutcome): List<TurnEffect> {
        awayWithTurnAndCard()
        val second = daemon.accept()
        second.connect(HELLO_ACK.copy(historyHeadSeq = 11uL, readUpToSeq = 10uL, mutationHeadSeq = 2uL))
        assertEquals(ClientEvent.Ack(11uL), second.expect<ClientEvent.Ack>())
        assertEquals(ClientEvent.RequestStatus(listOf("m1")), second.expect<ClientEvent.RequestStatus>())
        second.send(ServerEvent.RequestStatusPage(listOf(outcome)))
        second.send(row(12uL))
        assertEquals(ClientEvent.Ack(12uL), second.expect<ClientEvent.Ack>())
        return turnEffects()
    }
}
