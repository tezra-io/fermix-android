package io.tezra.fermix.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val KIND_ROW = "server_seq"
private const val KIND_APPROVAL = "approval_id"
private const val KIND_TURN = "turn_id"

/** The push's `ttl`, one day for every kind (design section 10). */
private const val PUSH_TTL_MS = 86_400_000L

/**
 * How long the notified set keeps an approval or a failed turn: twice the push's `ttl`. A duplicate push of
 * one arrives at most a `ttl` and the daemon's retries, 2 s, 10 s and 30 s apart, after the first copy was
 * sent (design section 10); the second `ttl` is margin for this phone's clock.
 */
const val NOTIFIED_ID_RETENTION_MS: Long = 2L * PUSH_TTL_MS

/** What Room's insert returns for a row its IGNORE strategy left out. */
private const val NOT_INSERTED = -1L

/** One entry of the notified set, by the id of its kind (design section 10, "Lifecycle on the phone"). */
sealed interface NotifiedEntry {
    /** A row this phone announced, unread until the read frontier reaches it. */
    data class Row(
        val serverSeq: ULong,
    ) : NotifiedEntry

    /** An approval whose notification this phone posted. */
    data class Approval(
        val approvalId: String,
    ) : NotifiedEntry {
        init {
            require(approvalId.isNotEmpty()) { "an approval's id is empty" }
        }
    }

    /** A failed turn whose notification this phone posted. */
    data class TurnFailed(
        val turnId: String,
    ) : NotifiedEntry {
        init {
            require(turnId.isNotEmpty()) { "a turn's id is empty" }
        }
    }
}

/**
 * An entry as the table holds it: its kind, its id as text, a row's being its `server_seq` in decimal, and
 * when this phone put it, in Unix milliseconds, which ends an approval's or a failed turn's stay.
 */
@Entity(tableName = "notified", primaryKeys = ["kind", "ref"])
internal data class NotifiedEntity(
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "ref") val ref: String,
    @ColumnInfo(name = "added_at") val addedAtMs: Long,
)

/** The entry's kind and id as the table keys them. */
private fun NotifiedEntry.key(): Pair<String, String> =
    when (this) {
        is NotifiedEntry.Row -> KIND_ROW to serverSeq.toColumn().toString()
        is NotifiedEntry.Approval -> KIND_APPROVAL to approvalId
        is NotifiedEntry.TurnFailed -> KIND_TURN to turnId
    }

/**
 * The notified set: what this phone has told its owner of, read on every path that can alert, a socket row,
 * a push and a row loaded on connect, so no reply alerts twice (onboarding gotcha 13, tla/specs/mobile_push).
 * A put is idempotent, so a push and the socket row for one reply are one entry, and a row already read is
 * never put. The rows leave once read: every `hello_ack` and `read_state` calls [removeReadUpTo]. Approvals
 * and failed turns have no seq to read, so they stay keyed by id, and a late push for one is still
 * recognised, until no duplicate of it can arrive: [removeExpired] on every `hello_ack`.
 */
@Dao
abstract class NotifiedDao {
    /**
     * Adds [entry], put at [nowMs] in Unix milliseconds, and is true when this call added it: only then may
     * the caller alert. A row at or below the profile's read frontier, its cursors' `read_up_to_seq`, is read
     * and is never added (design section 10). The tests and the add are one transaction, so the paths that
     * race to announce one id, a push and its socket row, alert once between them, whichever comes first
     * (tla/specs/mobile_push, NotifiedSet), and a read frontier written meanwhile is never missed; [contains]
     * is for the paths that only read.
     */
    @Transaction
    open suspend fun put(
        entry: NotifiedEntry,
        nowMs: Long,
    ): Boolean {
        require(nowMs >= 0L) { "an entry put at $nowMs" }
        // The cursors row is written when the database is created, so a missing one is a broken database.
        val frontier = checkNotNull(readUpToSeq()) { "the cursors row is missing" }
        if (entry is NotifiedEntry.Row && entry.serverSeq.toColumn() <= frontier) return false
        val (kind, ref) = entry.key()
        return insert(NotifiedEntity(kind, ref, nowMs)) != NOT_INSERTED
    }

    suspend fun contains(entry: NotifiedEntry): Boolean {
        val (kind, ref) = entry.key()
        return exists(kind, ref)
    }

    /** The rows announced and not yet read, oldest first: what the conversation's notification shows. */
    fun serverSeqs(): Flow<List<ULong>> = rowSeqs().map { seqs -> seqs.map { it.toSeq() } }

    /** Removes the rows at or below the read frontier [seq]. */
    suspend fun removeReadUpTo(seq: ULong) = deleteRowsUpTo(seq.toColumn())

    @Query("DELETE FROM notified")
    abstract suspend fun clear()

    @Query("SELECT read_up_to_seq FROM cursors WHERE id = $CURSORS_ROW")
    internal abstract suspend fun readUpToSeq(): Long?

    /** The new row's id, or [NOT_INSERTED] when the set held the entry already. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract suspend fun insert(entity: NotifiedEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM notified WHERE kind = :kind AND ref = :ref)")
    internal abstract suspend fun exists(
        kind: String,
        ref: String,
    ): Boolean

    @Query("SELECT CAST(ref AS INTEGER) AS seq FROM notified WHERE kind = '$KIND_ROW' ORDER BY seq")
    internal abstract fun rowSeqs(): Flow<List<Long>>

    @Query("DELETE FROM notified WHERE kind = '$KIND_ROW' AND CAST(ref AS INTEGER) <= :seq")
    internal abstract suspend fun deleteRowsUpTo(seq: Long)

    @Query("DELETE FROM notified WHERE kind IN ('$KIND_APPROVAL', '$KIND_TURN') AND added_at < :cutoffMs")
    internal abstract suspend fun deleteIdsPutBefore(cutoffMs: Long)
}

/**
 * Removes the approvals and failed turns put more than [NOTIFIED_ID_RETENTION_MS] before [nowMs], in Unix
 * milliseconds: the notified set's retention, kept beside its storage rather than in it.
 */
suspend fun NotifiedDao.removeExpired(nowMs: Long) {
    require(nowMs >= 0L) { "the time is $nowMs" }
    deleteIdsPutBefore(nowMs - NOTIFIED_ID_RETENTION_MS)
}
