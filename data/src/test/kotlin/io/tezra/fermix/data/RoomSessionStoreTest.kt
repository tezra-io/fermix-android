package io.tezra.fermix.data

import androidx.room.execSQL
import androidx.room.useWriterConnection
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.SessionStore
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The profile database's SessionStore, held to the store's contract, in memory on the bundled SQLite. */
class RoomSessionStoreTest : SessionStoreContract() {
    private val database = inMemoryDatabase()

    override val store: SessionStore = RoomSessionStore(database)

    override suspend fun persist(row: TimelineRow) = database.timeline().persist(row)

    override suspend fun cached(serverSeq: ULong): TimelineRow? = database.timeline().row(serverSeq)

    override suspend fun failCursorWrites() =
        database.useWriterConnection { connection ->
            connection.execSQL(
                "CREATE TRIGGER planted_cursor_fault BEFORE UPDATE ON cursors " +
                    "BEGIN SELECT RAISE(ABORT, 'a planted fault'); END",
            )
        }

    @Test
    fun `the pending flow shows the outbox the store writes, in enqueue order with each failure`() =
        runTest {
            val failure = RequestFailure("client_message_conflict", "content differs")
            store.enqueue(OutboxItem(msg("m1")))
            store.enqueue(OutboxItem(msg("m2")))
            store.markFailed("m1", failure)
            assertEquals(listOf(OutboxItem(msg("m1"), failure), OutboxItem(msg("m2"))), database.pending().first())
            store.dequeue("m2")
            assertEquals(listOf(OutboxItem(msg("m1"), failure)), database.pending().first())
        }

    @AfterEach
    fun close() = database.close()
}
