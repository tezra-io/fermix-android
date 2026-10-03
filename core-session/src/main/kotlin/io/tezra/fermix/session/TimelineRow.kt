package io.tezra.fermix.session

import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
     * history page carries the same row whole, as a [Message]. A `link_preview` that came before the row
     * did is kept in [linkPreviews] (SessionStore.addLinkPreview).
     */
    data class Reply(
        override val serverSeq: ULong,
        val turnId: String,
        val text: String,
        val truncated: Boolean,
        val route: Route?,
        val linkPreviews: List<LinkPreviewCard>? = null,
    ) : TimelineRow
}

/**
 * A live `row` is the history message with `text` for `content` (PROTOCOL.md "Timeline shapes"), kept as the
 * phone keeps every row (keptMessage).
 */
internal fun ServerEvent.Row.toTimelineRow(): TimelineRow =
    TimelineRow.Message(
        keptMessage(
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
        ),
    )

internal fun ServerEvent.TextDone.toTimelineRow(): TimelineRow =
    TimelineRow.Reply(serverSeq, turnId, text, truncated == true, route)

/** The key of the owner's row's metadata that holds the daemon's reaction (design section 7, `reaction` durability). */
const val REACTION_KEY = "reaction"

/** The link previews a row has at most (PROTOCOL.md "Link previews"). */
const val MAX_LINK_PREVIEWS = 4

/**
 * The owner's row with [emoji] as its reaction, `{"emoji": …}` under [REACTION_KEY], its other metadata kept:
 * what a `reaction` puts on the cached row before the mutation that persists it brings its `ts`.
 */
fun TimelineRow.Message.withReaction(emoji: String): TimelineRow.Message {
    require(emoji.isNotEmpty()) { "a reaction has an emoji" }
    val reaction = JsonObject(mapOf("emoji" to JsonPrimitive(emoji)))
    val metadata = JsonObject(message.metadata.orEmpty() + (REACTION_KEY to reaction))
    return TimelineRow.Message(message.copy(metadata = metadata))
}

/** The link previews [row] holds: a message's from its history or a `link_preview`, a reply's from the latter. */
fun linkPreviewsOf(row: TimelineRow): List<LinkPreviewCard> =
    when (row) {
        is TimelineRow.Message -> row.message.linkPreviews.orEmpty()
        is TimelineRow.Reply -> row.linkPreviews.orEmpty()
    }

/** [row] with [card] among its link previews, after the ones it holds: once per url, and at most four. */
fun withLinkPreview(
    row: TimelineRow,
    card: LinkPreviewCard,
): TimelineRow {
    val held = linkPreviewsOf(row)
    val kept = if (held.any { it.url == card.url } || held.size >= MAX_LINK_PREVIEWS) held else held + card
    return when (row) {
        is TimelineRow.Message -> TimelineRow.Message(row.message.copy(linkPreviews = kept))
        is TimelineRow.Reply -> row.copy(linkPreviews = kept)
    }
}
