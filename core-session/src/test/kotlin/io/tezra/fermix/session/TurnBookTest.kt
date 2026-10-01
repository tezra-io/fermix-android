package io.tezra.fermix.session

import io.tezra.fermix.protocol.ActiveTurn
import io.tezra.fermix.session.TurnEffect.BubbleOpened
import io.tezra.fermix.session.TurnEffect.BubbleSealed
import io.tezra.fermix.session.TurnEffect.CardRemoved
import io.tezra.fermix.session.TurnEffect.CardShown
import io.tezra.fermix.session.TurnEffect.TurnEnded
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One machine per turn id (design section 8.2), what a reconnect ends and shows, a turn that ended
 * staying ended, a turn found over opening again, and the bounds.
 */
class TurnBookTest {
    @Test
    fun `the phone's own message and a cron turn each get a machine of their own`() {
        val (book, effects) =
            TurnBook().play(
                TurnEvent.Accepted("c1"),
                TurnEvent.TurnStarted("turn-c1", inReplyTo = "c1"),
                TurnEvent.TurnStarted("turn-cron-7", inReplyTo = "job-7"),
                TurnEvent.TextDone("turn-cron-7", 20uL),
                TurnEvent.TurnDone("turn-cron-7"),
            )
        assertEquals(TurnState.Thinking(true, 0, true, false, 0), book.stateOf("turn-c1"))
        assertEquals(TurnState.Ended, book.stateOf("turn-cron-7"))
        val cron = effects.filter { it.turnId == "turn-cron-7" }
        val expected =
            listOf(
                CardShown("turn-cron-7"),
                BubbleOpened("turn-cron-7", 1, true),
                BubbleSealed("turn-cron-7", 1, 20uL),
                TurnEnded("turn-cron-7", TurnOutcome.Completed),
            )
        assertEquals(expected, cron)
    }

    @Test
    fun `a duplicate or late turn_done, and any later event, is ignored once the turn ended`() {
        val (ended, _) =
            TurnBook().play(
                TurnEvent.Accepted("c1"),
                TurnEvent.TextDone("turn-c1", 5uL),
                TurnEvent.TurnDone("turn-c1"),
            )
        val late =
            listOf(
                TurnEvent.TurnDone("turn-c1"),
                TurnEvent.TextDelta("turn-c1", "late", replace = false),
                TurnEvent.Thought("turn-c1", "late"),
                TurnEvent.TurnStarted("turn-c1", inReplyTo = "c1"),
                TurnEvent.TurnError("turn-c1", "turn_failed", "late"),
            )
        val (book, effects) = ended.playAll(late)
        assertEquals(emptyList<TurnEffect>(), effects)
        assertEquals(TurnState.Ended, book.stateOf("turn-c1"))
    }

    @Test
    fun `a turn a reconnect found over, a request still queued, opens again when it starts`() {
        val (book, _) = TurnBook().play(TurnEvent.Accepted("c1"))
        val over = book.reconcile(emptyList())
        assertEquals(TurnState.Idle, over.book.stateOf("turn-c1"))
        val (_, effects) =
            over.book.play(TurnEvent.TurnStarted("turn-c1", inReplyTo = "c1"), TurnEvent.TurnDone("turn-c1"))
        val expected = listOf(CardShown("turn-c1"), CardRemoved("turn-c1"), TurnEnded("turn-c1", TurnOutcome.Completed))
        assertEquals(expected, effects)
    }

    @Test
    fun `a turn_done for a turn never seen changes nothing`() {
        val (book, effects) = TurnBook().play(TurnEvent.TurnDone("turn-x"))
        assertEquals(emptyList<TurnEffect>(), effects)
        assertEquals(TurnState.Idle, book.stateOf("turn-x"))
    }

    @Test
    fun `a reconnect ends every turn absent from active_turns and shows every listed one`() {
        val (book, _) =
            TurnBook().play(
                TurnEvent.Accepted("c1"),
                TurnEvent.TurnStarted("turn-c2", inReplyTo = "c2"),
                TurnEvent.TurnStarted("turn-cron-7", inReplyTo = "job-7"),
            )
        assertEquals(listOf("c1", "c2", "job-7"), book.requestIds())
        val step = book.reconcile(listOf(ActiveTurn("turn-c2", "c2"), ActiveTurn("turn-c3", "c3")))
        val expected =
            listOf(
                CardRemoved("turn-c1"),
                TurnEnded("turn-c1", TurnOutcome.Over),
                CardRemoved("turn-cron-7"),
                TurnEnded("turn-cron-7", TurnOutcome.Over),
                CardShown("turn-c3"),
            )
        assertEquals(expected, step.effects)
        assertEquals(TurnState.Idle, step.book.stateOf("turn-c1"))
        assertEquals(TurnState.Thinking(true, 0, true, false, 0), step.book.stateOf("turn-c2"))
        assertEquals(TurnState.Thinking(true, 0, true, false, 0), step.book.stateOf("turn-c3"))
        assertEquals(listOf("c2", "c3"), step.book.requestIds())
    }

    @Test
    fun `a turn that ended is not shown again by a reconnect that still lists it`() {
        val (book, _) = TurnBook().play(TurnEvent.Accepted("c1"), TurnEvent.TurnDone("turn-c1"))
        val step = book.reconcile(listOf(ActiveTurn("turn-c1", "c1")))
        assertEquals(emptyList<TurnEffect>(), step.effects)
    }

    @Test
    fun `past the live bound the oldest turn is ended as over, so the book never grows without end`() {
        val accepted = (1..MAX_LIVE_TURNS + 1).map { TurnEvent.Accepted("c$it") }
        val (book, effects) = TurnBook().playAll(accepted)
        assertEquals(TurnState.Ended, book.stateOf("turn-c1"))
        assertTrue(effects.contains(TurnEnded("turn-c1", TurnOutcome.Over)))
        assertEquals(MAX_LIVE_TURNS, book.requestIds().size)
    }

    private fun TurnBook.play(vararg events: TurnEvent): Pair<TurnBook, List<TurnEffect>> = playAll(events.toList())

    private fun TurnBook.playAll(events: List<TurnEvent>): Pair<TurnBook, List<TurnEffect>> =
        events.fold(this to emptyList()) { (book, effects), event ->
            val step = book.apply(event)
            step.book to effects + step.effects
        }
}
