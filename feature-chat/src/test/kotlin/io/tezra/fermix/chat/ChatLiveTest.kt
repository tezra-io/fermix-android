package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.ToolPhase
import io.tezra.fermix.session.DRAFT_INDICATOR_POOLS
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.transport.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The app's fold of a session's events into what the chat shows besides its rows. */
class ChatLiveTest {
    private val turn = "turn-m1"

    private fun moment(
        mono: Long,
        newest: Int = 1,
    ) = Moment(mono, wallAt(mono / 60_000), Candidate.Scope.LAN, newest.toULong())

    private fun folded(vararg steps: Pair<SessionEvent, Long>): ChatLive =
        steps.fold(ChatLive()) { live, (event, mono) -> live.after(event, moment(mono)) }

    private fun effect(
        effect: TurnEffect,
        speaking: Boolean = false,
    ) = SessionEvent.Turn(effect, speaking)

    @Test
    fun `a turn's card counts its headings and chips, and the bubble that replaces it counts the time it showed`() {
        val live =
            folded(
                effect(TurnEffect.CardShown(turn)) to 0L,
                effect(TurnEffect.CardText(turn, "Reading the logs\n\nChecking the limits")) to 1_000L,
                effect(TurnEffect.ToolChip(turn, "shell", ToolPhase.START, null)) to 2_000L,
                effect(TurnEffect.ToolChip(turn, "shell", ToolPhase.STOP, "ok")) to 3_000L,
                effect(TurnEffect.BubbleOpened(turn, 1, fromCard = true)) to 12_000L,
                effect(TurnEffect.BubbleText(turn, 1, "The worker")) to 12_500L,
                effect(TurnEffect.BubbleText(turn, 1, "The worker restarts")) to 13_000L,
                effect(TurnEffect.BubbleSealed(turn, 1, 7uL)) to 14_000L,
                effect(TurnEffect.TurnEnded(turn, TurnOutcome.Completed)) to 15_000L,
            )
        val shown = live.turns.single()
        assertNull(shown.card)
        assertEquals(12_000L, shown.thoughtMs)
        assertEquals(listOf(LiveChip("shell", running = false, status = "ok")), shown.tools)
        val went = LiveCard(0L, listOf("Reading the logs", "Checking the limits"), shown.tools, speaking = false)
        assertEquals(LiveBubble(1, "The worker restarts", 0, true, 7uL, wallAt(0), went), shown.bubbles.single())
        assertEquals(Candidate.Scope.LAN, shown.path)
        assertEquals(TurnOutcome.Completed, shown.ending?.outcome)
        assertEquals("m1", shown.clientMsgId)
    }

    @Test
    fun `a replaced snapshot that does not extend the text counts a reset`() {
        val live =
            folded(
                effect(TurnEffect.BubbleOpened(turn, 1, fromCard = false)) to 0L,
                effect(TurnEffect.BubbleText(turn, 1, "Draft one")) to 1L,
                effect(TurnEffect.BubbleText(turn, 1, "Another draft")) to 2L,
            )
        assertEquals(
            1,
            live.turns
                .single()
                .bubbles
                .single()
                .resets,
        )
    }

    @Test
    fun `accepted, a refusal before it, notices and model lines are remembered`() {
        val failure = RequestFailure("unsupported", "no")
        val live =
            folded(
                SessionEvent.Accepted("m1", duplicate = false) to 60_000L,
                SessionEvent.RequestFailed("m2", failure, inOutbox = true) to 61_000L,
                SessionEvent.RequestFailed("m3", failure, inOutbox = false) to 62_000L,
            )
        assertEquals(mapOf("m1" to wallAt(1)), live.accepted)
        assertEquals(setOf("m2"), live.refused)
    }

    @Test
    fun `a card the daemon speaks on makes the line the opening word, whatever the time`() {
        val quiet = LiveCard(0L, emptyList(), emptyList(), speaking = false)
        val heading = quiet.copy(headings = listOf("Reading the logs"), speaking = true)
        val running = quiet.copy(chips = listOf(LiveChip("shell", running = true, status = null)), speaking = true)
        val pools = DRAFT_INDICATOR_POOLS
        assertEquals("Thinking", cardLine(quiet, 0L, 7L, 4_000L, pools))
        val phrase = cardLine(quiet, 0L, 7L, 20_000L, pools)
        assertTrue(phrase in pools.first, phrase)
        assertEquals("Thinking", cardLine(heading, 0L, 7L, 20_000L, pools))
        assertEquals("Thinking", cardLine(running, 0L, 7L, 20_000L, pools))
        assertTrue(cardLine(quiet, 0L, 7L, 60_000L, pools) in pools.second)
    }

    @Test
    fun `a turn over without its ending runs on when it shows again, and one that ended is a new turn`() {
        val over =
            folded(
                effect(TurnEffect.CardShown(turn)) to 0L,
                effect(TurnEffect.TurnEnded(turn, TurnOutcome.Over)) to 1L,
                effect(TurnEffect.CardShown(turn)) to 2L,
            )
        assertTrue(over.turns.single().live)
        val again =
            folded(
                effect(TurnEffect.CardShown(turn)) to 0L,
                effect(TurnEffect.TurnEnded(turn, TurnOutcome.Stopped)) to 1L,
                effect(TurnEffect.CardShown(turn)) to 2L,
            )
        assertEquals(2L, again.turns.single().startedMono)
        assertFalse(again.turns.single().card == null)
    }

    @Test
    fun `the card speaks as core-session says, a blank thought included, and is quiet again when it says so`() {
        val blank =
            folded(
                effect(TurnEffect.CardShown(turn)) to 0L,
                effect(TurnEffect.CardText(turn, "  "), speaking = true) to 1L,
            )
        val card = checkNotNull(blank.turns.single().card)
        assertTrue(card.speaking)
        assertEquals(emptyList<String>(), card.headings)
        val quiet = blank.after(effect(TurnEffect.ToolChip(turn, "shell", ToolPhase.STOP, null)), moment(2L))
        assertFalse(checkNotNull(quiet.turns.single().card).speaking)
    }

    @Test
    fun `a bare text_done keeps the card in the bubble's place, by the key both share, until the row lands`() {
        val live =
            folded(
                effect(TurnEffect.CardShown(turn)) to 0L,
                effect(TurnEffect.CardText(turn, "Reading the logs")) to 1L,
                effect(TurnEffect.BubbleOpened(turn, 1, fromCard = true)) to 2L,
                effect(TurnEffect.BubbleSealed(turn, 1, 7uL)) to 3L,
            )
        val shown = live.turns.single()
        val held = turnBubbles(shown, rowSeqs = emptySet()).single()
        assertTrue(held is ChatItem.Thinking, "$held")
        assertEquals(cardKey(turn, 1), held.key)
        assertEquals(listOf("Reading the logs"), (held as ChatItem.Thinking).card.headings)
        assertEquals(emptyList<ChatItem>(), turnBubbles(shown, rowSeqs = setOf(7uL)))
        assertEquals(cardKey(turn, 1), rowKey(agentRow(7, "The worker restarts"), agentMessage(), live))
    }

    @Test
    fun `an answer arrives once its turn completed with a sealed final bubble, its words the bubble's or its row's`() {
        val streamed =
            folded(
                effect(TurnEffect.BubbleOpened(turn, 1, fromCard = false)) to 0L,
                effect(TurnEffect.BubbleText(turn, 1, "The worker restarts")) to 1L,
                effect(TurnEffect.BubbleSealed(turn, 1, 7uL)) to 2L,
            )
        assertEquals(emptyList<Arrival>(), arrivals(streamed, emptyList()))
        val done = streamed.after(effect(TurnEffect.TurnEnded(turn, TurnOutcome.Completed)), moment(3L))
        assertEquals(listOf(Arrival(turn, "The worker restarts")), arrivals(done, emptyList()))
        val bare =
            folded(
                effect(TurnEffect.BubbleOpened("turn-m2", 1, fromCard = false)) to 0L,
                effect(TurnEffect.BubbleSealed("turn-m2", 1, 9uL)) to 1L,
                effect(TurnEffect.TurnEnded("turn-m2", TurnOutcome.Completed)) to 2L,
            )
        assertEquals(emptyList<Arrival>(), arrivals(bare, emptyList()))
        assertEquals(listOf(Arrival("turn-m2", "From the row")), arrivals(bare, listOf(agentRow(9, "From the row"))))
        val stopped = streamed.after(effect(TurnEffect.TurnEnded(turn, TurnOutcome.Stopped)), moment(3L))
        assertEquals(emptyList<Arrival>(), arrivals(stopped, emptyList()))
    }

    private fun agentMessage() = ShownMessage(Sender.Agent, "The worker restarts", null, Delivery.NONE)
}
