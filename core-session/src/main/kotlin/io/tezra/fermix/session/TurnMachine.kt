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

/** A turn's id is `turn-` and the client_msg_id of the request it answers (PROTOCOL.md "Streaming a turn"). */
private const val TURN_ID_PREFIX = "turn-"

/** The `turn_error` code of a turn the owner stopped (PROTOCOL.md "Streaming a turn"). */
private const val CANCELLED = "cancelled"

/** The id of the turn that answers the request [clientMsgId]. */
fun turnIdOf(clientMsgId: String): String = TURN_ID_PREFIX + clientMsgId

/**
 * Where one turn stands in design section 8.2's client machine. The machine is per turn; its turn id
 * is the key it is kept under, so no state repeats it.
 */
sealed interface TurnState {
    /** No card and no bubble: before the phone's message is accepted, or a turn not seen yet. */
    data object Idle : TurnState

    /**
     * `thinking` while [named] is false, the phone's message accepted and no turn id seen yet, and
     * `thinking(T)` once one is. [bubbles] were sealed above the card. [card] is false once
     * `thought_done` removed it; [headings] and [runningTools] are what the daemon has put on it.
     */
    data class Thinking(
        val named: Boolean,
        val bubbles: Int,
        val card: Boolean,
        val headings: Boolean,
        val runningTools: Int,
    ) : TurnState

    /** Bubble [bubble], counted from 1, is streaming, and [text] is its live text so far. */
    data class Answering(
        val bubble: Int,
        val text: String,
    ) : TurnState

    /** Bubble [bubble] is sealed: its canonical text is its row. */
    data class Sealed(
        val bubble: Int,
    ) : TurnState

    /** The turn ended; every later event of it, a late or duplicate `turn_done` among them, is ignored. */
    data object Ended : TurnState
}

/** Whether the daemon's own headings or a running tool are on the card, so the indicator reads plain "Thinking". */
val TurnState.daemonSpeaking: Boolean
    get() = this is TurnState.Thinking && card && (headings || runningTools > 0)

/** What moves a turn's machine: the stream's events, the phone's own `accepted`, and a reconnect's verdict. */
sealed interface TurnEvent {
    val turnId: String

    /** The daemon took the phone's own message; the card shows before any turn id is on the wire. */
    data class Accepted(
        val clientMsgId: String,
    ) : TurnEvent {
        override val turnId: String get() = turnIdOf(clientMsgId)
    }

    data class TurnStarted(
        override val turnId: String,
        val inReplyTo: String,
    ) : TurnEvent

    /** The turn is in `hello_ack.active_turns`: still running when the phone connected. */
    data class Active(
        override val turnId: String,
        val inReplyTo: String?,
    ) : TurnEvent

    /** A `thought`: the headings as a whole snapshot. */
    data class Thought(
        override val turnId: String,
        val text: String,
    ) : TurnEvent

    data class ThoughtDone(
        override val turnId: String,
    ) : TurnEvent

    data class Tool(
        override val turnId: String,
        val tool: String,
        val phase: ToolPhase,
        val status: String?,
    ) : TurnEvent

    /** A `text_delta`: a suffix, or with [replace] the bubble's whole text. */
    data class TextDelta(
        override val turnId: String,
        val text: String,
        val replace: Boolean,
    ) : TurnEvent

    /** A `text_done`: the bubble is sealed with the row at [serverSeq]. */
    data class TextDone(
        override val turnId: String,
        val serverSeq: ULong,
    ) : TurnEvent

    data class TurnDone(
        override val turnId: String,
    ) : TurnEvent

    /** A `turn_error`, or an `error` naming the request the turn answers. */
    data class TurnError(
        override val turnId: String,
        val code: String,
        val message: String,
    ) : TurnEvent

    /**
     * The turn is over without its ending seen: absent from `hello_ack.active_turns`, or past the book's
     * bound. Its card goes and its machine is idle again, not ended: `active_turns` lists running turns
     * only, so a request still queued at the reconnect is absent, and its `request_status` shows the card
     * again, as its `turn_started` would (design section 8.2, `idle ──turn_started(T)──▶ thinking(T)`).
     */
    data class Over(
        override val turnId: String,
    ) : TurnEvent
}

/**
 * What the UI does on a step. Thought text only ever travels as [CardText]: it is never a row and is
 * never stored (design section 8.2).
 */
sealed interface TurnEffect {
    val turnId: String

    /** The working indicator's card shows: the first one starts the indicator's clock. */
    data class CardShown(
        override val turnId: String,
    ) : TurnEffect

    /** The card's headings, a whole snapshot. */
    data class CardText(
        override val turnId: String,
        val text: String,
    ) : TurnEffect

    data class ToolChip(
        override val turnId: String,
        val tool: String,
        val phase: ToolPhase,
        val status: String?,
    ) : TurnEffect

    data class CardRemoved(
        override val turnId: String,
    ) : TurnEffect

    /** Bubble [bubble] opens; with [fromCard] the card morphs into it. */
    data class BubbleOpened(
        override val turnId: String,
        val bubble: Int,
        val fromCard: Boolean,
    ) : TurnEffect

    /** The live bubble's whole text, appended to or replaced as the delta said. */
    data class BubbleText(
        override val turnId: String,
        val bubble: Int,
        val text: String,
    ) : TurnEffect

    /** The bubble is sealed; its canonical text is the row at [serverSeq]. */
    data class BubbleSealed(
        override val turnId: String,
        val bubble: Int,
        val serverSeq: ULong,
    ) : TurnEffect

    /** The turn ended: stop turns back into send, and a queued message may go. */
    data class TurnEnded(
        override val turnId: String,
        val outcome: TurnOutcome,
    ) : TurnEffect
}

/** How a turn ended. */
sealed interface TurnOutcome {
    data object Completed : TurnOutcome

    /** `turn_error{code:"cancelled"}`: the "Stopped" notice. */
    data object Stopped : TurnOutcome

    /** The error card. */
    data class Failed(
        val code: String,
        val message: String,
    ) : TurnOutcome

    /**
     * Over while the phone was away, or not started yet; its outcome comes from `request_status`, and a
     * later `turn_started` of the same turn shows it again.
     */
    data object Over : TurnOutcome
}

data class TurnStep(
    val state: TurnState,
    val effects: List<TurnEffect>,
)

/**
 * Design section 8.2's machine for one turn: a pure reducer, with no clock and no I/O. The diagram draws
 * a tool's `start` only: a `start` opens the card, or the card below a sealed bubble, and a `stop` marks
 * its chip on a card that is shown and never opens one. While a bubble streams there is no card, so a
 * thought or a tool then is dropped; the next one after the seal opens a card below it.
 *
 * Design section 8.2 disagrees with itself on `thought_done`, and this reducer follows its diagram. The
 * diagram (`any ──thought_done(T)──▶ card removed`) and section 13.5 ("on `thought_done` … nothing
 * remains") remove the card, and the working indicator with it, which lives on the card; the working
 * indicator's paragraph ends the indicator only at the first `text_delta`, a bare `text_done`, or
 * `turn_done` / `turn_error`. So a `thought_done` before the first `text_delta` leaves the turn showing
 * nothing until its text, its next thought or tool, or its end. Keeping a cardless indicator until then
 * is the paragraph's reading, and the owner's call.
 */
fun reduceTurn(
    state: TurnState,
    event: TurnEvent,
): TurnStep =
    when (state) {
        TurnState.Idle -> fromIdle(event)
        is TurnState.Thinking -> fromThinking(state, event)
        is TurnState.Answering -> fromAnswering(state, event)
        is TurnState.Sealed -> fromSealed(state, event)
        TurnState.Ended -> TurnStep(state, emptyList())
    }

/**
 * A `turn_done`, a late or duplicate one, a `thought_done`, a tool's `stop` or an over for a turn with
 * nothing shown changes nothing.
 */
private fun fromIdle(event: TurnEvent): TurnStep {
    val opened =
        TurnState.Thinking(
            named = event !is TurnEvent.Accepted,
            bubbles = 0,
            card = true,
            headings = false,
            runningTools = 0,
        )
    return when (event) {
        is TurnEvent.Accepted, is TurnEvent.TurnStarted, is TurnEvent.Active -> {
            TurnStep(opened, listOf(CardShown(event.turnId)))
        }

        is TurnEvent.Thought, is TurnEvent.Tool -> {
            opening(TurnState.Idle, opened.copy(card = false), event)
        }

        is TurnEvent.TextDelta -> {
            answer(bubble = 1, fromCard = false, event)
        }

        is TurnEvent.TextDone -> {
            seal(bubble = 1, fromCard = false, event)
        }

        is TurnEvent.TurnError -> {
            ending(event, emptyList())
        }

        else -> {
            TurnStep(TurnState.Idle, emptyList())
        }
    }
}

private fun fromThinking(
    state: TurnState.Thinking,
    event: TurnEvent,
): TurnStep {
    val id = event.turnId
    val shown = if (state.card) emptyList() else listOf(CardShown(id))
    val removed = if (state.card) listOf(CardRemoved(id)) else emptyList()
    return when (event) {
        is TurnEvent.TurnStarted, is TurnEvent.Active -> {
            TurnStep(state.copy(named = true), emptyList())
        }

        is TurnEvent.Thought -> {
            TurnStep(state.copy(named = true, card = true, headings = true), shown + CardText(id, event.text))
        }

        is TurnEvent.ThoughtDone -> {
            TurnStep(if (state.card) state.copy(card = false, headings = false, runningTools = 0) else state, removed)
        }

        is TurnEvent.Tool -> {
            tool(state, event, shown)
        }

        is TurnEvent.TextDelta -> {
            answer(state.bubbles + 1, state.card, event)
        }

        is TurnEvent.TextDone -> {
            seal(state.bubbles + 1, state.card, event)
        }

        is TurnEvent.TurnDone, is TurnEvent.TurnError, is TurnEvent.Over -> {
            ending(event, removed)
        }

        is TurnEvent.Accepted -> {
            TurnStep(state, emptyList())
        }
    }
}

/** A thought or a tool while a bubble streams is dropped: there is no card to put it on. */
private fun fromAnswering(
    state: TurnState.Answering,
    event: TurnEvent,
): TurnStep =
    when (event) {
        is TurnEvent.TextDelta -> {
            val text = if (event.replace) event.text else state.text + event.text
            TurnStep(state.copy(text = text), listOf(BubbleText(event.turnId, state.bubble, text)))
        }

        is TurnEvent.TextDone -> {
            TurnStep(TurnState.Sealed(state.bubble), listOf(BubbleSealed(event.turnId, state.bubble, event.serverSeq)))
        }

        is TurnEvent.TurnDone, is TurnEvent.TurnError, is TurnEvent.Over -> {
            ending(event, emptyList())
        }

        else -> {
            TurnStep(state, emptyList())
        }
    }

/** After a seal, thinking goes on in a new card below the bubble, and the next text is a new bubble. */
private fun fromSealed(
    state: TurnState.Sealed,
    event: TurnEvent,
): TurnStep {
    val below =
        TurnState.Thinking(named = true, bubbles = state.bubble, card = false, headings = false, runningTools = 0)
    return when (event) {
        is TurnEvent.Thought, is TurnEvent.Tool -> opening(state, below, event)
        is TurnEvent.TextDelta -> answer(state.bubble + 1, fromCard = false, event)
        is TurnEvent.TextDone -> seal(state.bubble + 1, fromCard = false, event)
        is TurnEvent.TurnDone, is TurnEvent.TurnError, is TurnEvent.Over -> ending(event, emptyList())
        else -> TurnStep(state, emptyList())
    }
}

/** A tool's chip goes in the card, which a `start` shows again if `thought_done` had removed it. */
private fun tool(
    state: TurnState.Thinking,
    event: TurnEvent.Tool,
    shown: List<TurnEffect>,
): TurnStep {
    if (!state.card && event.phase == ToolPhase.STOP) return TurnStep(state, emptyList())
    val running =
        when (event.phase) {
            ToolPhase.START -> state.runningTools + 1
            ToolPhase.STOP -> maxOf(0, state.runningTools - 1)
        }
    val chip = ToolChip(event.turnId, event.tool, event.phase, event.status)
    return TurnStep(state.copy(named = true, card = true, runningTools = running), shown + chip)
}

private fun answer(
    bubble: Int,
    fromCard: Boolean,
    event: TurnEvent.TextDelta,
): TurnStep {
    val effects = listOf(BubbleOpened(event.turnId, bubble, fromCard), BubbleText(event.turnId, bubble, event.text))
    return TurnStep(TurnState.Answering(bubble, event.text), effects)
}

/** A `text_done` with no delta before it: the sealed bubble takes the card's place (design R1). */
private fun seal(
    bubble: Int,
    fromCard: Boolean,
    event: TurnEvent.TextDone,
): TurnStep {
    val effects =
        listOf(BubbleOpened(event.turnId, bubble, fromCard), BubbleSealed(event.turnId, bubble, event.serverSeq))
    return TurnStep(TurnState.Sealed(bubble), effects)
}

/**
 * [event] is a `turn_done`, a `turn_error` or an over: the turn ends, after [before]. An over leaves the
 * machine idle (TurnEvent.Over); the turn's own ending leaves it ended.
 */
private fun ending(
    event: TurnEvent,
    before: List<TurnEffect>,
): TurnStep {
    val outcome =
        when {
            event is TurnEvent.TurnDone -> TurnOutcome.Completed
            event is TurnEvent.TurnError && event.code == CANCELLED -> TurnOutcome.Stopped
            event is TurnEvent.TurnError -> TurnOutcome.Failed(event.code, event.message)
            else -> TurnOutcome.Over
        }
    val after = if (outcome == TurnOutcome.Over) TurnState.Idle else TurnState.Ended
    return TurnStep(after, before + TurnEnded(event.turnId, outcome))
}

/** A thought or a tool's `start` opens [card] in place of [from]; a tool's `stop` leaves [from] as it is. */
private fun opening(
    from: TurnState,
    card: TurnState.Thinking,
    event: TurnEvent,
): TurnStep {
    val stop = event is TurnEvent.Tool && event.phase == ToolPhase.STOP
    return if (stop) TurnStep(from, emptyList()) else fromThinking(card, event)
}
