package io.tezra.fermix.session

import io.tezra.fermix.protocol.ServerEvent

/** A frame with no raw tail: every event but a blob's chunk. */
private val NO_RAW = ByteArray(0)

/**
 * Where each server event of a reconciled or reconciling connection goes: rows to the timeline, a
 * turn's stream to its machine, receipts and refusals to the outbox, what stands beside them (SideEvents:
 * blobs, approvals, reactions, link previews, one-shots' answers and model changes), and everything the
 * session does not own to the app as it came. An event out of its place is a protocol error.
 */
internal class Dispatch(
    private val core: SessionCore,
    private val live: Live,
    private val requests: Requests,
) {
    private val profile: String get() = core.instance.profileId
    private val sides = SideEvents(core, live)

    /** [event], whose frame carried [raw] after its header: a blob's chunk's bytes, none for any other. */
    suspend fun event(
        event: ServerEvent,
        raw: ByteArray = NO_RAW,
    ) {
        when (event) {
            is ServerEvent.Unknown -> core.log(DiagnosticKind.UNKNOWN_EVENT, event.t)

            is ServerEvent.Row -> if (mine(event.profileId, event)) liveRow(event.toTimelineRow())

            is ServerEvent.TextDone -> textDone(event)

            is ServerEvent.HistoryPage -> page(event)

            is ServerEvent.ReadState -> readState(event)

            is ServerEvent.Accepted -> requests.accepted(event)

            is ServerEvent.Error -> error(event)

            is ServerEvent.HelloAck, is ServerEvent.PairApproved, is ServerEvent.PairDenied,
            is ServerEvent.RequestStatusPage, is ServerEvent.MutationsPage, is ServerEvent.EventPart,
            -> throw SessionProtocolError("${nameOf(event)} out of its place")

            is ServerEvent.Known -> if (!sides.take(event, raw)) other(event)
        }
    }

    /**
     * Whether [event] is this session's profile's. The daemon has one profile today (PROTOCOL.md); a
     * second one's events would be another session's, so they are noted and left, not refused.
     */
    private fun mine(
        profileId: String,
        event: ServerEvent,
    ): Boolean {
        val mine = profileId == profile
        if (!mine) core.log(DiagnosticKind.OTHER_PROFILE, "${nameOf(event)} for $profileId")
        return mine
    }

    private suspend fun liveRow(row: TimelineRow) {
        when (core.timeline().live(row)) {
            RowFate.APPLIED -> live.report()
            RowFate.GAP -> live.pullForward()
            RowFate.DROPPED -> Unit
        }
    }

    /**
     * The bubble seals, then its row is the timeline's like any other. A command's answer the daemon wrote
     * inline (SessionCore.answersCommandInline) also ends its turn: the daemon answers `/stop` before its queue,
     * and no `turn_done` follows such an answer, so the turn would otherwise hold every later `msg` back. A
     * `turn_done` that does come after it is a late one, and ignored.
     */
    private suspend fun textDone(event: ServerEvent.TextDone) {
        val inline = core.answersCommandInline(event.turnId)
        core.turns { it.apply(TurnEvent.TextDone(event.turnId, event.serverSeq)) }
        if (inline) core.turns { it.apply(TurnEvent.TurnDone(event.turnId)) }
        liveRow(event.toTimelineRow())
    }

    /**
     * A page's rows are the ones its pull asked for, after its `after_seq` or before its `before_seq`
     * (PROTOCOL.md "History pages"), so each forward pull starts past the one before and the pulls end.
     */
    private suspend fun page(page: ServerEvent.HistoryPage) {
        val pull = live.answered()
        if (page.profileId != profile) throw SessionProtocolError("a history_page for ${page.profileId}")
        val stray = page.messages.firstOrNull { !pull.holds(it.serverSeq) }
        if (stray != null) throw SessionProtocolError("a ${pull.kind} history_page holds row ${stray.serverSeq}")
        val rows = page.messages.map { TimelineRow.Message(keptMessage(it)) }
        val timeline = core.timeline()
        if (pull.kind == PullKind.OLDER) {
            core.emit(SessionEvent.OlderLoaded(timeline.older(rows), page.prevBeforeSeq))
            return
        }
        // Each row's ack goes before the next row is announced, within design section 7's 1 s of its own.
        timeline.forward(rows, page.historyHeadSeq) { live.report() }
        // An empty page below the head would only be asked for again; the next live row pulls.
        if (page.messages.isNotEmpty() && timeline.cursor < timeline.head) live.pullForward()
        if (pull.kind == PullKind.NEWEST) core.emit(SessionEvent.OlderLoaded(emptyList(), page.prevBeforeSeq))
        live.checkCaughtUp()
    }

    private suspend fun readState(event: ServerEvent.ReadState) {
        if (!mine(event.profileId, event)) return
        val timeline = core.timeline()
        if (timeline.read(event.readUpToSeq, fromDaemon = true)) {
            core.emit(SessionEvent.ReadFrontier(timeline.readFrontier))
        }
        // A frontier that passed a row never announced lets its ack go: the daemon pushes no row read.
        live.report()
    }

    /**
     * A refusal: of a request the outbox holds, of the fetch its ref names, or with an upload's code of the
     * upload on its way, whose codes name nothing (PROTOCOL.md "Errors"); one that is none of them goes to the
     * app as it came. One naming neither a request nor a ref is never taken for a search's or a pull's (Asked):
     * no code is one only they can get, and `request_backlog_full` comes so for a `msg`.
     */
    private suspend fun error(event: ServerEvent.Error) {
        core.log(DiagnosticKind.REFUSED, event.code)
        val clientMsgId = event.clientMsgId
        if (clientMsgId != null) return requests.failed(clientMsgId, event)
        val taken = if (event.ref != null) live.fetches.refused(event) else live.uploads.refused(event)
        if (!taken) core.emit(SessionEvent.Refused(event))
    }

    private suspend fun other(event: ServerEvent.Known) {
        val turn = turnEventOf(event)
        when {
            turn == null -> core.emit(SessionEvent.Server(event))
            event is ServerEvent.TurnStarted && !mine(event.profileId, event) -> Unit
            else -> core.turns { it.apply(turn) }
        }
    }
}

/**
 * A turn's stream event as its machine's input; `text_done` is the timeline's too, and goes on its own.
 * Protocol v2's `text_delta.replace`, `thought`, `thought_done` and `turn_done` (design section 7, those
 * rows) and `tool_event.status` in place of v1's `detail` are what the machine runs on.
 */
internal fun turnEventOf(event: ServerEvent.Known): TurnEvent? =
    when (event) {
        is ServerEvent.TurnStarted -> TurnEvent.TurnStarted(event.turnId, event.inReplyTo)
        is ServerEvent.TextDelta -> TurnEvent.TextDelta(event.turnId, event.text, event.replace == true)
        is ServerEvent.ToolEvent -> TurnEvent.Tool(event.turnId, event.tool, event.phase, event.status)
        is ServerEvent.Thought -> TurnEvent.Thought(event.turnId, event.text)
        is ServerEvent.ThoughtDone -> TurnEvent.ThoughtDone(event.turnId)
        is ServerEvent.TurnDone -> TurnEvent.TurnDone(event.turnId)
        is ServerEvent.TurnError -> TurnEvent.TurnError(event.turnId, event.code, event.message)
        else -> null
    }
