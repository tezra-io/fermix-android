package io.tezra.fermix.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MediaRef
import io.tezra.fermix.protocol.MessageKind
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.keptMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * One row of the profile's timeline cache (design section 9.1), keyed by its `server_seq`. A [whole] row is a
 * history message, and [rowJson] is its JSON as core-protocol's model writes it: that is what is read back,
 * so a later protocol that widens the row needs no migration to keep what it carries. The other columns are
 * the same message's fields, kept for queries: the search, and the chips that filter cached media, files and
 * links (design section 13.7). A row that is not whole is a reply's text as `text_done` sealed it, with its
 * turn and route, the link previews that came for it, and no role, time or metadata, until the history page
 * brings the row whole.
 */
@Entity(tableName = "timeline")
internal data class TimelineEntity(
    @PrimaryKey @ColumnInfo(name = "server_seq") val serverSeq: Long,
    @ColumnInfo(name = "whole") val whole: Boolean,
    @ColumnInfo(name = "role") val role: String?,
    @ColumnInfo(name = "kind") val kind: String?,
    @ColumnInfo(name = "ts") val ts: String?,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "client_msg_id") val clientMsgId: String?,
    @ColumnInfo(name = "in_reply_to") val inReplyTo: String?,
    @ColumnInfo(name = "media_refs") val mediaRefs: String?,
    @ColumnInfo(name = "metadata") val metadata: String?,
    @ColumnInfo(name = "link_previews") val linkPreviews: String?,
    @ColumnInfo(name = "truncated") val truncated: Boolean?,
    @ColumnInfo(name = "turn_id") val turnId: String?,
    @ColumnInfo(name = "route") val route: String?,
    @ColumnInfo(name = "row_json") val rowJson: String?,
)

/**
 * The full-text index of the cached rows' content: the offline search of design section 13.7. It reads
 * [TimelineEntity]'s table, and the triggers Room writes for it keep it in step with every insert, update
 * and delete there. The unicode61 tokenizer folds case and diacritics, so "cafe" finds "Café".
 */
@Fts4(contentEntity = TimelineEntity::class, tokenizer = FTS_TOKENIZER)
@Entity(tableName = "timeline_fts")
internal data class TimelineFts(
    @ColumnInfo(name = "content") val content: String,
)

internal fun TimelineRow.toEntity(): TimelineEntity =
    when (this) {
        is TimelineRow.Message -> message.toEntity()
        is TimelineRow.Reply -> toEntity()
    }

private fun HistoryMessage.toEntity(): TimelineEntity =
    TimelineEntity(
        serverSeq = serverSeq.toColumn(),
        whole = true,
        role = role,
        kind = kind?.let { STORED_JSON.encodeToJsonElement(serializer<MessageKind>(), it).jsonPrimitive.content },
        ts = ts,
        content = content,
        clientMsgId = clientMsgId,
        inReplyTo = inReplyTo,
        mediaRefs = STORED_JSON.encodeToString(serializer<List<MediaRef>>(), mediaRefs),
        metadata = metadata?.let { STORED_JSON.encodeToString(serializer<JsonObject>(), it) },
        linkPreviews = linkPreviews?.let { STORED_JSON.encodeToString(serializer<List<LinkPreviewCard>>(), it) },
        truncated = truncated,
        turnId = null,
        route = null,
        rowJson = STORED_JSON.encodeToString(serializer<HistoryMessage>(), this),
    )

private fun TimelineRow.Reply.toEntity(): TimelineEntity =
    TimelineEntity(
        serverSeq = serverSeq.toColumn(),
        whole = false,
        role = null,
        kind = null,
        ts = null,
        content = text,
        clientMsgId = null,
        inReplyTo = null,
        mediaRefs = null,
        metadata = null,
        linkPreviews = linkPreviews?.let { STORED_JSON.encodeToString(serializer<List<LinkPreviewCard>>(), it) },
        truncated = truncated,
        turnId = turnId,
        route = route?.let { STORED_JSON.encodeToString(serializer<Route>(), it) },
        rowJson = null,
    )

internal fun TimelineEntity.toRow(): TimelineRow {
    if (!whole) return toReply()
    val json = checkNotNull(rowJson) { "row $serverSeq has no JSON" }
    val message = STORED_JSON.decodeFromString(serializer<HistoryMessage>(), json)
    check(message.serverSeq == serverSeq.toSeq()) { "row $serverSeq holds row ${message.serverSeq}" }
    return TimelineRow.Message(message)
}

private fun TimelineEntity.toReply(): TimelineRow.Reply =
    TimelineRow.Reply(
        serverSeq = serverSeq.toSeq(),
        turnId = checkNotNull(turnId) { "reply $serverSeq has no turn" },
        text = content,
        truncated = checkNotNull(truncated) { "reply $serverSeq does not say whether it was cut" },
        route = route?.let { STORED_JSON.decodeFromString(serializer<Route>(), it) },
        linkPreviews = linkPreviews?.let { STORED_JSON.decodeFromString(serializer<List<LinkPreviewCard>>(), it) },
    )

/**
 * [mutation] applied to the row it names: each field it carries replaces the row's (design section 7, the
 * `mutation_seq` row), and the row is kept as core-session keeps every row, an approval's answer without its
 * words (keptMessage). A reply takes the new content as its text; it has no metadata or media of its own, and
 * the history page that brings the row whole brings the row as it stands.
 */
internal fun TimelineRow.mutated(mutation: MutationRow): TimelineRow {
    require(mutation.serverSeq == serverSeq) { "mutation of row ${mutation.serverSeq} applied to row $serverSeq" }
    return when (this) {
        is TimelineRow.Message -> {
            val changed =
                message.copy(
                    content = mutation.content ?: message.content,
                    metadata = mutation.metadata ?: message.metadata,
                    mediaRefs = mutation.mediaRefs ?: message.mediaRefs,
                )
            TimelineRow.Message(keptMessage(changed))
        }

        is TimelineRow.Reply -> {
            copy(text = mutation.content ?: text)
        }
    }
}
