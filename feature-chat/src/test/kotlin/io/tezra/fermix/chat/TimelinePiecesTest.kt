package io.tezra.fermix.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import io.tezra.fermix.design.Sender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The timeline's pure pieces: when the chat is on screen, what rises, the date pill's day, the stamp, figures, a
 * wide table's columns.
 */
class TimelinePiecesTest {
    @Test
    fun `the chat is on screen only resumed with its window focused`() {
        val table =
            Lifecycle.State.entries.flatMap { state -> listOf(true, false).map { focused -> state to focused } }
        val onScreenOnes = table.filter { (state, focused) -> onScreen(state, focused) }
        assertEquals(listOf(Lifecycle.State.RESUMED to true), onScreenOnes)
    }

    @Test
    fun `what lands at the bottom rises, and nothing does as the chat opens or an older page lands`() {
        assertEquals(emptySet<String>(), freshKeys(null, listOf("b", "a")))
        assertEquals(emptySet<String>(), freshKeys(emptyList(), listOf("b", "a")))
        assertEquals(setOf("d", "c"), freshKeys(listOf("b", "a"), listOf("d", "c", "b", "a")))
        assertEquals(emptySet<String>(), freshKeys(listOf("b", "a"), listOf("b", "a", "older")))
        // The card that becomes its bubble keeps its key: nothing rises.
        assertEquals(emptySet<String>(), freshKeys(listOf("card:t:1", "a"), listOf("card:t:1", "a")))
    }

    @Test
    fun `the date pill names the day header above the topmost item in view`() {
        val today = LocalDate.of(2026, 9, 27)
        val yesterday = today.minusDays(1)
        val items =
            listOf(
                said("r2"),
                said("r1"),
                ChatItem.Day("day:$today", today),
                said("y1"),
                ChatItem.Day("day:$yesterday", yesterday),
                ChatItem.Older,
            )
        assertEquals(today, dateAt(items, 0))
        assertEquals(today, dateAt(items, 2))
        assertEquals(yesterday, dateAt(items, 3))
        assertNull(dateAt(items, 5))
    }

    @Test
    fun `a stamp floats on the last line when it fits and the line runs the bubble's way`() {
        assertTrue(stampFloats(lastLineWidth = 100, stampWidth = 60, gap = 20, maxWidth = 180, sameDirection = true))
        assertFalse(stampFloats(lastLineWidth = 101, stampWidth = 60, gap = 20, maxWidth = 180, sameDirection = true))
        assertFalse(stampFloats(lastLineWidth = 10, stampWidth = 60, gap = 20, maxWidth = 180, sameDirection = false))
    }

    @Test
    fun `times, counts, amounts and figures with a short unit are figures, words are not`() {
        listOf("02:14", "60 s", "41 s", "2 s", "2184", "1,284", "12.5%", "$3.20", "-4", "84 ms", "3.2 GB").forEach {
            assertTrue(isFigure(it), it)
        }
        listOf("timed out", "ok", "nightly-export", "v1.2", "3 tests", "").forEach { assertFalse(isFigure(it), it) }
    }

    @Test
    fun `a wide table's columns share the room they leave in the card, and keep their widths past it`() {
        val widths = listOf(100.dp, 60.dp, 40.dp)
        assertEquals(listOf(120.dp, 80.dp, 60.dp), fittedWidths(widths, room = 260.dp))
        assertEquals(widths, fittedWidths(widths, room = 200.dp))
        assertEquals(widths, fittedWidths(widths, room = 120.dp))
        assertEquals(emptyList<Dp>(), fittedWidths(emptyList(), room = 120.dp))
    }

    @Test
    fun `each answer arrives once, the ones held already let go, and only the newest turns are remembered`() {
        val first = Arrival("turn-1", "One")
        val second = Arrival("turn-2", "Two")
        assertEquals(listOf(second), freshArrivals(listOf(first, second), handled = setOf("turn-1")))
        assertEquals(emptyList<Arrival>(), freshArrivals(listOf(first), handled = setOf("turn-1")))
        val many = (1..MAX_REMEMBERED_TURNS + 5).map { Arrival("turn-$it", "$it") }
        val kept = handledAfter(emptySet(), many)
        assertEquals(MAX_REMEMBERED_TURNS, kept.size)
        assertTrue("turn-${MAX_REMEMBERED_TURNS + 5}" in kept)
        assertFalse("turn-1" in kept)
    }

    private fun said(key: String) = ChatItem.Message(key, ShownMessage(Sender.Agent, key, null, Delivery.NONE))
}
