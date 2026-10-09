package io.tezra.fermix.demo

import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.RequestState
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.ToolPhase
import io.tezra.fermix.session.turnIdOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The code a stopped turn ends with (PROTOCOL.md "Streaming a turn"). */
private const val CANCELLED = "cancelled"

/** The emoji the agent reacts with. */
private const val THUMBS_UP = "👍"

/** A resumed request's id: the engine re-ingests the request a grant held up as a message of its own. */
private const val RESUME_PREFIX = "grant-resume-"

/** The heading of a resumed request's card. */
private const val RESUMING = "Picking up your request"

/**
 * A turn as it started: its id, the route it runs on, fixed then, so a model picked meanwhile applies to the next
 * turn (design section 8.6), and whether that route streams.
 */
private class Running(
    val turnId: String,
    val route: Route,
    val streams: Boolean,
)

/**
 * The turns of one demo Fermix (design section 8.2): each a coroutine of the demo's scope, kept by the request it
 * answers until it ends, so a reconnect lists it in `active_turns` and its stream goes on to whichever phones are
 * connected; how each plays is [TurnPlay]'s. A reply whose words call for the owner's OK raises an approval and ends
 * saying so; the owner's grant runs the request again as a turn of its own ([resume]). A stop or a cancel ends a
 * turn `cancelled`.
 */
internal class DemoTurns(
    private val home: DemoHome,
    private val parts: DemoParts,
) {
    private val play = TurnPlay(home, parts)

    /** Answers the owner's message [requestId], [words], with a reply of the Fermix's seeded script. */
    fun reply(
        requestId: String,
        words: String,
        media: Boolean,
    ) {
        val plan = replyPlan(home.random, requestId, words, media)
        start(requestId) { play.reply(requestId, plan) }
    }

    /** Plays [turn], the script's long one, its headings spread over [DemoTimes.longTurn]. */
    fun long(turn: LongTurn) {
        home.claims[turn.requestId] = Claim(turn.requestId, RequestState.RUNNING, phone = null)
        start(turn.requestId) { play.long(turn) }
    }

    /**
     * Runs again the request an approval held up, once the owner granted it: as the engine re-ingests it, a message
     * of its own with no request row behind it (`grant-resume-N`), whose turn answers with [answer].
     */
    fun resume(answer: String) {
        home.resumes++
        val requestId = RESUME_PREFIX + home.resumes
        start(requestId) { play.resumed(requestId, answer) }
    }

    /** Stops [requestId]'s turn, which ends `cancelled`; a request with no turn running changes nothing. */
    fun cancel(requestId: String): Boolean {
        val job = home.turns.remove(requestId) ?: return false
        job.cancel()
        home.claims[requestId]?.apply {
            state = RequestState.FAILED
            error = CANCELLED
        }
        home.broadcast(ServerEvent.TurnError(turnIdOf(requestId), CANCELLED, "Stopped"))
        return true
    }

    /** Stops every turn running; how many there were. */
    fun stopAll(): Int =
        home.turns.keys
            .toList()
            .count(::cancel)

    private fun start(
        requestId: String,
        body: suspend () -> Unit,
    ) {
        home.claims[requestId]?.state = RequestState.RUNNING
        val job =
            parts.scope.launch(start = CoroutineStart.LAZY) {
                try {
                    body()
                } finally {
                    home.turns.remove(requestId)
                }
            }
        home.turns[requestId] = job
        job.start()
    }
}

/**
 * How a demo turn plays, on the route it started on. On a route that streams a reply opens with `turn_started`,
 * shows its headings and its tool on the card, takes the card away, streams its words in deltas over
 * [DemoTimes.streaming], seals them as its row with `text_done` and ends with `turn_done`; on one that does not (a
 * model listed `streams: false`) it sends no `turn_started`, no heading, no tool and no delta, only the ending, the
 * phone's card shown from `accepted` (R1; PROTOCOL.md "Streaming a turn").
 */
private class TurnPlay(
    private val home: DemoHome,
    private val parts: DemoParts,
) {
    private val times = parts.times

    /** [requestId]'s turn as it starts now: `turn_started` when its route streams. */
    private fun opened(requestId: String): Running {
        val turn = Running(turnIdOf(requestId), home.route(), home.streams())
        if (turn.streams) home.broadcast(ServerEvent.TurnStarted(MAIN, turn.turnId, requestId))
        return turn
    }

    suspend fun reply(
        requestId: String,
        plan: ReplyPlan,
    ) {
        val turn = opened(requestId)
        if (plan.react) react(home, requestId, stamp(parts.wallMs()))
        think(turn, requestId, plan.headings, listOfNotNull(plan.tool), times.heading)
        val ask = plan.ask
        if (ask != null) raise(home, parts, ask, times.askTtl, plan.answer)
        answer(turn, requestId, if (ask != null) ASKED else plan.answer)
    }

    suspend fun long(turn: LongTurn) {
        val running = opened(turn.requestId)
        think(running, turn.requestId, turn.headings, turn.tools, times.longTurn / turn.headings.size)
        answer(running, turn.requestId, turn.answer)
    }

    suspend fun resumed(
        requestId: String,
        answer: String,
    ) {
        val turn = opened(requestId)
        think(turn, requestId, listOf(RESUMING), emptyList(), times.heading)
        answer(turn, requestId, answer)
    }

    /**
     * The card's thinking: [DemoTimes.thinking], then each of [headings] for [step], the first's [tools] run on the
     * card, and `thought_done`; on a route that does not stream, the same time with nothing sent.
     */
    private suspend fun think(
        turn: Running,
        requestId: String,
        headings: List<String>,
        tools: List<String>,
        step: Long,
    ) {
        delay(times.thinking)
        headings.forEachIndexed { index, heading ->
            if (turn.streams) home.broadcast(ServerEvent.Thought(turn.turnId, requestId, heading))
            tools.getOrNull(index)?.let { run(turn, it) }
            delay(step)
        }
        if (turn.streams) home.broadcast(ServerEvent.ThoughtDone(turn.turnId))
    }

    /** A tool's chip on the card, started, run for [DemoTimes.tool] and stopped; on a quiet route, the time alone. */
    private suspend fun run(
        turn: Running,
        tool: String,
    ) {
        if (turn.streams) home.broadcast(ServerEvent.ToolEvent(turn.turnId, tool, ToolPhase.START))
        delay(times.tool)
        if (turn.streams) home.broadcast(ServerEvent.ToolEvent(turn.turnId, tool, ToolPhase.STOP, status = "ok"))
    }

    /** [text] streamed when the route streams, sealed as its row, a link previewed, and the turn done. */
    private suspend fun answer(
        turn: Running,
        requestId: String,
        text: String,
    ) {
        if (turn.streams) stream(turn.turnId, text)
        val metadata = JsonObject(mapOf("turn_id" to JsonPrimitive(turn.turnId), "route" to routeJson(turn.route)))
        val row = home.write(HistoryMessage(0uL, AGENT, text, stamp(parts.wallMs()), emptyList(), metadata = metadata))
        home.claims[requestId]?.apply {
            state = RequestState.COMPLETED
            resultSeq = row.serverSeq
        }
        home.broadcast(ServerEvent.TextDone(turn.turnId, row.serverSeq, text, route = turn.route))
        if (GUIDE_URL in text) preview(home, row.serverSeq, parts.blobs.getValue(DemoBlobName.PREVIEW))
        home.broadcast(ServerEvent.TurnDone(turn.turnId))
    }

    /** [text] in deltas, the most that [DemoTimes.streaming] holds at one per [DemoTimes.delta], evenly cut. */
    private suspend fun stream(
        turnId: String,
        text: String,
    ) {
        val pieces = (times.streaming / times.delta).toInt().coerceIn(1, text.length.coerceAtLeast(1))
        val size = (text.length + pieces - 1) / pieces
        piecesOf(text, size).forEach { piece ->
            home.broadcast(ServerEvent.TextDelta(turnId, piece))
            delay(times.delta)
        }
    }
}

/**
 * [text] cut into pieces of [size] UTF-16 units, a piece one unit longer where a cut would part a surrogate pair:
 * the wire's UTF-8 has no half of one, which the encoder would write as '?'. Each pass takes at least one unit, so
 * there are at most as many passes as units.
 */
internal fun piecesOf(
    text: String,
    size: Int,
): List<String> {
    require(size > 0) { "a piece holds at least one unit" }
    val pieces = mutableListOf<String>()
    var from = 0
    while (from < text.length) {
        var to = minOf(from + size, text.length)
        if (to < text.length && text[to - 1].isHighSurrogate()) to++
        pieces += text.substring(from, to)
        from = to
    }
    return pieces
}

private fun routeJson(route: Route): JsonObject =
    JsonObject(mapOf("provider" to JsonPrimitive(route.provider), "model" to JsonPrimitive(route.model)))

/** The agent's reaction to the owner's message [requestId], told live and kept on its row as a change in place. */
private fun react(
    home: DemoHome,
    requestId: String,
    nowStamp: String,
) {
    val row = home.rows.lastOrNull { it.clientMsgId == requestId } ?: return
    val reaction = JsonObject(mapOf("emoji" to JsonPrimitive(THUMBS_UP), "ts" to JsonPrimitive(nowStamp)))
    home.change(row.serverSeq, JsonObject(row.metadata.orEmpty() + ("reaction" to reaction)))
    home.broadcast(ServerEvent.Reaction(requestId, THUMBS_UP))
}

/** The guide's preview on row [serverSeq], stored on it, then told to the phones (PROTOCOL.md "Link previews"). */
private fun preview(
    home: DemoHome,
    serverSeq: ULong,
    image: DemoBlob,
) {
    val card =
        LinkPreviewCard(
            url = GUIDE_URL,
            site = "hexdocs.pm",
            title = "Task — Elixir",
            description = "Conveniences for spawning and awaiting tasks.",
            imageRef = image.ref,
        )
    home.addPreview(serverSeq, card)
    home.broadcast(ServerEvent.LinkPreview(serverSeq, card.url, card.site, card.title, card.description, card.imageRef))
}
