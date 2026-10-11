package io.tezra.fermix.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What the thinking card's header says and which chips stand under it (the owner, 2026-10-10): while a tool runs the
 * header names the newest running one, which is not also a chip; every other tool is a chip in the place it started
 * in, so the header's tool lands among them under its own place once it stops.
 */
class CardHeaderTest {
    private fun card(vararg chips: LiveChip) = LiveCard(0L, emptyList(), chips.toList(), speaking = chips.isNotEmpty())

    private fun running(tool: String) = LiveChip(tool, running = true, status = null)

    private fun finished(tool: String) = LiveChip(tool, running = false, status = "ok")

    @Test
    fun `with no tool running the header is the phrase and every tool is a chip`() {
        assertEquals(CardHeader(tool = null, chips = emptyList()), cardHeader(card()))
        val done = card(finished("file_read"), finished("shell"))
        val chips = listOf(IndexedValue(0, finished("file_read")), IndexedValue(1, finished("shell")))
        assertEquals(CardHeader(tool = null, chips = chips), cardHeader(done))
    }

    @Test
    fun `a running tool is the header's and no chip, the finished ones chips in their places`() {
        val header = cardHeader(card(finished("file_read"), running("generate_image")))
        assertEquals("generate_image", header.tool)
        assertEquals(listOf(IndexedValue(0, finished("file_read"))), header.chips)
    }

    @Test
    fun `of two running tools the header names the newest, and the other runs as a chip`() {
        val header = cardHeader(card(running("shell"), finished("file_read"), running("web_search")))
        assertEquals("web_search", header.tool)
        assertEquals(listOf(IndexedValue(0, running("shell")), IndexedValue(1, finished("file_read"))), header.chips)
    }

    @Test
    fun `the header's tool stopping lands it as a chip in its own place, and the header goes back to the phrase`() {
        val before = cardHeader(card(finished("file_read"), running("generate_image")))
        val after = cardHeader(card(finished("file_read"), finished("generate_image")))
        assertNull(after.tool)
        assertEquals(before.chips + IndexedValue(1, finished("generate_image")), after.chips)
    }

    @Test
    fun `the header's tool stopping hands the header to the next one still running`() {
        val header = cardHeader(card(running("shell"), finished("generate_image")))
        assertEquals("shell", header.tool)
        assertEquals(listOf(IndexedValue(1, finished("generate_image"))), header.chips)
    }
}
