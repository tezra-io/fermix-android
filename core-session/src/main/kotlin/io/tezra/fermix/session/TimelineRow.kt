package io.tezra.fermix.session

import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.Place
import io.tezra.fermix.protocol.ReplyBlock
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
     * A reply's bubble as `text_done` seals it at its row, with the [blocks] its row carries. `text_done` carries no
     * role or time; the history page carries the same row whole, as a [Message]. A `link_preview` that came before
     * the row did is kept in [linkPreviews] (SessionStore.addLinkPreview).
     */
    data class Reply(
        override val serverSeq: ULong,
        val turnId: String,
        val text: String,
        val truncated: Boolean,
        val route: Route?,
        val linkPreviews: List<LinkPreviewCard>? = null,
        val blocks: List<ReplyBlock>? = null,
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
                blocks = blocks,
                truncated = truncated,
            ),
        ),
    )

internal fun ServerEvent.TextDone.toTimelineRow(): TimelineRow =
    TimelineRow.Reply(serverSeq, turnId, text, truncated == true, route, blocks = blocks)

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

/**
 * The blocks [row] holds (the companion PROTOCOL.md "Blocks under a reply"), each as it came, a type this app does
 * not know among them: a message's from its history or its `row`, a reply's from its `text_done`.
 */
fun blocksOf(row: TimelineRow): List<ReplyBlock> =
    when (row) {
        is TimelineRow.Message -> row.message.blocks.orEmpty()
        is TimelineRow.Reply -> row.blocks.orEmpty()
    }

/** The places [row]'s one places block holds, in the order its text first links them; none without one. */
fun placesOf(row: TimelineRow): List<Place> =
    blocksOf(row)
        .filterIsInstance<ReplyBlock.Places>()
        .firstOrNull()
        ?.places
        .orEmpty()

/**
 * The Diagnostics' one line for the blocks of [blocks] whose type this app does not know, which it keeps on their
 * rows and never draws (the companion PROTOCOL.md "Blocks under a reply"); null when there is none. Each type is
 * the daemon's text, cut and kept to printable ASCII, and named once.
 */
internal fun skippedBlocksNote(blocks: List<ReplyBlock>): String? {
    val skipped = blocks.filterIsInstance<ReplyBlock.Skipped>()
    if (skipped.isEmpty()) return null
    val types = skipped.map { it.type?.let(::printable) ?: "a type that is not text" }.distinct()
    return "skipped ${skipped.size} block(s) of a type this app does not know: ${types.joinToString()}"
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
