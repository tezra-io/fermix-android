package io.tezra.fermix.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The notified set (design section 10, "Lifecycle on the phone"; tla/specs/mobile_push): what the phone
 * has announced, read on every path that can alert. A second put of an entry is no second entry, so a
 * push and a socket row for one reply alert once; a read frontier removes the rows at or below it.
 */
class NotifiedDaoTest {
    private val database = inMemoryDatabase()
    private val notified = database.notified()

    @AfterEach
    fun close() = database.close()

    @Test
    fun `putting an entry twice holds it once, and only the first put may alert`() =
        runTest {
            assertTrue(notified.put(NotifiedEntry.Row(7uL), NOW))
            assertFalse(notified.put(NotifiedEntry.Row(7uL), NOW), "a second put of a row added it again")
            assertTrue(notified.put(NotifiedEntry.Approval("approval-1"), NOW))
            assertFalse(notified.put(NotifiedEntry.Approval("approval-1"), NOW), "a second put of an approval added it")
            assertTrue(notified.put(NotifiedEntry.TurnFailed("turn-1"), NOW))
            assertFalse(notified.put(NotifiedEntry.TurnFailed("turn-1"), NOW), "a second put of a failed turn added it")
            assertEquals(listOf(7uL), notified.serverSeqs().first())
            assertTrue(notified.contains(NotifiedEntry.Row(7uL)))
            assertTrue(notified.contains(NotifiedEntry.Approval("approval-1")))
            assertFalse(notified.contains(NotifiedEntry.Row(8uL)))
        }

    @Test
    fun `a row the read frontier has reached is never added, so a late push for it alerts nobody`() =
        runTest {
            assertTrue(notified.put(NotifiedEntry.Row(5uL), NOW))
            RoomSessionStore(database).setReadFrontier(9uL)
            notified.removeReadUpTo(9uL)
            assertFalse(notified.put(NotifiedEntry.Row(5uL), NOW), "a row read was added again")
            assertFalse(notified.put(NotifiedEntry.Row(9uL), NOW), "the row at the read frontier was added")
            assertEquals(emptyList<ULong>(), notified.serverSeqs().first())
            assertTrue(notified.put(NotifiedEntry.Row(10uL), NOW))
            assertEquals(listOf(10uL), notified.serverSeqs().first())
        }

    @Test
    fun `an approval or failed turn leaves once no duplicate push of it can arrive, and a row stays until read`() =
        runTest {
            notified.put(NotifiedEntry.Approval("old"), 0L)
            notified.put(NotifiedEntry.TurnFailed("old"), 0L)
            notified.put(NotifiedEntry.Row(3uL), 0L)
            notified.put(NotifiedEntry.Approval("new"), 1L)
            notified.removeExpired(nowMs = NOTIFIED_ID_RETENTION_MS)
            assertTrue(notified.contains(NotifiedEntry.Approval("old")), "removed at the retention's very end")
            notified.removeExpired(nowMs = NOTIFIED_ID_RETENTION_MS + 1L)
            assertFalse(notified.contains(NotifiedEntry.Approval("old")))
            assertFalse(notified.contains(NotifiedEntry.TurnFailed("old")))
            assertTrue(notified.contains(NotifiedEntry.Approval("new")))
            assertEquals(listOf(3uL), notified.serverSeqs().first())
        }

    @Test
    fun `the retention outlasts a duplicate push, which arrives within the push ttl and the daemon's retries`() {
        // Design section 10: ttl 86400 s for every kind, and three retries 2 s, 10 s and 30 s apart.
        assertTrue(NOTIFIED_ID_RETENTION_MS > (86_400L + 2L + 10L + 30L) * 1_000L)
    }

    @Test
    fun `the three kinds are apart, so one id under two kinds is two entries`() =
        runTest {
            notified.put(NotifiedEntry.Approval("7"), NOW)
            assertFalse(notified.contains(NotifiedEntry.Row(7uL)))
            assertFalse(notified.contains(NotifiedEntry.TurnFailed("7")))
            notified.put(NotifiedEntry.TurnFailed("7"), NOW)
            assertTrue(notified.contains(NotifiedEntry.TurnFailed("7")))
            assertEquals(emptyList<ULong>(), notified.serverSeqs().first())
        }

    @Test
    fun `a read frontier removes the rows at or below it, and no approval or failed turn`() =
        runTest {
            listOf(3uL, 5uL, 6uL, 9uL).forEach { seq -> notified.put(NotifiedEntry.Row(seq), NOW) }
            notified.put(NotifiedEntry.Approval("approval-1"), NOW)
            notified.put(NotifiedEntry.TurnFailed("turn-1"), NOW)
            notified.removeReadUpTo(6uL)
            assertEquals(listOf(9uL), notified.serverSeqs().first())
            assertTrue(notified.contains(NotifiedEntry.Approval("approval-1")))
            assertTrue(notified.contains(NotifiedEntry.TurnFailed("turn-1")))
            notified.removeReadUpTo(9uL)
            assertEquals(emptyList<ULong>(), notified.serverSeqs().first())
        }

    @Test
    fun `seqs are compared and ordered as numbers, never as text`() =
        runTest {
            // As text, "10" and "100" sort before "9" and fall at or below it.
            listOf(100uL, 9uL, 10uL, 3uL).forEach { seq -> notified.put(NotifiedEntry.Row(seq), NOW) }
            assertEquals(listOf(3uL, 9uL, 10uL, 100uL), notified.serverSeqs().first())
            notified.removeReadUpTo(9uL)
            assertEquals(listOf(10uL, 100uL), notified.serverSeqs().first())
        }

    @Test
    fun `clear empties the set`() =
        runTest {
            notified.put(NotifiedEntry.Row(1uL), NOW)
            notified.put(NotifiedEntry.Approval("approval-1"), NOW)
            notified.clear()
            assertFalse(notified.contains(NotifiedEntry.Row(1uL)))
            assertFalse(notified.contains(NotifiedEntry.Approval("approval-1")))
        }

    private companion object {
        /** When the phone put an entry, in Unix milliseconds: 2026-10-01. */
        const val NOW = 1_790_812_800_000L
    }
}
