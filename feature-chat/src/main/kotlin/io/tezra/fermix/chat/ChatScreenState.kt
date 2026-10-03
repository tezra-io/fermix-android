package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.CommandDescriptor
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.TimelineRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Chat screen as it reads, built from its inputs alone (chatScreenState): its bar, the banner once it has
 * held 2 s, the list newest first, the [name] the empty chat greets (the instance's, as the visual canon draws
 * it), whether a turn runs (the stop control), the daemon's commands for the palette, the newest row the list
 * holds (what the screen reports to the announcer and reads up to at the bottom), the scroll pill's count of
 * the agent's rows past what the owner has seen, whether older rows may be loaded above, and the model the
 * owner chose for the chat, which a `model_unavailable` card names; the owner's zone and today, which the
 * day headers and the times are read in; the answers that [arrived] whole, which the screen plays and
 * reads once each; the model chip, none when the daemon says no model; and whether a model picked during the
 * running turn waits for it ("Switches after this reply").
 */
data class ChatScreenState(
    val header: ChatHeader,
    val banner: Banner?,
    val items: List<ChatItem>,
    val name: String,
    val turnRuns: Boolean,
    val commands: List<CommandDescriptor>,
    val newestSeq: ULong?,
    val unseen: Int,
    val older: Boolean,
    val chosenModel: String?,
    val zone: ZoneId,
    val today: LocalDate,
    val arrived: List<Arrival>,
    val model: ModelChip? = null,
    val switchPending: Boolean = false,
)

/** The chat's facts besides its list: the bar and the banner. */
data class ChatFacts(
    val header: ChatHeader,
    val banner: Banner?,
)

/**
 * The screen from [facts] and [inputs]: [seenUpTo] is the newest row the owner has seen at the bottom, [limit]
 * how many rows were asked of the cache, so a cache that gave fewer holds no more, and [loadingOlder] whether
 * the daemon's older page is on its way, whose skeleton then stands above the oldest row.
 */
fun chatScreenState(
    facts: ChatFacts,
    inputs: ChatInputs,
    seenUpTo: ULong?,
    limit: Int,
    loadingOlder: Boolean,
): ChatScreenState {
    val items = chatItems(inputs) + listOfNotNull(ChatItem.Older.takeIf { loadingOlder })
    val newest = inputs.rows.maxOfOrNull { it.serverSeq }
    val oldest = inputs.rows.minOfOrNull { it.serverSeq }
    val more = inputs.rows.size >= limit || (inputs.live.older != Older.None && oldest != null && oldest > 1uL)
    val caps = facts.header.record.caps
    val chip = modelChipOf(inputs.live.model, caps?.modelState, inputs.connected)
    return ChatScreenState(
        header = facts.header,
        banner = facts.banner,
        items = items,
        name = facts.header.record.title,
        turnRuns = inputs.live.turns.any { it.live },
        commands =
            facts.header.record.caps
                ?.commands
                .orEmpty(),
        newestSeq = newest,
        unseen = unseen(items, seenUpTo),
        older = more,
        chosenModel = chip?.takeIf { it.overridden }?.label,
        zone = inputs.zone,
        today = Instant.ofEpochMilli(inputs.nowWall).atZone(inputs.zone).toLocalDate(),
        arrived = arrivals(inputs.live, inputs.rows),
        model = chip,
    )
}

/** The agent's rows past [seenUpTo] the list holds: the scroll pill's count (design section 13.5). */
fun unseen(
    items: List<ChatItem>,
    seenUpTo: ULong?,
): Int =
    items.count { item ->
        val message = (item as? ChatItem.Message)?.message
        val seq = message?.seq
        message?.sender == Sender.Agent && seq != null && (seenUpTo == null || seq > seenUpTo)
    }

/** At most this many items `accepted` took out of the outbox are bridged until their rows come. */
internal const val MAX_BRIDGED = 64

/**
 * The owner's messages bridged from the outbox to their rows (design section 13.5: the bubble stays, its
 * clock a tick): what [before] held that [now] does not, a `msg` the daemon took, kept with [bridged] until
 * its row is in [rows]. One the owner took back ([withdrawn]: Edit, Remove, a retry) is not.
 */
fun bridgedAfter(
    bridged: List<OutboxItem>,
    before: List<OutboxItem>,
    now: List<OutboxItem>,
    rows: List<TimelineRow>,
    withdrawn: Set<String>,
): List<OutboxItem> {
    val held = now.map { it.clientMsgId }.toSet()
    val landed = rows.mapNotNull { (it as? TimelineRow.Message)?.message?.clientMsgId }.toSet()
    val left =
        before.filter { item ->
            item.clientMsgId !in held && item.clientMsgId !in withdrawn && item.request is ClientEvent.Msg
        }
    return (bridged + left)
        .distinctBy { it.clientMsgId }
        .filter { it.clientMsgId !in landed && it.clientMsgId !in withdrawn }
        .takeLast(MAX_BRIDGED)
}
