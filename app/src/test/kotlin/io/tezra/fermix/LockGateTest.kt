package io.tezra.fermix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The app lock's gate (design section 13.7), on the elapsed clock the activity reads. */
class LockGateTest {
    /** A phone with a screen lock, or without one once [able] says so. */
    private var able = true

    private fun gate() = LockGate(canLock = { able })

    @Test
    fun `with the lock on, the app is locked as it comes into sight after the process starts`() {
        val gate = gate()
        gate.lockSetting(on = true)
        assertFalse(gate.locked.value)
        gate.cameIntoSight(now = 0)
        assertTrue(gate.locked.value)
    }

    @Test
    fun `the setting read after the app came into sight locks it as well`() {
        val gate = gate()
        gate.cameIntoSight(now = 0)
        gate.lockSetting(on = true)
        assertTrue(gate.locked.value)
    }

    @Test
    fun `nothing is known of the lock until the settings say it once`() {
        val gate = gate()
        gate.cameIntoSight(now = 0)
        assertFalse(gate.known.value)
        gate.lockSetting(on = false)
        assertTrue(gate.known.value)
    }

    @Test
    fun `an unlock opens it, and only a return past the grace locks it again`() {
        val gate = gate()
        gate.lockSetting(on = true)
        gate.cameIntoSight(now = 0)
        gate.unlocked()
        assertFalse(gate.locked.value)
        gate.wentOutOfSight(now = 1_000)
        gate.cameIntoSight(now = 1_000 + BACKGROUND_GRACE_MILLIS)
        assertFalse(gate.locked.value)
        gate.wentOutOfSight(now = 7_000)
        gate.cameIntoSight(now = 7_001 + BACKGROUND_GRACE_MILLIS)
        assertTrue(gate.locked.value)
    }

    @Test
    fun `a rotation or a fold long after the last return is no return, and leaves the app unlocked`() {
        val gate = gate()
        gate.lockSetting(on = true)
        gate.cameIntoSight(now = 0)
        gate.unlocked()
        gate.wentOutOfSight(now = 1_000)
        gate.cameIntoSight(now = 2_000)
        // The recreated activity comes into sight with the app never having left.
        gate.cameIntoSight(now = 60_000)
        assertFalse(gate.locked.value)
    }

    @Test
    fun `turning the lock off unlocks, and turning it on in sight waits for the next return`() {
        val gate = gate()
        gate.lockSetting(on = true)
        gate.cameIntoSight(now = 0)
        gate.lockSetting(on = false)
        assertFalse(gate.locked.value)
        gate.lockSetting(on = true)
        assertFalse(gate.locked.value)
        gate.wentOutOfSight(now = 100)
        gate.cameIntoSight(now = 101 + BACKGROUND_GRACE_MILLIS)
        assertTrue(gate.locked.value)
    }

    @Test
    fun `with the lock off, nothing locks`() {
        val gate = gate()
        gate.lockSetting(on = false)
        gate.cameIntoSight(now = 0)
        gate.wentOutOfSight(now = 1)
        gate.cameIntoSight(now = 2 + BACKGROUND_GRACE_MILLIS)
        assertFalse(gate.locked.value)
    }

    @Test
    fun `a phone whose screen lock is gone is never locked, and a lock it held opens at the next sight`() {
        able = false
        val unable = gate()
        unable.lockSetting(on = true)
        unable.cameIntoSight(now = 0)
        assertFalse(unable.locked.value)

        able = true
        val gate = gate()
        gate.lockSetting(on = true)
        gate.cameIntoSight(now = 0)
        assertTrue(gate.locked.value)
        gate.wentOutOfSight(now = 1_000)
        able = false
        gate.cameIntoSight(now = 2_000)
        assertFalse(gate.locked.value)
        able = true
        gate.wentOutOfSight(now = 3_000)
        gate.cameIntoSight(now = 4_000)
        assertTrue(gate.locked.value, "the screen lock is back, and the unlock it waited for never came")
    }

    @Test
    fun `the gate says how the app is seen once its setting is known, away, locked or open`() {
        val gate = gate()
        assertEquals(Sight.UNKNOWN, gate.sight.value)
        gate.cameIntoSight(now = 0)
        assertEquals(Sight.UNKNOWN, gate.sight.value)
        gate.lockSetting(on = true)
        assertEquals(Sight.LOCKED, gate.sight.value)
        gate.unlocked()
        assertEquals(Sight.OPEN, gate.sight.value)
        gate.wentOutOfSight(now = 1_000)
        assertEquals(Sight.AWAY, gate.sight.value)
        gate.cameIntoSight(now = 2_000 + BACKGROUND_GRACE_MILLIS)
        assertEquals(Sight.LOCKED, gate.sight.value)
        gate.lockSetting(on = false)
        assertEquals(Sight.OPEN, gate.sight.value)
    }

    @Test
    fun `a phone that cannot hold the lock is open as it comes into sight`() {
        able = false
        val gate = gate()
        gate.lockSetting(on = true)
        assertEquals(Sight.AWAY, gate.sight.value)
        gate.cameIntoSight(now = 0)
        assertEquals(Sight.OPEN, gate.sight.value)
    }
}
