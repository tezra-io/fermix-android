package io.tezra.fermix.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Transactor
import androidx.room.Update
import androidx.room.useReaderConnection
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val WHITESPACE = Regex("\\s+")

/**
 * The profile's timeline cache, keyed by `server_seq`. [persist] is the app's Announcer's first step for a
 * new row: persist, then show or notify, then answer (core-session's Announcer, onboarding gotcha 14).
 * [persistAll] keeps an older page (`SessionEvent.OlderLoaded`), whose rows are not new and never reach the
 * notified set. A whole row replaces what the cache holds at its seq: the reply that stood for it, or an
 * older copy. A reply is kept only where the cache holds no whole row, which it would otherwise hide.
 */
@Dao
abstract class TimelineDao(
    private val database: RoomDatabase,
) {
    suspend fun persist(row: TimelineRow) = persistAll(listOf(row))

    /** Every row of [rows] as [persist] keeps it, in one transaction. */
    @Transaction
    open suspend fun persistAll(rows: List<TimelineRow>) {
        for (row in rows) {
            val held = entity(row.serverSeq.toColumn())
            when {
                held == null -> insert(row.toEntity())
                row is TimelineRow.Reply && held.whole -> Unit
                else -> update(row.toEntity())
            }
        }
    }

    suspend fun row(serverSeq: ULong): TimelineRow? = entity(serverSeq.toColumn())?.toRow()

    /** The newest [limit] rows the cache holds, newest first, again on every change. */
    fun newest(limit: Int): Flow<List<TimelineRow>> {
        require(limit > 0) { "a limit of $limit rows" }
        return newestEntities(limit).map { rows -> rows.map { it.toRow() } }
    }

    /**
     * The local full-text search of design section 13.7, for when the daemon cannot search: the cached rows
     * holding every word of [query], each word or the start of one, newest first, at most [limit]. The index's
     * own tokenizer splits each word into its terms, so the query reads every character as the index does,
     * by Unicode 6.1's tables and not the JVM's: punctuation on a word is a separator, an emoji after 6.1 is
     * a term, and a word of which the tokenizer makes no term is left out.
     */
    suspend fun search(
        query: String,
        limit: Int,
    ): List<TimelineRow> {
        require(limit > 0) { "a limit of $limit rows" }
        val words = query.split(WHITESPACE).filter { it.isNotEmpty() }
        val terms = database.useReaderConnection { connection -> words.map { word -> terms(connection, word) } }
        val match = ftsMatch(terms) ?: return emptyList()
        return matching(match, limit).map { it.toRow() }
    }

    @Query("SELECT * FROM timeline WHERE server_seq = :serverSeq")
    internal abstract suspend fun entity(serverSeq: Long): TimelineEntity?

    @Insert
    internal abstract suspend fun insert(entity: TimelineEntity)

    // A row held is updated in place, which the full-text index's update triggers follow. An insert that
    // REPLACEs would delete the row without the delete trigger, which SQLite fires for REPLACE only with
    // recursive triggers on, and leave the index naming the old text.
    @Update
    internal abstract suspend fun update(entity: TimelineEntity)

    @Query("SELECT * FROM timeline ORDER BY server_seq DESC LIMIT :limit")
    internal abstract fun newestEntities(limit: Int): Flow<List<TimelineEntity>>

    @Query(
        "SELECT timeline.* FROM timeline JOIN timeline_fts ON timeline.server_seq = timeline_fts.rowid " +
            "WHERE timeline_fts MATCH :match ORDER BY timeline.server_seq DESC LIMIT :limit",
    )
    internal abstract suspend fun matching(
        match: String,
        limit: Int,
    ): List<TimelineEntity>
}

/** The terms the full-text index's tokenizer makes of [word], in order, through [QUERY_TOKENS]. */
private suspend fun terms(
    connection: Transactor,
    word: String,
): List<String> =
    connection.usePrepared("SELECT token FROM $QUERY_TOKENS WHERE input = ? ORDER BY position") { statement ->
        statement.bindText(1, word)
        buildList { while (statement.step()) add(statement.getText(0)) }
    }

/**
 * The full-text query for [words], each the terms the index's tokenizer made of one word the owner typed:
 * every word a quoted phrase of its terms, its last term a prefix, and all of them required. The quotes make
 * every word text, so FTS's own syntax (`AND`, `NOT`, a column filter) is searched for, never obeyed. A word
 * of no term is left out: a required phrase of no term matches no row, and would hide what the other words
 * find. Null when no word is left. A term that holds a quote, a star or a space is refused: the tokenizer
 * makes none such, and one would leave its phrase.
 */
internal fun ftsMatch(words: List<List<String>>): String? {
    words.flatten().forEach { term ->
        require(term.isNotEmpty() && term.none { it == '"' || it == '*' || it.isWhitespace() }) {
            "'$term' is not a term of the tokenizer's"
        }
    }
    val phrases = words.filter { it.isNotEmpty() }
    return if (phrases.isEmpty()) null else phrases.joinToString(" ") { "\"${it.joinToString(" ")}*\"" }
}
