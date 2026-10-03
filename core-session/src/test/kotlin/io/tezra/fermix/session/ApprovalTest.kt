package io.tezra.fermix.session

import io.tezra.fermix.protocol.ApprovalOutcome
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.RequestOutcome
import io.tezra.fermix.protocol.RequestState
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/** Frames a reconnect's test reads at most for its `request_status`: the hello and the reconciliation's first asks. */
private const val STATUS_LOOKS = 8

/**
 * Approvals (PROTOCOL.md "Approvals", design section 8.4) over the vendored card (server_events.jsonl's
 * `approval` and `approval_resolved`) and its vendored answer (client_events.jsonl's `command`): the app hears
 * the card without its token or routes, the owner's answer goes as the route's `command`, an outbox item that
 * runs no turn, and the daemon's row of the answer is kept without its words.
 */
class ApprovalTest {
    private val card = vendoredServer("approval") as ServerEvent.Approval
    private val vendoredAnswer =
        (1..2).map { vendoredClient("command", nth = it) as ClientEvent.Command }.single { it.name == "confirm" }

    private suspend fun Harness.cardShown(): DaemonConnection {
        val connection = connect()
        connection.send(card)
        settle()
        return connection
    }

    @Test
    fun `the app hears the card, never its token or routes`() =
        runTest {
            val harness = Harness(this)
            harness.cardShown()
            val shown = SessionEvent.Approval(card.approvalId, "sandbox", "Allow reading ~/Documents?", null, 37)
            assertEquals(listOf(shown), harness.events.filterIsInstance<SessionEvent.Approval>())
            assertTrue(harness.events.none { card.token in it.toString() })
        }

    @Test
    fun `approve sends the approve route as the vendored command, from the outbox`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.cardShown()
            val answer = assertInstanceOf<ApprovalAnswer.Sent>(harness.session.answerApproval(card.approvalId, true))
            val sent = connection.expect<ClientEvent.Command>()
            assertEquals(vendoredAnswer.copy(clientMsgId = answer.clientMsgId), sent)
            assertTrue(isApprovalAnswer(answer.clientMsgId))
            assertTrue(answer.clientMsgId.startsWith("$APPROVAL_ANSWER_PREFIX${card.approvalId}:"))
            assertEquals(listOf(sent), harness.store.items.map { it.request })
            harness.settle()
            assertTrue(SessionEvent.ApprovalAnswered(card.approvalId, true, answer.clientMsgId) in harness.events)
        }

    @Test
    fun `deny sends the deny route`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.cardShown()
            harness.session.answerApproval(card.approvalId, false)
            val sent = connection.expect<ClientEvent.Command>()
            assertEquals("deny" to "opaque-token", sent.name to sent.args)
        }

    @Test
    fun `a route of more words keeps them as its args`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val soul = card.copy(approvalId = "soul-1", kind = "soul", approveCommand = "/soul apply T")
            connection.send(soul)
            harness.settle()
            harness.session.answerApproval("soul-1", true)
            val sent = connection.expect<ClientEvent.Command>()
            assertEquals("soul" to "apply T", sent.name to sent.args)
        }

    @Test
    fun `a card answered once is not answered again until its answer is refused`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.cardShown()
            val first = assertInstanceOf<ApprovalAnswer.Sent>(harness.session.answerApproval(card.approvalId, true))
            connection.expect<ClientEvent.Command>()
            assertEquals(
                ApprovalAnswer.Answered(first.clientMsgId),
                harness.session.answerApproval(card.approvalId, false),
            )
            connection.send(ServerEvent.Error("request_failed", "no", clientMsgId = first.clientMsgId))
            harness.settle()
            assertTrue(harness.store.items.isEmpty(), "the refused answer left the outbox, its token with it")
            assertTrue(harness.turnEffects().none { it is TurnEffect.TurnEnded }, "an answer runs no turn")
            assertInstanceOf<ApprovalAnswer.Sent>(harness.session.answerApproval(card.approvalId, false))
        }

    @Test
    fun `a resolved card is not shown any more, and the app hears how it ended`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.cardShown()
            connection.send(vendoredServer("approval_resolved"))
            harness.settle()
            assertTrue(SessionEvent.ApprovalResolved(card.approvalId, ApprovalOutcome.APPROVED) in harness.events)
            assertEquals(ApprovalAnswer.NotShown, harness.session.answerApproval(card.approvalId, true))
            assertEquals(ApprovalAnswer.NotShown, harness.session.answerApproval("never-shown", true))
        }

    @Test
    fun `a card past its ttl_s on the session's clock is expired, and nothing goes`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.cardShown()
            val others = mutableListOf<ClientEvent>()
            backgroundScope.launch { connection.answerPings(backgroundScope, afterMs = 10, others) }
            delay(card.ttlS * 1_000L + 1)
            assertEquals(ApprovalAnswer.Expired, harness.session.answerApproval(card.approvalId, true))
            harness.settle()
            assertTrue(others.none { it is ClientEvent.Command })
        }

    @Test
    fun `a replayed card replaces the one shown and keeps its answer`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.cardShown()
            val first = assertInstanceOf<ApprovalAnswer.Sent>(harness.session.answerApproval(card.approvalId, true))
            connection.expect<ClientEvent.Command>()
            connection.send(card.copy(ttlS = 12))
            harness.settle()
            assertEquals(2, harness.events.count { it is SessionEvent.Approval })
            assertEquals(
                ApprovalAnswer.Answered(first.clientMsgId),
                harness.session.answerApproval(card.approvalId, true),
            )
        }

    /** The card answered, then the connection gone before its `accepted`, and a new one asking after it. */
    private suspend fun Harness.answeredThenAway(): Pair<String, DaemonConnection> {
        val first = cardShown()
        val answer = assertInstanceOf<ApprovalAnswer.Sent>(session.answerApproval(card.approvalId, true))
        first.expect<ClientEvent.Command>()
        first.close(NORMAL_CLOSURE, LIFETIME_REASON)
        settle()
        val second = daemon.accept()
        second.connect(HELLO_ACK)
        var asked: ClientEvent? = null
        repeat(STATUS_LOOKS) { if (asked !is ClientEvent.RequestStatus) asked = second.nextBesidesPing().event }
        assertInstanceOf<ClientEvent.RequestStatus>(asked)
        return answer.clientMsgId to second
    }

    @Test
    fun `an answer still running after a reconnect opens no card of its own`() =
        runTest {
            val harness = Harness(this)
            val (id, connection) = harness.answeredThenAway()
            val before = harness.turnEffects().size
            connection.send(ServerEvent.RequestStatusPage(listOf(RequestOutcome(id, RequestState.RUNNING))))
            harness.settle()
            assertEquals(emptyList<TurnEffect>(), harness.turnEffects().drop(before))
            assertTrue(harness.store.items.isEmpty(), "the daemon has the answer: it left the outbox")
        }

    @Test
    fun `an answer that failed while away ends no turn, and its card may be answered again`() =
        runTest {
            val harness = Harness(this)
            val (id, connection) = harness.answeredThenAway()
            val before = harness.turnEffects().size
            val failed = RequestOutcome(id, RequestState.FAILED, error = "request_failed")
            connection.send(ServerEvent.RequestStatusPage(listOf(failed)))
            harness.settle()
            assertEquals(emptyList<TurnEffect>(), harness.turnEffects().drop(before))
            assertTrue(harness.events.any { it is SessionEvent.RequestFailed && it.clientMsgId == id && !it.inOutbox })
            assertInstanceOf<ApprovalAnswer.Sent>(harness.session.answerApproval(card.approvalId, false))
        }

    @Test
    fun `the daemon's row of an answer is kept without its words, live or paged`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.cardShown()
            val answer = assertInstanceOf<ApprovalAnswer.Sent>(harness.session.answerApproval(card.approvalId, true))
            connection.expect<ClientEvent.Command>()
            connection.send(ServerEvent.Accepted(answer.clientMsgId, duplicate = false))
            val live = row(1uL, role = "user", clientMsgId = answer.clientMsgId).copy(text = "/confirm opaque-token")
            connection.send(live)
            connection.expect<ClientEvent.Ack>()
            connection.send(row(3uL))
            val paged = message(2uL, role = "user", clientMsgId = answer.clientMsgId)
            connection.expect<ClientEvent.HistoryPull>()
            val rows = listOf(paged.copy(content = "/confirm opaque-token"), message(3uL))
            connection.send(page(rows, nextAfterSeq = 3uL, head = 3uL))
            harness.settle()
            val words = harness.announcer.rows.map { (it as TimelineRow.Message).message.content }
            assertEquals(listOf("", "", "Row 3"), words)
            assertTrue(harness.announcer.rows.none { card.token in it.toString() })
        }

    @Test
    fun `a route that is no command closes the connection as a protocol error`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(card.copy(approveCommand = "confirm opaque-token"))
            assertEquals(PROTOCOL_ERROR, connection.phoneClosed().code)
            assertTrue(harness.diagnostics().none { card.token in it.detail })
        }
}
