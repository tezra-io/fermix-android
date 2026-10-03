package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ApprovalOutcome
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.APPROVAL_ANSWER_PREFIX
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.transport.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

private const val SECOND_MS = 1_000L

/** The card the session typed: never a token, which stays in the session. */
private val CARD = SessionEvent.Approval("ap-1", "sandbox", "Allow reading ~/Documents?", "~/Documents/**", ttlS = 60)

/**
 * Approval cards as the chat folds and draws them (design sections 8.4 and 13.5): after the newest row as they
 * came, counting down on the monotonic clock, expired at once when their time ran out before the screen drew
 * them (gotcha 18), answerable until an answer is on its way, and again once the daemon refused it; each end
 * becomes its receipt line, and an answer's own row and outbox item never show, since they carry the token.
 */
class ApprovalItemsTest {
    private fun moment(
        mono: Long,
        newest: Int = 4,
    ) = Moment(mono, wallAt(1), Candidate.Scope.LAN, newest.toULong())

    private fun folded(vararg steps: Pair<SessionEvent, Long>): ChatLive =
        steps.fold(ChatLive()) { live, (event, mono) -> live.after(event, moment(mono)) }

    private fun shown(live: ChatLive): ShownApproval = shownOf(live.approvals.single())

    @Test
    fun `a card stands after the newest row as it came, and counts down its seconds, rounded up`() {
        val live = folded(CARD to 10_000L)
        val (placed, wall) = approvalItems(live).single()
        assertEquals(4uL, placed.anchor)
        assertEquals(wallAt(1), wall)
        val card = (placed.item as ChatItem.Approval).card
        assertEquals(CardKind.SANDBOX, card.kind)
        assertEquals(60, card.secondsLeft(10_000L))
        assertEquals(43, card.secondsLeft(10_000L + 17_500L))
        assertEquals(42, card.secondsLeft(10_000L + 18_000L))
        assertTrue(card.answerable(10_000L + 59_999L))
        assertEquals(Receipt.EXPIRED, card.receiptAt(10_000L + 60 * SECOND_MS))
    }

    @Test
    fun `a card whose time ran out before it was drawn is expired at once, and takes no answer`() {
        val card = shown(folded(CARD.copy(ttlS = 5) to 0L))
        val firstFrame = 5 * SECOND_MS + 1
        assertEquals(Receipt.EXPIRED, card.receiptAt(firstFrame))
        assertFalse(card.answerable(firstFrame))
        assertEquals(0, card.secondsLeft(firstFrame))
    }

    @Test
    fun `an answer on its way holds the buttons, and a refused one gives them back`() {
        val answered = folded(CARD to 0L, SessionEvent.ApprovalAnswered("ap-1", true, "approval-answer:ap-1:aa") to 1L)
        assertFalse(shown(answered).answerable(2L))
        val failure = RequestFailure("approval_not_found", "gone")
        val refused =
            answered.after(SessionEvent.RequestFailed("approval-answer:ap-1:aa", failure, inOutbox = false), moment(3L))
        assertTrue(shown(refused).answerable(4L))
    }

    @Test
    fun `each end is its receipt, and a card gone while the phone was away is closed`() {
        val ends =
            mapOf(
                ApprovalOutcome.APPROVED to Receipt.APPROVED,
                ApprovalOutcome.DENIED to Receipt.DENIED,
                ApprovalOutcome.EXPIRED to Receipt.EXPIRED,
            )
        ends.forEach { (outcome, receipt) ->
            val live = folded(CARD to 0L, SessionEvent.ApprovalResolved("ap-1", outcome) to 1L)
            assertEquals(receipt, shown(live).receiptAt(1L))
        }
        val away = folded(CARD to 0L, SessionEvent.ApprovalClosedWhileAway("ap-1") to 1L)
        assertEquals(Receipt.CLOSED, shown(away).receiptAt(1L))
    }

    @Test
    fun `a replay keeps the card's place, its ttl and its answer, and moves its expiry`() {
        val live =
            folded(
                CARD to 0L,
                SessionEvent.ApprovalAnswered("ap-1", false, "approval-answer:ap-1:bb") to 1_000L,
                CARD.copy(ttlS = 30) to 40_000L,
            )
        val card = live.approvals.single()
        assertEquals(60, card.ttlS)
        assertEquals(70_000L, card.expiresAtMono)
        assertEquals(LiveAnswer(false, "approval-answer:ap-1:bb"), card.answer)
        assertEquals(4uL, card.afterSeq)
    }

    /** What the countdown says after each of [frames], its seconds left, from the card's first frame. */
    private fun said(vararg frames: Int): List<Int?> {
        var said: Int? = null
        var before: Int? = null
        return frames.map { left ->
            said = announcementAfter(said, before, left)
            before = left
            said
        }
    }

    @Test
    fun `TalkBack hears the countdown as it crosses 30 s and 10 s, with the seconds it then has`() {
        assertEquals(listOf(null, null, 30, 30, 30, 10, 10), said(60, 31, 30, 29, 11, 10, 3))
        assertEquals(listOf(null, 27, 27, 8), said(33, 27, 12, 8), "frames that came late say their true time")
    }

    @Test
    fun `a card first drawn under 30 s says its own seconds once, never a threshold it did not reach`() {
        assertEquals(listOf(25, 25, 25, 10), said(25, 24, 12, 10))
        assertEquals(listOf(12, 10), said(12, 10))
        assertEquals(listOf(8, 8, 8), said(8, 7, 1))
    }

    @Test
    fun `the countdown's line keeps the height of the most seconds it shows, as many digits all nines`() {
        assertEquals(99, widestSeconds(60))
        assertEquals(99, widestSeconds(10))
        assertEquals(9, widestSeconds(9))
        assertEquals(999, widestSeconds(600))
        assertEquals(9, widestSeconds(0))
    }

    @Test
    fun `an answer's row and outbox item never show, their words holding the token`() {
        val answerId = "${APPROVAL_ANSWER_PREFIX}ap-1:0123456789abcdef"
        val inputs =
            ChatInputs(
                rows = listOf(userRow(5, "/confirm opaque-token", clientMsgId = answerId), userRow(4, "hello")),
                outbox = listOf(OutboxItem(ClientEvent.Command(answerId, PROFILE, "deny", "opaque-token"))),
                bridged = emptyList(),
                live = ChatLive(),
                connected = true,
                unreadAt = null,
                requests = emptyMap(),
                profileId = PROFILE,
                nowWall = wallAt(10),
                zone = ZoneOffset.UTC,
            )
        val texts = chatItems(inputs).filterIsInstance<ChatItem.Message>().map { it.message.text }
        assertEquals(listOf("hello"), texts)
    }
}
