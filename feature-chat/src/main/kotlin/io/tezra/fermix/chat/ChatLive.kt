package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ModelSource
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.IndicatorPools
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.session.indicatorLine
import io.tezra.fermix.transport.Candidate

/** Turns a chat remembers at most; past it the oldest that ended is let go, and then the oldest. */
internal const val MAX_REMEMBERED_TURNS = 64

/** Requests whose `accepted` or refusal a chat remembers at most; past it the oldest is let go. */
internal const val MAX_REMEMBERED_REQUESTS = 128

/** Notices and model lines a chat remembers at most; past it the oldest is let go. */
internal const val MAX_REMEMBERED_PILLS = 32

/** The id prefix of a turn that answers a request (PROTOCOL.md "Streaming a turn"): `turn-{client_msg_id}`. */
private const val TURN_PREFIX = "turn-"

/**
 * When something happened to a chat: [monoMs] on the monotonic clock durations are read on, [wallMs] in Unix
 * milliseconds for what the owner reads, [path] the scope the session was connected over then, none while it
 * was not, and [newestSeq] the newest row the chat had kept then, after which a line with no row of its own
 * is placed.
 */
data class Moment(
    val monoMs: Long,
    val wallMs: Long,
    val path: Candidate.Scope?,
    val newestSeq: ULong,
)

/** A tool chip on the card: its wire name, whether it runs, and the daemon's `status` word when it stopped. */
data class LiveChip(
    val tool: String,
    val running: Boolean,
    val status: String?,
)

/**
 * The thinking card while it shows (design section 8.2): since [shownMono], with the daemon's [headings], one
 * per line of its latest `thought` snapshot, and its tool [chips] in the order they started. [speaking] is
 * core-session's word on it (SessionEvent.Turn.daemonSpeaking): "what the daemon says comes first", a heading
 * or a running tool, when the line is the opening word.
 */
data class LiveCard(
    val shownMono: Long,
    val headings: List<String>,
    val chips: List<LiveChip>,
    val speaking: Boolean,
)

/**
 * One answer bubble of a turn, counted from 1 as core-session counts it: its whole [text] so far, and
 * [resets], how many times the text was replaced by one that does not extend it (`text_delta.replace`, design
 * section 7), so the renderer, which only appends, starts again. Sealed, it has the row [sealedSeq] that holds
 * its canonical text, and the wall time it was sealed. [fromCard] is whether the card became it, and [card] is
 * that card as it went, which stands in its place while the bubble has no words yet: a bare `text_done` seals
 * it before its row, which holds them, is kept (design section 13.10, item 2: nothing is visibly deleted).
 */
data class LiveBubble(
    val index: Int,
    val text: String,
    val resets: Int,
    val fromCard: Boolean,
    val sealedSeq: ULong? = null,
    val sealedWall: Long? = null,
    val card: LiveCard? = null,
)

/** How a turn ended: its [outcome], when, and the newest row then, after which its error card or notice goes. */
data class TurnEnding(
    val outcome: TurnOutcome,
    val monoMs: Long,
    val wallMs: Long,
    val afterSeq: ULong,
)

/**
 * One turn as the chat shows it and remembers it for Info, in this process alone (design section 8.2: thought
 * text never touches the store; duration and tool count are kept per turn in memory): the [card] while it
 * shows, its [bubbles], every tool it ran, how long the card showed ([thoughtMs]), how it ended, and the
 * [path] its last bubble landed over. [seed] picks the indicator's phrases; it is the turn's id's, so the
 * phrase is the same whatever recomposes the screen.
 */
data class LiveTurn(
    val turnId: String,
    val startedMono: Long,
    val startedWall: Long,
    val card: LiveCard? = null,
    val bubbles: List<LiveBubble> = emptyList(),
    val tools: List<LiveChip> = emptyList(),
    val thoughtMs: Long = 0L,
    val ending: TurnEnding? = null,
    val path: Candidate.Scope? = null,
) {
    val seed: Long get() = turnId.hashCode().toLong()

    /** The request the turn answers: none for a turn of the daemon's own, a job's. */
    val clientMsgId: String? get() = turnId.takeIf { it.startsWith(TURN_PREFIX) }?.removePrefix(TURN_PREFIX)

    val live: Boolean get() = ending == null
}

/** Where the next older page starts (`OlderLoaded.prevBeforeSeq`): unknown until a page said, or none left. */
sealed interface Older {
    data object Unknown : Older

    data class Before(
        val seq: ULong,
    ) : Older

    data object None : Older
}

/**
 * The chat's model as the daemon last said it (`model_changed`): an override, or the config's default; none
 * until one comes in this connection, when `hello_ack`'s `caps.model_state` says it.
 */
data class LiveModel(
    val provider: String,
    val model: String,
    val label: String,
    val source: ModelSource,
)

/** A centred line with no row of its own (design section 8.4): a `notice`, or a model change. */
sealed interface LivePill {
    val wallMs: Long
    val afterSeq: ULong

    data class Notice(
        val text: String,
        override val wallMs: Long,
        override val afterSeq: ULong,
    ) : LivePill

    /** "Switched to {model}", or "Back to the default · {model}", with the daemon's [note] when it sent one. */
    data class ModelChanged(
        val label: String,
        val toDefault: Boolean,
        val note: String?,
        override val wallMs: Long,
        override val afterSeq: ULong,
    ) : LivePill
}

/**
 * What a chat's session told the app that its rows do not hold, folded from the session's events by the
 * app's one collector (core-session's Session.events), for as long as the process lives: the [turns] it
 * shows or showed, when each request was [accepted] (Info's "Delivered"), the requests the daemon [refused]
 * before `accepted`, whose outbox item shows instead of an error card, the notices and model lines, where
 * the next older page starts, the approval cards, and the [model] the daemon last said the chat is on. A
 * value: every event gives a new one.
 */
data class ChatLive(
    val turns: List<LiveTurn> = emptyList(),
    val accepted: Map<String, Long> = emptyMap(),
    val refused: Set<String> = emptySet(),
    val pills: List<LivePill> = emptyList(),
    val older: Older = Older.Unknown,
    val approvals: List<LiveApproval> = emptyList(),
    val model: LiveModel? = null,
) {
    /** The chat after [event], which came [at]. */
    fun after(
        event: SessionEvent,
        at: Moment,
    ): ChatLive =
        when (event) {
            is SessionEvent.Turn -> copy(turns = bounded(turnsAfter(turns, event, at)))
            is SessionEvent.Accepted -> copy(accepted = (accepted + (event.clientMsgId to at.wallMs)).newest())
            is SessionEvent.RequestFailed -> refusing(event).copy(approvals = approvalsAfter(approvals, event, at))
            is SessionEvent.OlderLoaded -> copy(older = event.prevBeforeSeq?.let(Older::Before) ?: Older.None)
            is SessionEvent.ModelChanged -> modelChanged(event, at)
            is SessionEvent.Server -> server(event.event, at)
            else -> copy(approvals = approvalsAfter(approvals, event, at))
        }

    private fun refusing(event: SessionEvent.RequestFailed): ChatLive =
        if (event.inOutbox) refusing(event.clientMsgId) else this

    /** A `notice` is a centred line; a `hello_ack` hands the model back to its `caps.model_state`. */
    private fun server(
        event: ServerEvent.Known,
        at: Moment,
    ): ChatLive =
        when (event) {
            is ServerEvent.Notice -> withPill(LivePill.Notice(event.text, at.wallMs, at.newestSeq))
            is ServerEvent.HelloAck -> copy(model = null)
            else -> this
        }

    /** The chat's model now, and its line: "Switched to {model}", or "Back to the default · {model}". */
    private fun modelChanged(
        event: SessionEvent.ModelChanged,
        at: Moment,
    ): ChatLive {
        val toDefault = event.source == ModelSource.DEFAULT
        val pill = LivePill.ModelChanged(event.label, toDefault, event.note, at.wallMs, at.newestSeq)
        return withPill(pill).copy(model = LiveModel(event.provider, event.model, event.label, event.source))
    }

    private fun refusing(clientMsgId: String): ChatLive =
        copy(refused = (refused - clientMsgId + clientMsgId).toList().takeLast(MAX_REMEMBERED_REQUESTS).toSet())

    private fun withPill(pill: LivePill): ChatLive = copy(pills = (pills + pill).takeLast(MAX_REMEMBERED_PILLS))
}

/** The newest [MAX_REMEMBERED_REQUESTS] entries, in the order they came. */
private fun Map<String, Long>.newest(): Map<String, Long> =
    if (size <= MAX_REMEMBERED_REQUESTS) {
        this
    } else {
        entries.drop(size - MAX_REMEMBERED_REQUESTS).associate { it.toPair() }
    }

/** At most [MAX_REMEMBERED_TURNS]: the oldest turn that ended goes first, and only then a live one. */
private fun bounded(turns: List<LiveTurn>): List<LiveTurn> {
    if (turns.size <= MAX_REMEMBERED_TURNS) return turns
    val oldestEnded = turns.indexOfFirst { !it.live }
    val dropped = if (oldestEnded >= 0) oldestEnded else 0
    return turns.filterIndexed { index, _ -> index != dropped }
}

/**
 * The working indicator's line on a turn's [card] at [nowMono] (design section 8.2): core-session's choice
 * (indicatorLine) by the time since the turn showed at [startedMono], from `accepted` for the phone's own
 * message, its [seed], and whether the daemon speaks on the card, when the line is the opening word.
 */
fun cardLine(
    card: LiveCard,
    startedMono: Long,
    seed: Long,
    nowMono: Long,
    pools: IndicatorPools,
): String = indicatorLine((nowMono - startedMono).coerceAtLeast(0L), seed, card.speaking, pools)

/** A turn's answer that arrived whole while the chat held it: the turn, and its final bubble's words. */
data class Arrival(
    val turnId: String,
    val words: String,
)

/**
 * The turns that completed with a final bubble whose words the chat has, in the order they started: the
 * arrivals the screen plays `CLOCK_TICK` for and TalkBack reads once (design sections 13.1 and 13.8). A
 * bubble's words are its live text, or once a bare `text_done` sealed it, its row's in [rows].
 */
fun arrivals(
    live: ChatLive,
    rows: List<TimelineRow>,
): List<Arrival> =
    live.turns
        .filter { it.ending?.outcome == TurnOutcome.Completed }
        .mapNotNull { turn ->
            val last = turn.bubbles.maxByOrNull { it.index }?.takeIf { it.sealedSeq != null } ?: return@mapNotNull null
            val row = rows.firstOrNull { it.serverSeq == last.sealedSeq }
            val words = last.text.ifEmpty { row?.let(::rowText).orEmpty() }
            words.takeIf { it.isNotBlank() }?.let { Arrival(turn.turnId, it) }
        }

/** A row's words as the daemon wrote them: a message's content, or a reply's text. */
internal fun rowText(row: TimelineRow): String =
    when (row) {
        is TimelineRow.Message -> row.message.content
        is TimelineRow.Reply -> row.text
    }
