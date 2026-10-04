package io.tezra.fermix.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.withLinkPreview
import io.tezra.fermix.session.withReaction
import kotlinx.coroutines.flow.Flow

/** The id of the one cursors row, which the database's creation writes (ProfileDatabase.kt). */
internal const val CURSORS_ROW = 0

/**
 * core-session's StoredCursors for one (instance, profile): one row, made with every cursor 0 when the
 * database is created, so that every write of a cursor is an update of that row.
 */
@Entity(tableName = "cursors")
internal data class CursorsEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "last_server_seq") val lastServerSeq: Long,
    @ColumnInfo(name = "read_up_to_seq") val readUpToSeq: Long,
    @ColumnInfo(name = "last_mutation_seq") val lastMutationSeq: Long,
    @ColumnInfo(name = "announced_up_to_seq") val announcedUpToSeq: Long,
    @ColumnInfo(name = "last_unannounced_seq") val lastUnannouncedSeq: Long,
)

/**
 * core-session's OutboxItem, keyed by its `client_msg_id`, in the order it was enqueued: [position] is one
 * past the largest the outbox holds. [request] is the `msg` or `command` as core-protocol's model writes it,
 * a failed item has its failure's code and message, and [written] says its frame went to a socket once. A
 * `msg`'s [attachments] are a JSON list of StoredAttachment, `[]` for none, and [uploadStarts] counts the
 * connections that began uploading them.
 */
@Entity(tableName = "outbox")
internal data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "client_msg_id") val clientMsgId: String,
    @ColumnInfo(name = "position") val position: Long,
    @ColumnInfo(name = "request") val request: String,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "failure_message") val failureMessage: String?,
    @ColumnInfo(name = "written") val written: Boolean,
    @ColumnInfo(name = "attachments") val attachments: String,
    @ColumnInfo(name = "upload_starts") val uploadStarts: Int,
)

/** The cursors and the writes that change the cache and a cursor together, each in one transaction. */
@Dao
internal interface CacheDao {
    @Query("SELECT * FROM cursors WHERE id = $CURSORS_ROW")
    suspend fun cursors(): CursorsEntity?

    @Query(
        "UPDATE cursors SET last_server_seq = :seq, announced_up_to_seq = :announcedUpToSeq, " +
            "last_unannounced_seq = :lastUnannouncedSeq WHERE id = $CURSORS_ROW",
    )
    suspend fun setServerCursor(
        seq: Long,
        announcedUpToSeq: Long,
        lastUnannouncedSeq: Long,
    ): Int

    @Query("UPDATE cursors SET read_up_to_seq = :seq WHERE id = $CURSORS_ROW")
    suspend fun setReadFrontier(seq: Long): Int

    @Query("SELECT read_up_to_seq FROM cursors WHERE id = $CURSORS_ROW")
    fun readFrontier(): Flow<Long?>

    /** The cached rows [rows] name, updated in place, and [lastMutationSeq]; a row not cached is skipped. */
    @Transaction
    suspend fun applyMutations(
        rows: List<MutationRow>,
        lastMutationSeq: Long,
    ) {
        for (mutation in rows) {
            val held = entity(mutation.serverSeq.toColumn()) ?: continue
            update(held.toRow().mutated(mutation).toEntity())
        }
        requireOneRow(setLastMutationSeq(lastMutationSeq))
    }

    /** Drops the cache, its full-text index and its cursor, and sets the mutation cursor to [mutationHeadSeq]. */
    @Transaction
    suspend fun rebuildCache(mutationHeadSeq: Long) {
        deleteTimeline()
        requireOneRow(startOver(mutationHeadSeq))
    }

    @Query("SELECT * FROM timeline WHERE server_seq = :serverSeq")
    suspend fun entity(serverSeq: Long): TimelineEntity?

    @Update
    suspend fun update(entity: TimelineEntity): Int

    @Query("UPDATE cursors SET last_mutation_seq = :seq WHERE id = $CURSORS_ROW")
    suspend fun setLastMutationSeq(seq: Long): Int

    @Query("DELETE FROM timeline")
    suspend fun deleteTimeline()

    @Query("UPDATE cursors SET last_server_seq = 0, last_mutation_seq = :mutationHeadSeq WHERE id = $CURSORS_ROW")
    suspend fun startOver(mutationHeadSeq: Long): Int
}

/** The edits a live event makes to a cached row in place (RowEdits), each in one transaction. */
@Dao
internal interface RowEditsDao {
    /** The owner's row [clientMsgId] with [emoji] as its reaction (TimelineRow.withReaction); false when not cached. */
    @Transaction
    suspend fun applyReaction(
        clientMsgId: String,
        emoji: String,
    ): Boolean {
        val held = ownersEntity(clientMsgId)?.toRow() as? TimelineRow.Message ?: return false
        return update(held.withReaction(emoji).toEntity()) == 1
    }

    /** The owner's row [clientMsgId] with [text] as its content, its transcript; false when not cached. */
    @Transaction
    suspend fun applyTranscript(
        clientMsgId: String,
        text: String,
    ): Boolean {
        val held = ownersEntity(clientMsgId)?.toRow() as? TimelineRow.Message ?: return false
        return update(TimelineRow.Message(held.message.copy(content = text)).toEntity()) == 1
    }

    /** Row [serverSeq] with [card] among its link previews (withLinkPreview); false when not cached. */
    @Transaction
    suspend fun addLinkPreview(
        serverSeq: Long,
        card: LinkPreviewCard,
    ): Boolean {
        val held = entity(serverSeq) ?: return false
        return update(withLinkPreview(held.toRow(), card).toEntity()) == 1
    }

    @Query("SELECT * FROM timeline WHERE server_seq = :serverSeq")
    suspend fun entity(serverSeq: Long): TimelineEntity?

    @Query("SELECT * FROM timeline WHERE client_msg_id = :clientMsgId AND role = 'user' LIMIT 1")
    suspend fun ownersEntity(clientMsgId: String): TimelineEntity?

    @Update
    suspend fun update(entity: TimelineEntity): Int
}

/** The outbox's rows, in the order they were enqueued. */
@Dao
internal interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY position")
    suspend fun items(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY position")
    fun observed(): Flow<List<OutboxEntity>>

    /** [entity] last, one past the largest position the outbox holds, whatever position it carries. */
    @Transaction
    suspend fun enqueue(entity: OutboxEntity) = insert(entity.copy(position = nextPosition()))

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM outbox")
    suspend fun nextPosition(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: OutboxEntity)

    @Query("DELETE FROM outbox WHERE client_msg_id = :clientMsgId")
    suspend fun dequeue(clientMsgId: String)

    @Query("UPDATE outbox SET written = 1 WHERE client_msg_id = :clientMsgId")
    suspend fun markWritten(clientMsgId: String): Int

    @Query("DELETE FROM outbox WHERE client_msg_id = :clientMsgId AND (written = 0 OR failure_code IS NOT NULL)")
    suspend fun withdraw(clientMsgId: String): Int

    @Query("UPDATE outbox SET failure_code = :code, failure_message = :message WHERE client_msg_id = :clientMsgId")
    suspend fun markFailed(
        clientMsgId: String,
        code: String,
        message: String,
    ): Int
}

/** What an upload writes on its outbox row (core-session's UploadMarks), and the staged files the rows name. */
@Dao
internal interface UploadsDao {
    @Query("SELECT attachments FROM outbox WHERE client_msg_id = :clientMsgId")
    suspend fun attachmentsOf(clientMsgId: String): String?

    /** Every row's attachments column, for the staged files the outbox still names. */
    @Query("SELECT attachments FROM outbox")
    suspend fun attachments(): List<String>

    /** [attachId] of [clientMsgId]'s item marked uploaded, in one transaction; false when the outbox lacks it. */
    @Transaction
    suspend fun markUploaded(
        clientMsgId: String,
        attachId: String,
    ): Boolean {
        val held = attachmentsOf(clientMsgId) ?: return false
        val marked = decodeAttachments(held).map { it.copy(uploaded = it.uploaded || it.attachId == attachId) }
        return setAttachments(clientMsgId, encodeAttachments(marked)) == 1
    }

    @Query("UPDATE outbox SET attachments = :attachments WHERE client_msg_id = :clientMsgId")
    suspend fun setAttachments(
        clientMsgId: String,
        attachments: String,
    ): Int

    @Query("UPDATE outbox SET upload_starts = :starts WHERE client_msg_id = :clientMsgId")
    suspend fun setUploadStarts(
        clientMsgId: String,
        starts: Int,
    ): Int
}

/** A cursor write changes the one cursors row; any other count means the row is gone, and fails loud. */
internal fun requireOneRow(changed: Int) {
    check(changed == 1) { "the cursors row changed $changed times, not once" }
}
