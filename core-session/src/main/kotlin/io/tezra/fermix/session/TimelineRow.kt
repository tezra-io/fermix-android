package io.tezra.fermix.session

import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.protocol.ServerEvent

/** One row of the profile's timeline as the data layer persists it, keyed by its [serverSeq]. */
sealed interface TimelineRow {
    val serverSeq: ULong

    /** A row as a `history_page` or a live `row` carries it: the exported timeline shape. */
    data class Message(
        val message: HistoryMessage,
    ) : TimelineRow {
        override val serverSeq: ULong get() = message.serverSeq
    }

    /**
     * A reply's bubble as `text_done` seals it at its row. `text_done` carries no role or time; the
     * history page carries the same row whole, as a [Message].
     */
    data class Reply(
        override val serverSeq: ULong,
        val turnId: String,
        val text: String,
        val truncated: Boolean,
        val route: Route?,
    ) : TimelineRow
}

/** A live `row` is the history message with `text` for `content` (PROTOCOL.md "Timeline shapes"). */
internal fun ServerEvent.Row.toTimelineRow(): TimelineRow =
    TimelineRow.Message(
        HistoryMessage(
            serverSeq = serverSeq,
            role = role,
            content = text,
            ts = ts,
            mediaRefs = mediaRefs,
            kind = kind,
            clientMsgId = clientMsgId,
            inReplyTo = inReplyTo,
            metadata = metadata,
            linkPreviews = linkPreviews,
            truncated = truncated,
        ),
    )

internal fun ServerEvent.TextDone.toTimelineRow(): TimelineRow =
    TimelineRow.Reply(serverSeq, turnId, text, truncated == true, route)
