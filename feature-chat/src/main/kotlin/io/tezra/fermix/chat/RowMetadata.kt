package io.tezra.fermix.chat

import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.session.REACTION_KEY
import io.tezra.fermix.session.TimelineRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.ParsePosition
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun wallOf(row: TimelineRow.Message): Long? = parseInstant(row.message.ts)?.toEpochMilli()

/** An RFC 3339 time as the daemon writes it (`ts`), none for one that is not. */
internal fun parseInstant(stamp: String): Instant? {
    val position = ParsePosition(0)
    DateTimeFormatter.ISO_INSTANT.parseUnresolved(stamp, position)
    val readable = position.errorIndex < 0 && position.index == stamp.length
    return if (readable) Instant.parse(stamp) else null
}

/** The row metadata's turn id (design section 7: a reply's row names its turn). */
internal const val TURN_ID_KEY = "turn_id"

/** The row metadata a scheduled job's delivery carries, provisional: no row of design section 7 names it. */
internal const val JOB_KEY = "job"

/** The row metadata's route, the model that wrote it (design section 7, the `text_done.route` row). */
internal const val ROUTE_KEY = "route"

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

/** The job a delivery came from: `metadata.job`, its name or an object holding one. */
internal fun jobOf(metadata: JsonObject): String? =
    metadata.string(JOB_KEY) ?: (metadata[JOB_KEY] as? JsonObject)?.string("name")

/** The daemon's reaction on the owner's row: `metadata.reaction.emoji` (design section 7, `reaction` durability). */
internal fun reactionOf(metadata: JsonObject): String? =
    (metadata[REACTION_KEY] as? JsonObject)?.string("emoji")?.takeIf { it.isNotEmpty() }

internal fun routeOf(metadata: JsonObject): Route? {
    val route = metadata[ROUTE_KEY] as? JsonObject
    val provider = route?.string("provider")
    val model = route?.string("model")
    return if (provider != null && model != null) Route(provider, model) else null
}

/** The link previews a message shows, under it (design section 13.5): at most two. */
internal const val MAX_SHOWN_PREVIEWS = 2

/** The previews a message shows: its first two web addresses; a preview of any other scheme is never shown. */
internal fun shownPreviews(previews: List<LinkPreviewCard>): List<LinkPreviewCard> =
    previews.filter { isWebLink(it.url) }.take(MAX_SHOWN_PREVIEWS)
