package io.tezra.fermix.data

import androidx.room.Database
import androidx.room.FtsOptions
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.tezra.fermix.session.OutboxItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One (instance, profile)'s Room database (design section 9.1): the timeline cache with its full-text index,
 * the notified set, the outbox, the cursors and the chat's own state. Feature modules read [timeline],
 * [notified], [chat], [pending] and [readFrontier]; core-session's store is [RoomSessionStore] over the same
 * database, and the outbox's one writer.
 */
@Database(
    entities = [
        TimelineEntity::class,
        TimelineFts::class,
        NotifiedEntity::class,
        CursorsEntity::class,
        OutboxEntity::class,
        ChatStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun timeline(): TimelineDao

    abstract fun notified(): NotifiedDao

    abstract fun chat(): ChatStateDao

    internal abstract fun cache(): CacheDao

    internal abstract fun outbox(): OutboxDao

    /**
     * The outbox, in the order it was enqueued, again on every change: the queued and failed bubbles of design
     * section 13.6, which outlive a relaunch as the outbox does.
     */
    fun pending(): Flow<List<OutboxItem>> = outbox().observed().map { rows -> rows.map { it.toItem() } }

    /**
     * The read frontier (core-session's StoredCursors.readUpToSeq), again on every change: where the Chat
     * screen places its unread divider as it opens (design section 13.5).
     */
    fun readFrontier(): Flow<ULong> =
        cache().readFrontier().map { seq -> checkNotNull(seq) { "the cursors row is missing" }.toSeq() }
}

/**
 * The table that splits a search query into terms with the full-text index's own tokenizer (TimelineDao's
 * search): an `fts3tokenize` table, which no Room entity can declare, so it is made on every open rather
 * than in the exported schema. It holds nothing, so no migration ever has to carry it.
 */
internal const val QUERY_TOKENS = "query_tokens"

/** The tokenizer of the timeline's full-text index, and so of [QUERY_TOKENS]. */
internal const val FTS_TOKENIZER = FtsOptions.TOKENIZER_UNICODE61

/**
 * How every profile database is built, on disk or in a test's memory. It runs on the bundled SQLite, the
 * same build on the phone and in the JVM tests, so the tests prove the engine the app ships, its full-text
 * tokenizer included; queries run on [queries]; the journal is written ahead, named rather than left to
 * Room, which would ask the platform whether the phone is short of memory. The cursors and chat rows are
 * written when the database is created, and [QUERY_TOKENS] made each time it is opened.
 */
internal fun RoomDatabase.Builder<ProfileDatabase>.buildProfileDatabase(queries: CoroutineDispatcher): ProfileDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queries)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addCallback(OneRows())
        .addCallback(QueryTokens())
        .build()

/**
 * Writes the one cursors row, every cursor 0, and the one chat row, no draft or agent name and previews on,
 * into a database as it is created.
 */
private class OneRows : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        connection.execSQL(
            "INSERT INTO cursors (id, last_server_seq, read_up_to_seq, last_mutation_seq, announced_up_to_seq, " +
                "last_unannounced_seq) VALUES ($CURSORS_ROW, 0, 0, 0, 0, 0)",
        )
        connection.execSQL("INSERT INTO chat_state (id, draft, agent_name, previews) VALUES ($CHAT_ROW, NULL, NULL, 1)")
    }
}

/**
 * Makes [QUERY_TOKENS] when the database opens, on the connection Room configures it with, its writer: the
 * readers are `query_only`, and the table is in the main schema so that every reader sees it.
 */
private class QueryTokens : RoomDatabase.Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        connection.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $QUERY_TOKENS USING fts3tokenize('$FTS_TOKENIZER')")
    }
}
