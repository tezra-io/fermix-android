package io.tezra.fermix.data

import androidx.room.execSQL
import androidx.room.useWriterConnection
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.OutboxAttachment
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.SessionStore
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** The profile database's SessionStore, held to the store's contract, in memory on the bundled SQLite. */
class RoomSessionStoreTest : SessionStoreContract() {
    private val database = inMemoryDatabase()
    private val stagedDirectory = Files.createTempDirectory("staged").toFile()
    private val staged = StagedUploads(stagedDirectory)

    override val store: SessionStore = RoomSessionStore(database, staged)

    /** [attachId]'s file staged as the chat stages it, and the attachment that names it. */
    private fun stagedAttachment(attachId: String): OutboxAttachment {
        val picked = File.createTempFile("picked", null).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return attachment(attachId).copy(source = staged.stage(picked, attachId).path)
    }

    @Test
    fun `an item that leaves the outbox lets go of the staged files no other item names`() =
        runTest {
            val photo = stagedAttachment("a1")
            val refused = ClientEvent.Msg("m1", PROFILE, "", listOf("a1"))
            store.enqueue(OutboxItem(refused, attachments = listOf(photo)))
            store.enqueue(OutboxItem(refused.copy(clientMsgId = "m2", retryOf = "m1"), attachments = listOf(photo)))
            store.dequeue("m1")
            assertTrue(File(photo.source).isFile, "the run again still names the file")
            store.dequeue("m2")
            assertFalse(File(photo.source).exists(), "no item names the file any more")
            val removed = stagedAttachment("a3")
            store.enqueue(OutboxItem(ClientEvent.Msg("m3", PROFILE, "", listOf("a3")), attachments = listOf(removed)))
            assertTrue(store.withdraw("m3"))
            assertFalse(File(removed.source).exists(), "a withdrawn item's file goes with it")
        }

    @Test
    fun `a staged file named nowhere is swept, one an item names stays, and a file elsewhere is never touched`() =
        runTest {
            val kept = stagedAttachment("a1")
            val orphan = stagedAttachment("a2")
            val elsewhere = File.createTempFile("elsewhere", null)
            staged.sweep(setOf(kept.source))
            staged.release(listOf(elsewhere.path))
            assertTrue(File(kept.source).isFile)
            assertFalse(File(orphan.source).exists())
            assertTrue(elsewhere.isFile, "a path outside the staged directory is never deleted")
        }

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
    fun `the pending flow shows the outbox the store writes, in enqueue order with each failure and write`() =
        runTest {
            val failure = RequestFailure("client_message_conflict", "content differs")
            store.enqueue(OutboxItem(msg("m1")))
            store.enqueue(OutboxItem(msg("m2")))
            store.markFailed("m1", failure)
            assertEquals(listOf(OutboxItem(msg("m1"), failure), OutboxItem(msg("m2"))), database.pending().first())
            store.dequeue("m2")
            assertEquals(listOf(OutboxItem(msg("m1"), failure)), database.pending().first())
            store.enqueue(OutboxItem(msg("m3")))
            store.markWritten("m3")
            assertEquals(OutboxItem(msg("m3"), written = true), database.pending().first().last())
        }

    @Test
    fun `the read frontier flows as the store writes it, for the divider the Chat screen places on open`() =
        runTest {
            assertEquals(0uL, database.readFrontier().first())
            store.setReadFrontier(12uL)
            assertEquals(12uL, database.readFrontier().first())
        }

    @AfterEach
    fun close() {
        database.close()
        stagedDirectory.deleteRecursively()
    }
}
