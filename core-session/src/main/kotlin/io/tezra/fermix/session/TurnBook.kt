package io.tezra.fermix.session

import io.tezra.fermix.protocol.ActiveTurn

/**
 * The most turns a book shows at once. The daemon runs one request at a time per connection and holds
 * 32 more, and scheduled jobs add a few; past this the oldest turn is ended as over, so a daemon that
 * never ends its turns cannot grow the book without end.
 */
internal const val MAX_LIVE_TURNS = 64

/** How many ended turns a book remembers, so their late events are ignored rather than opening a card. */
private const val MAX_ENDED_TURNS = 128

/** A shown turn: its machine's state, and the request it answers when the wire named it. */
private data class LiveTurn(
    val state: TurnState,
    val inReplyTo: String?,
)

data class TurnBookStep(
    val book: TurnBook,
    val effects: List<TurnEffect>,
)

/**
 * Every turn's machine, keyed by turn id: one machine per turn (design section 8.2), so a cron or
 * background turn's `turn_started` gets its own beside the phone's. A value: every event gives a new
 * book and the effects to show.
 */
class TurnBook private constructor(
    private val live: Map<String, LiveTurn>,
    private val ended: List<String>,
) {
    constructor() : this(emptyMap(), emptyList())

    fun stateOf(turnId: String): TurnState =
        if (turnId in ended) TurnState.Ended else live[turnId]?.state ?: TurnState.Idle

    fun apply(event: TurnEvent): TurnBookStep {
        if (event.turnId in ended) return TurnBookStep(this, emptyList())
        val current = live[event.turnId]
        val step = reduceTurn(current?.state ?: TurnState.Idle, event)
        return place(event.turnId, LiveTurn(step.state, requestOf(event) ?: current?.inReplyTo), step.effects)
    }

    /**
     * A reconnect's view of the turns (design section 8.2, "Reconnect reconciliation"): every shown
     * turn that [active] does not list is over, a scheduled job's included, since a job has no request
     * row; every listed turn not shown yet gets its card. An over turn is not remembered as ended, so
     * its own events open it again when it was only queued (TurnEvent.Over).
     */
    fun reconcile(active: List<ActiveTurn>): TurnBookStep {
        val listed = active.map { it.turnId }.toSet()
        val over = live.keys.filter { it !in listed }.map { TurnEvent.Over(it) }
        val shown = active.filter { it.turnId !in live }.map { TurnEvent.Active(it.turnId, it.inReplyTo) }
        return (over + shown).fold(TurnBookStep(this, emptyList())) { step, event ->
            val next = step.book.apply(event)
            TurnBookStep(next.book, step.effects + next.effects)
        }
    }

    /** The client_msg_ids of the requests the shown turns answer, for `request_status`. */
    fun requestIds(): List<String> = live.values.mapNotNull { it.inReplyTo }.distinct()

    private fun place(
        turnId: String,
        turn: LiveTurn,
        effects: List<TurnEffect>,
    ): TurnBookStep =
        when {
            turn.state == TurnState.Ended -> TurnBookStep(TurnBook(live - turnId, remember(turnId)), effects)
            turn.state == TurnState.Idle -> TurnBookStep(TurnBook(live - turnId, ended), effects)
            turnId in live || live.size < MAX_LIVE_TURNS -> TurnBookStep(withTurn(turnId, turn), effects)
            else -> evictOldest().let { TurnBookStep(it.book.withTurn(turnId, turn), it.effects + effects) }
        }

    private fun withTurn(
        turnId: String,
        turn: LiveTurn,
    ): TurnBook = TurnBook(live + (turnId to turn), ended)

    /** Ends the turn shown longest as over, to make room. */
    private fun evictOldest(): TurnBookStep {
        val oldest = live.keys.first()
        val step = reduceTurn(live.getValue(oldest).state, TurnEvent.Over(oldest))
        return TurnBookStep(TurnBook(live - oldest, remember(oldest)), step.effects)
    }

    private fun remember(turnId: String): List<String> = (ended + turnId).takeLast(MAX_ENDED_TURNS)
}

/** The request an event names for its turn, when it names one. */
private fun requestOf(event: TurnEvent): String? =
    when (event) {
        is TurnEvent.Accepted -> event.clientMsgId
        is TurnEvent.TurnStarted -> event.inReplyTo
        is TurnEvent.Active -> event.inReplyTo
        else -> null
    }
