package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ToolPhase
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome

/**
 * [turns] after [step]: the turn it names is opened at its first effect, or moved as the effect says, and its
 * card takes core-session's word on whether the daemon speaks on it.
 */
internal fun turnsAfter(
    turns: List<LiveTurn>,
    step: SessionEvent.Turn,
    at: Moment,
): List<LiveTurn> {
    val effect = step.effect
    val index = turns.indexOfFirst { it.turnId == effect.turnId }
    val existing = turns.getOrNull(index)
    // A turn over without its ending seen (TurnOutcome.Over) runs on when it shows again, as core-session's
    // book lets it; one that ended for good is never moved by core-session again, so another is a new turn.
    val turn =
        when {
            existing == null -> LiveTurn(effect.turnId, at.monoMs, at.wallMs)
            existing.live -> existing
            existing.ending?.outcome == TurnOutcome.Over -> existing.copy(ending = null)
            else -> LiveTurn(effect.turnId, at.monoMs, at.wallMs)
        }
    val stepped = turnAfter(turn, effect, at)
    val moved = stepped.copy(card = stepped.card?.copy(speaking = step.daemonSpeaking))
    return if (index >= 0) turns.toMutableList().also { it[index] = moved } else turns + moved
}

/** One effect on one turn (design section 8.2's machine, as core-session runs it and says it in effects). */
internal fun turnAfter(
    turn: LiveTurn,
    effect: TurnEffect,
    at: Moment,
): LiveTurn =
    when (effect) {
        is TurnEffect.CardShown -> {
            turn.copy(card = turn.card ?: emptyCard(at))
        }

        is TurnEffect.CardText -> {
            turn.withCard(at) { it.copy(headings = headingsOf(effect.text)) }
        }

        is TurnEffect.ToolChip -> {
            chipped(turn, effect, at)
        }

        is TurnEffect.CardRemoved -> {
            turn.cardGone(at)
        }

        is TurnEffect.BubbleOpened -> {
            opened(turn.cardGone(at), effect, turn.card)
        }

        is TurnEffect.BubbleText -> {
            turn.withBubble(effect.bubble) { replaced(it, effect.text) }
        }

        is TurnEffect.BubbleSealed -> {
            turn
                .withBubble(effect.bubble) { it.copy(sealedSeq = effect.serverSeq, sealedWall = at.wallMs) }
                .copy(path = at.path)
        }

        is TurnEffect.TurnEnded -> {
            turn.cardGone(at).copy(ending = TurnEnding(effect.outcome, at.monoMs, at.wallMs, at.newestSeq))
        }
    }

/** The daemon's headings: its snapshot's lines, blank ones left out. */
internal fun headingsOf(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

/** A card as it first shows, at [at]: nothing on it yet. */
private fun emptyCard(at: Moment): LiveCard = LiveCard(at.monoMs, emptyList(), emptyList(), speaking = false)

/** [turn] with its card changed by [change], the card shown first if it was not (a thought opens it). */
private fun LiveTurn.withCard(
    at: Moment,
    change: (LiveCard) -> LiveCard,
): LiveTurn = copy(card = change(card ?: emptyCard(at)))

/** A tool's `start` puts a running chip on the card; its `stop` marks the latest running chip of that tool. */
private fun chipped(
    turn: LiveTurn,
    effect: TurnEffect.ToolChip,
    at: Moment,
): LiveTurn {
    if (effect.phase == ToolPhase.START) {
        val chip = LiveChip(effect.tool, running = true, status = effect.status)
        return turn.withCard(at) { it.copy(chips = it.chips + chip) }.copy(tools = turn.tools + chip)
    }
    val stop = { chips: List<LiveChip> -> stopped(chips, effect.tool, effect.status) }
    return turn.withCard(at) { it.copy(chips = stop(it.chips)) }.copy(tools = stop(turn.tools))
}

/** [chips] with the latest running one of [tool] stopped, saying [status]; unchanged when none runs. */
private fun stopped(
    chips: List<LiveChip>,
    tool: String,
    status: String?,
): List<LiveChip> {
    val index = chips.indexOfLast { it.tool == tool && it.running }
    if (index < 0) return chips
    return chips.toMutableList().also { it[index] = it[index].copy(running = false, status = status) }
}

/** The card goes, and the time it showed counts toward "Thought for". */
private fun LiveTurn.cardGone(at: Moment): LiveTurn {
    val shown = card ?: return this
    return copy(card = null, thoughtMs = thoughtMs + (at.monoMs - shown.shownMono).coerceAtLeast(0L))
}

/** Bubble [effect] opens; one the card became keeps that card, [card], until it has words. */
private fun opened(
    turn: LiveTurn,
    effect: TurnEffect.BubbleOpened,
    card: LiveCard?,
): LiveTurn {
    val held = card?.takeIf { effect.fromCard }
    val bubble = LiveBubble(effect.bubble, text = "", resets = 0, fromCard = effect.fromCard, card = held)
    return turn.copy(bubbles = turn.bubbles.filter { it.index != effect.bubble } + bubble)
}

private fun LiveTurn.withBubble(
    index: Int,
    change: (LiveBubble) -> LiveBubble,
): LiveTurn = copy(bubbles = bubbles.map { if (it.index == index) change(it) else it })

/** The bubble's whole text, a reset counted when it no longer extends the text before (a `replace`). */
private fun replaced(
    bubble: LiveBubble,
    text: String,
): LiveBubble {
    val resets = if (text.startsWith(bubble.text)) bubble.resets else bubble.resets + 1
    return bubble.copy(text = text, resets = resets)
}
