package io.tezra.fermix.data

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.StoredCursors
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * One Room database per (instance, profile), keyed by both from day one (design section 9.1), each
 * with its media directory beside it, on disk on the bundled SQLite.
 */
class ProfileDatabasesTest {
    @TempDir
    lateinit var directory: File

    private val instanceId = idOf(key(1))
    private val otherId = idOf(key(3))

    private fun row(content: String) = TimelineRow.Message(HistoryMessage(1uL, "user", content, TS, emptyList()))

    @Test
    fun `each profile of each instance has a database of its own, and the same pair the same one`() =
        runTest {
            val databases = ProfileDatabases(TestContext, directory)
            databases.open(instanceId, "main").timeline().persist(row("main's"))
            databases.open(instanceId, "work").timeline().persist(row("work's"))
            databases.open(otherId, "main").timeline().persist(row("the other's"))
            assertSame(databases.open(instanceId, "main"), databases.open(instanceId, "main"))
            assertEquals(row("main's"), databases.open(instanceId, "main").timeline().row(1uL))
            assertEquals(row("work's"), databases.open(instanceId, "work").timeline().row(1uL))
            assertEquals(row("the other's"), databases.open(otherId, "main").timeline().row(1uL))
            assertNotEquals(databases.mediaDirectory(instanceId, "main"), databases.mediaDirectory(instanceId, "work"))
        }

    @Test
    fun `deleting an instance closes and deletes every profile's database and media, and no other's`() =
        runTest {
            val databases = ProfileDatabases(TestContext, directory)
            databases.open(instanceId, "main").timeline().persist(row("gone"))
            databases.open(otherId, "main").timeline().persist(row("kept"))
            val media = databases.mediaDirectory(instanceId, "main")
            assertTrue(media.mkdirs())
            databases.delete(instanceId)
            assertFalse(File(directory, instanceId).exists(), "the instance's files are still there")
            assertNull(databases.open(instanceId, "main").timeline().row(1uL))
            assertEquals(row("kept"), databases.open(otherId, "main").timeline().row(1uL))
        }

    @Test
    fun `a database opened again after a restart holds its cursors, outbox, cache and notified set`() =
        runTest {
            val before = ProfileDatabases(TestContext, directory).open(instanceId, "main")
            val store = RoomSessionStore(before)
            store.setServerCursor(5uL, announcedUpToSeq = 4uL, lastUnannouncedSeq = 5uL)
            store.setReadFrontier(3uL)
            store.applyMutations(emptyList(), 9uL)
            store.enqueue(OutboxItem(MSG))
            before.timeline().persist(row("kept"))
            before.notified().put(NotifiedEntry.Row(5uL), nowMs = 0L)
            before.close()

            val after = ProfileDatabases(TestContext, directory).open(instanceId, "main")
            val reopened = RoomSessionStore(after)
            assertEquals(StoredCursors(5uL, 3uL, 9uL, 4uL, 5uL), reopened.cursors())
            assertEquals(listOf(OutboxItem(MSG)), reopened.outbox())
            assertEquals(row("kept"), after.timeline().row(1uL))
            assertTrue(after.notified().contains(NotifiedEntry.Row(5uL)))
            after.close()
        }

    @Test
    fun `an instance id that is not a sha256, or an empty profile, is refused`() {
        val databases = ProfileDatabases(TestContext, directory)
        assertThrows<IllegalArgumentException> { databases.open("../$instanceId", "main") }
        assertThrows<IllegalArgumentException> { databases.open(instanceId, "") }
        assertThrows<IllegalArgumentException> { databases.delete("main") }
    }

    private companion object {
        const val TS = "2026-10-01T09:00:00Z"
        val MSG = ClientEvent.Msg("m1", "main", "hello", emptyList())
    }
}
