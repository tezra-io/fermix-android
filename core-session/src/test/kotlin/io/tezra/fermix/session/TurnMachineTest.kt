package io.tezra.fermix.session

import io.tezra.fermix.protocol.ToolPhase
import io.tezra.fermix.session.TurnEffect.BubbleOpened
import io.tezra.fermix.session.TurnEffect.BubbleSealed
import io.tezra.fermix.session.TurnEffect.BubbleText
import io.tezra.fermix.session.TurnEffect.CardRemoved
import io.tezra.fermix.session.TurnEffect.CardShown
import io.tezra.fermix.session.TurnEffect.CardText
import io.tezra.fermix.session.TurnEffect.ToolChip
import io.tezra.fermix.session.TurnEffect.TurnEnded
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

private const val T = "turn-c1"
private const val SEQ = 14uL

private val idle = TurnState.Idle
private val unnamed = TurnState.Thinking(named = false, bubbles = 0, card = true, headings = false, runningTools = 0)
private val busy = TurnState.Thinking(named = true, bubbles = 1, card = true, headings = true, runningTools = 1)
private val cardless = TurnState.Thinking(named = true, bubbles = 0, card = false, headings = false, runningTools = 0)
private val answering = TurnState.Answering(bubble = 1, text = "Hel")
private val sealed = TurnState.Sealed(bubble = 1)
private val ended = TurnState.Ended

private val accepted = TurnEvent.Accepted("c1")
private val started = TurnEvent.TurnStarted(T, inReplyTo = "c1")
private val active = TurnEvent.Active(T, inReplyTo = "c1")
private val thought = TurnEvent.Thought(T, "Searching the web")
private val thoughtDone = TurnEvent.ThoughtDone(T)
private val toolStart = TurnEvent.Tool(T, "web_search", ToolPhase.START, status = null)
private val toolStop = TurnEvent.Tool(T, "web_search", ToolPhase.STOP, status = "ok")
private val delta = TurnEvent.TextDelta(T, "lo", replace = false)
private val snapshot = TurnEvent.TextDelta(T, "Hello", replace = true)
private val textDone = TurnEvent.TextDone(T, SEQ)
private val turnDone = TurnEvent.TurnDone(T)
private val stopped = TurnEvent.TurnError(T, "cancelled", "Stopped")
private val failed = TurnEvent.TurnError(T, "turn_failed", "The model did not answer")
private val over = TurnEvent.Over(T)

/** Every state the table starts from: one of each kind, a Thinking with the card up, busy and gone. */
private val states = listOf(idle, unnamed, busy, cardless, answering, sealed, ended)

/** Every event, with both tool phases, a delta and a replace, and both kinds of `turn_error`. */
private val events =
    listOf(
        accepted,
        started,
        active,
        thought,
        thoughtDone,
        toolStart,
        toolStop,
        delta,
        snapshot,
        textDone,
        turnDone,
        stopped,
        failed,
        over,
    )

private val chipStart = ToolChip(T, "web_search", ToolPhase.START, null)
private val chipStop = ToolChip(T, "web_search", ToolPhase.STOP, "ok")
private val endedDone = TurnEnded(T, TurnOutcome.Completed)
private val endedStopped = TurnEnded(T, TurnOutcome.Stopped)
private val endedFailed = TurnEnded(T, TurnOutcome.Failed("turn_failed", "The model did not answer"))
private val endedOver = TurnEnded(T, TurnOutcome.Over)

/** One cell of the table: from [from], [event] leads to [to] with exactly [effects], in order. */
private class Cell(
    val from: TurnState,
    val event: TurnEvent,
    val to: TurnState,
    val effects: List<TurnEffect>,
) {
    override fun toString() = "$from × $event"
}

private fun cell(
    from: TurnState,
    event: TurnEvent,
    to: TurnState,
    vararg effects: TurnEffect,
) = Cell(from, event, to, effects.toList())

private fun opened(
    bubble: Int,
    fromCard: Boolean,
    text: String,
) = arrayOf(BubbleOpened(T, bubble, fromCard), BubbleText(T, bubble, text))

private fun sealedAs(
    bubble: Int,
    fromCard: Boolean,
) = arrayOf(BubbleOpened(T, bubble, fromCard), BubbleSealed(T, bubble, SEQ))

private val idleCells =
    listOf(
        cell(idle, accepted, unnamed, CardShown(T)),
        cell(idle, started, unnamed.copy(named = true), CardShown(T)),
        cell(idle, active, unnamed.copy(named = true), CardShown(T)),
        cell(idle, thought, unnamed.copy(named = true, headings = true), CardShown(T), CardText(T, thought.text)),
        cell(idle, thoughtDone, idle),
        cell(idle, toolStart, unnamed.copy(named = true, runningTools = 1), CardShown(T), chipStart),
        cell(idle, toolStop, idle),
        cell(idle, delta, TurnState.Answering(1, "lo"), *opened(1, false, "lo")),
        cell(idle, snapshot, TurnState.Answering(1, "Hello"), *opened(1, false, "Hello")),
        cell(idle, textDone, TurnState.Sealed(1), *sealedAs(1, false)),
        cell(idle, turnDone, idle),
        cell(idle, stopped, ended, endedStopped),
        cell(idle, failed, ended, endedFailed),
        cell(idle, over, idle),
    )

private val unnamedCells =
    listOf(
        cell(unnamed, accepted, unnamed),
        cell(unnamed, started, unnamed.copy(named = true)),
        cell(unnamed, active, unnamed.copy(named = true)),
        cell(unnamed, thought, unnamed.copy(named = true, headings = true), CardText(T, thought.text)),
        cell(unnamed, thoughtDone, unnamed.copy(card = false), CardRemoved(T)),
        cell(unnamed, toolStart, unnamed.copy(named = true, runningTools = 1), chipStart),
        cell(unnamed, toolStop, unnamed.copy(named = true), chipStop),
        cell(unnamed, delta, TurnState.Answering(1, "lo"), *opened(1, true, "lo")),
        cell(unnamed, snapshot, TurnState.Answering(1, "Hello"), *opened(1, true, "Hello")),
        cell(unnamed, textDone, TurnState.Sealed(1), *sealedAs(1, true)),
        cell(unnamed, turnDone, ended, CardRemoved(T), endedDone),
        cell(unnamed, stopped, ended, CardRemoved(T), endedStopped),
        cell(unnamed, failed, ended, CardRemoved(T), endedFailed),
        cell(unnamed, over, idle, CardRemoved(T), endedOver),
    )

private val busyCells =
    listOf(
        cell(busy, accepted, busy),
        cell(busy, started, busy),
        cell(busy, active, busy),
        cell(busy, thought, busy, CardText(T, thought.text)),
        cell(busy, thoughtDone, busy.copy(card = false, headings = false, runningTools = 0), CardRemoved(T)),
        cell(busy, toolStart, busy.copy(runningTools = 2), chipStart),
        cell(busy, toolStop, busy.copy(runningTools = 0), chipStop),
        cell(busy, delta, TurnState.Answering(2, "lo"), *opened(2, true, "lo")),
        cell(busy, snapshot, TurnState.Answering(2, "Hello"), *opened(2, true, "Hello")),
        cell(busy, textDone, TurnState.Sealed(2), *sealedAs(2, true)),
        cell(busy, turnDone, ended, CardRemoved(T), endedDone),
        cell(busy, stopped, ended, CardRemoved(T), endedStopped),
        cell(busy, failed, ended, CardRemoved(T), endedFailed),
        cell(busy, over, idle, CardRemoved(T), endedOver),
    )

private val cardlessCells =
    listOf(
        cell(cardless, accepted, cardless),
        cell(cardless, started, cardless),
        cell(cardless, active, cardless),
        cell(cardless, thought, cardless.copy(card = true, headings = true), CardShown(T), CardText(T, thought.text)),
        cell(cardless, thoughtDone, cardless),
        cell(cardless, toolStart, cardless.copy(card = true, runningTools = 1), CardShown(T), chipStart),
        cell(cardless, toolStop, cardless),
        cell(cardless, delta, TurnState.Answering(1, "lo"), *opened(1, false, "lo")),
        cell(cardless, snapshot, TurnState.Answering(1, "Hello"), *opened(1, false, "Hello")),
        cell(cardless, textDone, TurnState.Sealed(1), *sealedAs(1, false)),
        cell(cardless, turnDone, ended, endedDone),
        cell(cardless, stopped, ended, endedStopped),
        cell(cardless, failed, ended, endedFailed),
        cell(cardless, over, idle, endedOver),
    )

private val answeringCells =
    listOf(
        cell(answering, accepted, answering),
        cell(answering, started, answering),
        cell(answering, active, answering),
        cell(answering, thought, answering),
        cell(answering, thoughtDone, answering),
        cell(answering, toolStart, answering),
        cell(answering, toolStop, answering),
        cell(answering, delta, TurnState.Answering(1, "Hello"), BubbleText(T, 1, "Hello")),
        cell(answering, snapshot, TurnState.Answering(1, "Hello"), BubbleText(T, 1, "Hello")),
        cell(answering, textDone, sealed, BubbleSealed(T, 1, SEQ)),
        cell(answering, turnDone, ended, endedDone),
        cell(answering, stopped, ended, endedStopped),
        cell(answering, failed, ended, endedFailed),
        cell(answering, over, idle, endedOver),
    )

private val sealedCells =
    listOf(
        cell(sealed, accepted, sealed),
        cell(sealed, started, sealed),
        cell(sealed, active, sealed),
        cell(sealed, thought, busy.copy(runningTools = 0), CardShown(T), CardText(T, thought.text)),
        cell(sealed, thoughtDone, sealed),
        cell(sealed, toolStart, busy.copy(headings = false), CardShown(T), chipStart),
        cell(sealed, toolStop, sealed),
        cell(sealed, delta, TurnState.Answering(2, "lo"), *opened(2, false, "lo")),
        cell(sealed, snapshot, TurnState.Answering(2, "Hello"), *opened(2, false, "Hello")),
        cell(sealed, textDone, TurnState.Sealed(2), *sealedAs(2, false)),
        cell(sealed, turnDone, ended, endedDone),
        cell(sealed, stopped, ended, endedStopped),
        cell(sealed, failed, ended, endedFailed),
        cell(sealed, over, idle, endedOver),
    )

private val endedCells = events.map { cell(ended, it, ended) }

private val allCells = idleCells + unnamedCells + busyCells + cardlessCells + answeringCells + sealedCells + endedCells

/**
 * Design section 8.2's client state machine as a table: every state against every event, the cells
 * the diagram draws and the ones it leaves to the reducer, and a test that the table is whole. Thought
 * text is only ever card text.
 */
class TurnMachineTest {
    @Test
    fun `the table holds every state against every event, each once`() {
        val cells = allCells.map { it.from to it.event }
        val whole = states.flatMap { state -> events.map { event -> state to event } }
        assertEquals(whole.size, cells.size)
        assertEquals(whole.toSet(), cells.toSet())
    }

    @TestFactory
    fun `idle`(): List<DynamicTest> = table(idleCells)

    @TestFactory
    fun `thinking with no turn id yet`(): List<DynamicTest> = table(unnamedCells)

    @TestFactory
    fun `thinking with headings and a running tool below a sealed bubble`(): List<DynamicTest> = table(busyCells)

    @TestFactory
    fun `thinking after thought_done removed the card`(): List<DynamicTest> = table(cardlessCells)

    @TestFactory
    fun `answering drops what has no card to go on`(): List<DynamicTest> = table(answeringCells)

    @TestFactory
    fun `sealed opens a card below only for a thought or a tool's start`(): List<DynamicTest> = table(sealedCells)

    @TestFactory
    fun `ended ignores everything, a late or duplicate turn_done among them`(): List<DynamicTest> = table(endedCells)

    @Test
    fun `an accepted message names its turn by the wire's rule, turn- and its client_msg_id`() {
        assertEquals("turn-c1", TurnEvent.Accepted("c1").turnId)
        assertEquals("turn-c1", turnIdOf("c1"))
    }

    @Test
    fun `gotcha 4, a text_done with no turn_started takes the card's place and turn_done still ends the turn`() {
        val (state, effects) = run(idle, accepted, textDone, turnDone)
        assertEquals(ended, state)
        val expected =
            listOf(
                CardShown(T),
                BubbleOpened(T, 1, true),
                BubbleSealed(T, 1, SEQ),
                TurnEnded(T, TurnOutcome.Completed),
            )
        assertEquals(expected, effects)
    }

    @Test
    fun `gotcha 5, replace makes the text a whole snapshot, never a suffix`() {
        val (state, effects) = run(idle, started, delta, snapshot, TurnEvent.TextDelta(T, "!", replace = false))
        assertEquals(TurnState.Answering(1, "Hello!"), state)
        assertEquals(listOf("lo", "Hello", "Hello!"), effects.filterIsInstance<BubbleText>().map { it.text })
    }

    @Test
    fun `gotcha 6, thought_done on either side of text_done only removes the card, and only turn_done ends the turn`() {
        val before = run(idle, started, thought, thoughtDone, delta, textDone)
        val after = run(idle, started, thought, delta, textDone, thoughtDone)
        assertEquals(TurnState.Sealed(1), before.first)
        assertEquals(TurnState.Sealed(1), after.first)
        assertEquals(ended, run(before.first, turnDone).first)
    }

    @Test
    fun `commentary seals a bubble, thinking continues below it, and the answer is a second bubble`() {
        val (state, effects) = run(idle, accepted, started, delta, textDone, thought, delta, textDone, turnDone)
        assertEquals(ended, state)
        val sealedBubbles = effects.filterIsInstance<BubbleSealed>().map { it.bubble }
        assertEquals(listOf(1, 2), sealedBubbles)
        assertEquals(2, effects.count { it is CardShown })
    }

    @Test
    fun `a tool that stops after the bubble it streamed under sealed opens no card of its own`() {
        val (state, effects) = run(idle, accepted, started, delta, toolStart, textDone, toolStop)
        assertEquals(sealed, state)
        assertEquals(1, effects.count { it is CardShown })
        assertEquals(emptyList<TurnEffect>(), effects.filterIsInstance<ToolChip>())
    }

    @Test
    fun `thought text is card text and nothing else`() {
        val (_, effects) = run(idle, accepted, started, thought, delta, textDone, turnDone)
        val carrying = effects.filter { it.toString().contains(thought.text) }
        assertEquals(listOf<TurnEffect>(CardText(T, thought.text)), carrying)
    }

    private fun table(cells: List<Cell>): List<DynamicTest> =
        cells.map { cell ->
            dynamicTest(cell.toString()) {
                val step = reduceTurn(cell.from, cell.event)
                assertEquals(cell.to, step.state, "the state after $cell")
                assertEquals(cell.effects, step.effects, "the effects of $cell")
            }
        }

    private fun run(
        from: TurnState,
        vararg events: TurnEvent,
    ): Pair<TurnState, List<TurnEffect>> =
        events.fold(from to emptyList()) { (state, effects), event ->
            val step = reduceTurn(state, event)
            step.state to effects + step.effects
        }
}
