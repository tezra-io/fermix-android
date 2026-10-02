package io.tezra.fermix.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.tezra.fermix.protocol.MutationRow
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
 * and a failed item has its failure's code and message.
 */
@Entity(tableName = "outbox")
internal data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "client_msg_id") val clientMsgId: String,
    @ColumnInfo(name = "position") val position: Long,
    @ColumnInfo(name = "request") val request: String,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "failure_message") val failureMessage: String?,
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

/** The outbox's rows, in the order they were enqueued. */
@Dao
internal interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY position")
    suspend fun items(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY position")
    fun observed(): Flow<List<OutboxEntity>>

    @Query(
        "INSERT INTO outbox (client_msg_id, position, request, failure_code, failure_message) " +
            "SELECT :clientMsgId, COALESCE(MAX(position), 0) + 1, :request, :failureCode, :failureMessage FROM outbox",
    )
    suspend fun enqueue(
        clientMsgId: String,
        request: String,
        failureCode: String?,
        failureMessage: String?,
    )

    @Query("DELETE FROM outbox WHERE client_msg_id = :clientMsgId")
    suspend fun dequeue(clientMsgId: String)

    @Query("UPDATE outbox SET failure_code = :code, failure_message = :message WHERE client_msg_id = :clientMsgId")
    suspend fun markFailed(
        clientMsgId: String,
        code: String,
        message: String,
    ): Int
}

/** A cursor write changes the one cursors row; any other count means the row is gone, and fails loud. */
internal fun requireOneRow(changed: Int) {
    check(changed == 1) { "the cursors row changed $changed times, not once" }
}
