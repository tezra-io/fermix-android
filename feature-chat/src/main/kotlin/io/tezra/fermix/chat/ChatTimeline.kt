package io.tezra.fermix.chat

import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.GroupPosition
import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.UploadProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** The role of the owner's own messages on the wire. */
internal const val USER_ROLE = "user"

/**
 * Everything the chat's list is made of, each read on its own: the cached [rows], newest first, as the
 * timeline gives them; the [outbox] in enqueue order; the [bridged] items `accepted` took out of the outbox
 * whose rows have not come yet; the session's [live] fold; whether the link is [connected]; the row the unread
 * divider stands above, [unreadAt], chosen once as the chat opened (unreadAnchor), none for a chat read to its
 * end; the [requests] the screen remembers sending, by client_msg_id, for "Run again"; [profileId]; the wall
 * clock's now; the owner's [zone]; each attachment's [uploads], by its `attach_id`; and whether the daemon
 * transcribes voice notes (`caps.transcripts`), so a note waits for its words only then.
 */
data class ChatInputs(
    val rows: List<TimelineRow>,
    val outbox: List<OutboxItem>,
    val bridged: List<OutboxItem>,
    val live: ChatLive,
    val connected: Boolean,
    val unreadAt: ULong?,
    val requests: Map<String, ClientEvent>,
    val profileId: String,
    val nowWall: Long,
    val zone: ZoneId,
    val uploads: Map<String, UploadProgress> = emptyMap(),
    val transcripts: Boolean = false,
)

/** An item and where it falls: after row [anchor], at [rank] among what that row is followed by (0 is the row). */
internal data class Placed(
    val anchor: ULong,
    val rank: Int,
    val item: ChatItem,
)

/**
 * The chat's list, newest first, as a reversed list draws it from the bottom (design section 13.5): the
 * rows by `server_seq`, each followed by what came after it with no row of its own (notices, model lines,
 * a turn's "Stopped" or error card); then the requests `accepted` took whose rows have not come yet; then
 * the turns that run, their bubbles and their card; then the outbox. Day headers, the unread divider placed as
 * the chat opened, and the groups within two minutes are laid over that.
 */
fun chatItems(inputs: ChatInputs): List<ChatItem> {
    val ascending = inputs.rows.sortedBy { it.serverSeq }
    val times = rowTimes(ascending, inputs.live)
    val rowItems = ascending.mapNotNull { row -> rowItem(row, times[row.serverSeq], inputs) }
    val rowSeqs = ascending.map { it.serverSeq }.toSet()
    val ordered =
        merged(rowItems, extras(inputs, ascending, rowSeqs)) +
            bridgedItems(inputs, ascending) +
            liveItems(inputs, rowSeqs, ascending) +
            outboxItems(inputs)
    val laidOut = grouped(withDays(withUnread(ordered, inputs.unreadAt), inputs), inputs.nowWall)
    return laidOut.asReversed()
}

/**
 * The row the unread divider stands above (design section 13.5, "placed once on open"): the agent's first
 * row past [frontier] among the [rows] the chat held as it opened, none when it held none. Chosen once, it
 * does not move while the screen is open, and a row that comes while it is open, the owner's own answer among
 * them, gets no divider.
 */
fun unreadAnchor(
    rows: List<TimelineRow>,
    frontier: ULong,
): ULong? =
    rows
        .filter { it.serverSeq > frontier && rowText(it).isNotBlank() }
        .filter { (it as? TimelineRow.Message)?.message?.role != USER_ROLE }
        .minOfOrNull { it.serverSeq }

/** Rows and the items that follow them, by row and then by when each came. */
internal fun merged(
    rows: List<Placed>,
    extras: List<Placed>,
): List<ChatItem> {
    val oldest = rows.firstOrNull()?.anchor
    // Before the oldest row held, an item would stand above rows not loaded yet: it waits for them.
    val kept = if (oldest == null) extras else extras.filter { it.anchor >= oldest }
    return (rows + kept).withIndex().sortedWith(compareBy({ it.value.anchor }, { it.value.rank }, { it.index })).map {
        it.value.item
    }
}

/**
 * The unread divider above the agent's row [unreadAt], the one unreadAnchor chose as the chat opened (design
 * section 13.5): placed once, it does not move while the screen is open, and a chat read to its end has none.
 */
internal fun withUnread(
    items: List<ChatItem>,
    unreadAt: ULong?,
): List<ChatItem> {
    if (unreadAt == null) return items
    val first =
        items.indexOfFirst { item ->
            val message = (item as? ChatItem.Message)?.message
            message != null && message.sender == Sender.Agent && message.seq == unreadAt
        }
    return if (first < 0) items else items.subList(0, first) + ChatItem.Unread + items.subList(first, items.size)
}

/** A day header before the first item of each day, an item with no time of its own in the day before it. */
internal fun withDays(
    items: List<ChatItem>,
    inputs: ChatInputs,
): List<ChatItem> {
    var day: LocalDate? = null
    return items.flatMap { item ->
        val wallMs = (item as? ChatItem.Message)?.message?.wallMs
        val date = wallMs?.let { Instant.ofEpochMilli(it).atZone(inputs.zone).toLocalDate() } ?: day ?: today(inputs)
        val header = if (date != day && item != ChatItem.Unread) ChatItem.Day("day:$date", date) else null
        if (header != null) day = date
        listOfNotNull(header, item)
    }
}

internal fun today(inputs: ChatInputs): LocalDate =
    Instant
        .ofEpochMilli(inputs.nowWall)
        .atZone(inputs.zone)
        .toLocalDate()

/**
 * Each message's place in its group (design section 13.1): next to a message of the same sender within two
 * minutes, with nothing between them, it groups; a message with no time yet, a live bubble or an outbox item,
 * is read as [nowWall].
 */
internal fun grouped(
    items: List<ChatItem>,
    nowWall: Long,
): List<ChatItem> =
    items.mapIndexed { index, item ->
        val message = (item as? ChatItem.Message)?.message ?: return@mapIndexed item
        val before = groupsWith(message, items.getOrNull(index - 1), nowWall)
        val after = groupsWith(message, items.getOrNull(index + 1), nowWall)
        val position =
            when {
                before && after -> GroupPosition.Middle
                before -> GroupPosition.Last
                after -> GroupPosition.First
                else -> GroupPosition.Single
            }
        item.copy(message = message.copy(position = position))
    }

internal val GROUP_WINDOW_MS = FermixShapes.bubbleGroupWindow.inWholeMilliseconds

internal fun groupsWith(
    message: ShownMessage,
    neighbour: ChatItem?,
    nowWall: Long,
): Boolean {
    val other = (neighbour as? ChatItem.Message)?.message ?: return false
    val close = abs((message.wallMs ?: nowWall) - (other.wallMs ?: nowWall)) <= GROUP_WINDOW_MS
    return other.sender == message.sender && close
}
