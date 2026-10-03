package io.tezra.fermix.onboarding

import io.tezra.fermix.data.TINT_NAMES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The record an approval leaves (design sections 9.1 and 9.2), its tint, and when it needs a name. */
class PairedRecordTest {
    @Test
    fun `the record keeps what the pairing reported, with no nickname and notifications off`() {
        val reported = facts(gateway = 1)
        val stored = instanceOf(reported, "Ocean", PHONE, PAIRED_AT)
        assertEquals(reported.id, stored.id)
        assertEquals(reported.keyAlias, stored.keyAlias)
        assertEquals(reported.candidates, stored.candidates)
        assertEquals("Ocean", stored.tint)
        assertNull(stored.nickname)
        assertFalse(stored.notificationsEnabled)
        assertEquals(PHONE, stored.deviceName)
        assertEquals(PAIRED_AT, stored.pairedAt)
    }

    @Test
    fun `the tint is the first no record carries, then the one fewest carry`() {
        assertEquals("Slate", pickTint(emptyList()))
        assertEquals("Clay", pickTint(listOf(record(1, "Slate"), record(2, "Sage"))))
        val everyOnce = TINT_NAMES.mapIndexed { index, tint -> record(index + 1, tint) }
        assertEquals("Slate", pickTint(everyOnce))
        assertEquals("Sage", pickTint(everyOnce + record(9, "Slate")))
    }

    @Test
    fun `a second Fermix titled as the first is asked for a name, and a nickname settles it`() {
        val first = record(1)
        val second = record(2)
        assertTrue(needsName(second, listOf(first, second)))
        assertFalse(needsName(second, listOf(first.copy(nickname = "Studio"), second)))
        assertFalse(needsName(first, listOf(first)))
    }

    @Test
    fun `Pair again merges into its row while the row is there, of the profile, and the daemon is in no row`() {
        val old = record(gateway = 5)
        val paired = facts(gateway = 1)
        assertEquals(old.id, mergeTarget(old.id, paired, listOf(old)))
        assertNull(mergeTarget(null, paired, listOf(old)))
        assertNull(mergeTarget(old.id, paired, emptyList()))
        assertNull(mergeTarget(old.id, facts(gateway = 1, profile = "fermix-dev"), listOf(old)))
        assertNull(mergeTarget(old.id, paired, listOf(old, record(gateway = 1))))
        assertNull(mergeTarget(old.id, facts(gateway = 5), listOf(old)))
    }
}
