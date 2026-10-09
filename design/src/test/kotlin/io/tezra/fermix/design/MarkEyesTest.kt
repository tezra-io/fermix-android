package io.tezra.fermix.design

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val CLOSE = 1e-3f

/**
 * The mark's eyes on Connecting and Verify (the M51 update's 7.4), each a function of the time into its phase: the
 * search's sweep, the check's narrowing, the securing's opening and blink, and Verify's look down, held to the update's
 * numbers, written here as literals with the row they come from, and to the reference player's where the update names
 * none. Only the eyes' fields move; the rest of the pose stays at rest.
 */
class MarkEyesTest {
    @Test
    fun `the search sweeps 2_6 units each way, 380 ms a sweep, in-out, from the centre`() {
        // 7.4, Connecting, Reaching and Trying Tailscale: "the eyes sweep side to side, 2.6 units each way, 380 ms a
        // sweep, in-out". The player's eyeDX (line 360): 0, 380: -2.6, 760: 2.6, 1140: -2.6.
        assertEquals(2.6f, Search.REACH)
        assertEquals(380, Search.SWEEP_MILLIS)
        assertEquals(0f, searchingAt(0f).eyeX)
        assertEquals(-2.6f, searchingAt(380f).eyeX, CLOSE)
        assertEquals(2.6f, searchingAt(760f).eyeX, CLOSE)
        assertEquals(-2.6f, searchingAt(1_140f).eyeX, CLOSE)
        assertEquals(2.6f, searchingAt(1_520f).eyeX, CLOSE)
        // In-out: halfway through a sweep the eyes are at the centre, a quarter in they are in-out's 0.1292 of it.
        assertEquals(0f, searchingAt(570f).eyeX, CLOSE)
        assertEquals(-2.6f + 5.2f * MarkEasing.InOut.transform(0.25f), searchingAt(475f).eyeX, CLOSE)
        assertEquals(-2.6f * MarkEasing.InOut.transform(0.5f), searchingAt(190f).eyeX, CLOSE)
    }

    @Test
    fun `the search keeps to its 2_6 units however long it runs, and moves nothing but the eyes across`() {
        for (ms in 0..IDLE_MAX_MILLIS.toInt() step 997) {
            val pose = searchingAt(ms.toFloat())
            assertTrue(pose.eyeX in -2.6f - CLOSE..2.6f + CLOSE, "at $ms ms: ${pose.eyeX}")
            assertEquals(MarkPose.Rest, pose.copy(eyeX = 0f), "at $ms ms")
        }
        assertThrows<IllegalArgumentException> { searchingAt(-1f) }
    }

    @Test
    fun `checking brings the eyes back to the centre and narrows them to 72 percent over 300 ms`() {
        // 7.4, Connecting, Checking: "the eyes return to centre and narrow to 72% of their height (300 ms,
        // emphasized decelerate)".
        assertEquals(300, Narrow.MILLIS)
        assertEquals(0.72f, Narrow.SQUINT)
        assertSame(MarkEasing.EmphasizedDecelerate, Narrow.easing)
        val from = searchingAt(500f)
        assertEquals(from.eyeX, checkingAt(0f, from).eyeX, CLOSE)
        assertEquals(1f, checkingAt(0f, from).squint, CLOSE)
        val half = MarkEasing.EmphasizedDecelerate.transform(0.5f)
        assertEquals(from.eyeX * (1f - half), checkingAt(150f, from).eyeX, CLOSE)
        assertEquals(1f - 0.28f * half, checkingAt(150f, from).squint, CLOSE)
        assertEquals(MarkPose.Rest.copy(squint = 0.72f), checkingAt(300f, from))
        assertEquals(MarkPose.Rest.copy(squint = 0.72f), checkingAt(5_000f, from))
    }

    @Test
    fun `securing opens the eyes to full in 200 ms and blinks once`() {
        // 7.4, Connecting, Securing: "the eyes open to full (200 ms) and blink once". The update names no curve and no
        // time for the blink; the player's (lines 361 and 362): squint 2600: 0.72 (emph) to 2800: 1, blink 2650: 1
        // (io), 2710: 0.1 (io), 2790: 1, Securing showing from 2600.
        assertEquals(200, Open.MILLIS)
        assertSame(MarkEasing.EmphasizedDecelerate, Open.easing)
        assertEquals(listOf(0 to 1f, 50 to 1f, 110 to 0.1f, 190 to 1f), Open.blink.map { it.ms to it.value })
        assertEquals(
            listOf(MarkEasing.Hold, MarkEasing.InOut, MarkEasing.InOut),
            Open.blink.dropLast(1).map { it.easing },
        )
        val narrowed = MarkPose.Rest.copy(squint = 0.72f)
        assertEquals(0.72f, securingAt(0f, narrowed).squint, CLOSE)
        assertEquals(0.1f, securingAt(110f, narrowed).blink, CLOSE)
        assertEquals(MarkPose.Rest, securingAt(200f, narrowed))
        assertEquals(MarkPose.Rest, securingAt(5_000f, narrowed))
        // Halfway through the opening, on its emphasized decelerate: the squint and, from a search cut short, the eyes'
        // place, each held to its own curve in the segment's middle, where another curve parts from it most.
        val half = MarkEasing.EmphasizedDecelerate.transform(0.5f)
        assertEquals(0.72f + 0.28f * half, securingAt(100f, narrowed).squint, CLOSE)
        val searching = searchingAt(190f)
        assertTrue(searching.eyeX < -1f, "the search at ${searching.eyeX}")
        assertEquals(searching.eyeX * (1f - half), securingAt(100f, searching).eyeX, CLOSE)
        // From a search cut short, the eyes come back to the centre as they open.
        assertEquals(MarkPose.Rest, securingAt(200f, searchingAt(380f)))
    }

    @Test
    fun `on Verify the eyes look down 2_6 units from 200 to 500 ms and stay there`() {
        // 7.4, Verify, the mark: "the eyes move down 2.6 units to look at the code (200 to 500 ms, emphasized
        // decelerate) and stay there". The player's eyeDY (line 405): [0, 0, hold], [200, 0, emph], [500, 2.6].
        assertEquals(listOf(0 to 0f, 200 to 0f, 500 to 2.6f), LookDown.eyeY.map { it.ms to it.value })
        assertEquals(
            listOf(MarkEasing.Hold, MarkEasing.EmphasizedDecelerate),
            LookDown.eyeY.dropLast(1).map { it.easing },
        )
        // The look's clock runs to the table's last key, read from it.
        assertEquals(500, LookDown.CLOCK_MILLIS)
        assertEquals(LookDown.eyeY.last().ms, LookDown.CLOCK_MILLIS)
        assertEquals(MarkPose.Rest, lookingDownAt(200f))
        assertEquals(2.6f * MarkEasing.EmphasizedDecelerate.transform(0.5f), lookingDownAt(350f).eyeY, CLOSE)
        assertEquals(MarkPose.Rest.copy(eyeY = 2.6f), lookingDownAt(500f))
        assertEquals(MarkPose.Rest.copy(eyeY = 2.6f), lookingDownAt(9_000f))
    }

    @Test
    fun `the eyes at rest look ahead, wide open`() {
        assertEquals(listOf(0f, 0f, 1f), listOf(MarkPose.Rest.eyeX, MarkPose.Rest.eyeY, MarkPose.Rest.squint))
    }
}
