package io.tezra.fermix.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** An answer's parts: prose in bubbles, top-level fences and tables as cards (design section 8.3). */
class SegmentsTest {
    private val answer =
        """
        The job stopped at the export step.

        ```elixir report_job.ex
        def export(report) do
        end
        ```

        | Report | Rows |
        |---|---:|
        | sales | 48,210 |

        I raised the timeout.
        """.trimIndent()

    @Test
    fun `a closed fence and a table are cards between the prose`() {
        val parts = segmentsOf(answer, streaming = false)
        assertEquals(4, parts.size)
        assertEquals("The job stopped at the export step.", (parts[0] as Segment.Prose).markdown.trim())
        val code = parts[1] as Segment.Code
        assertEquals(FenceInfo("elixir", "report_job.ex"), code.info)
        assertEquals("def export(report) do\nend", code.code)
        assertEquals(
            TableCells(listOf("Report", "Rows"), listOf(listOf("sales", "48,210"))),
            (parts[2] as Segment.Table).table,
        )
        assertEquals("I raised the timeout.", (parts[3] as Segment.Prose).markdown.trim())
    }

    @Test
    fun `while streaming an open fence stays in the prose, and once sealed it is a card`() {
        val open = "I changed two things:\n\n```kotlin\nval timeout = 30"
        val streaming = segmentsOf(open, streaming = true)
        assertEquals(listOf(Segment.Prose(0, open)), streaming)
        val sealed = segmentsOf(open, streaming = false)
        assertEquals(2, sealed.size)
        assertEquals("val timeout = 30", (sealed[1] as Segment.Code).code)
    }

    @Test
    fun `a part keeps its start as the answer grows`() {
        val first = segmentsOf("Intro\n\n```sh\nls\n```\n\nMore", streaming = true)
        val grown = segmentsOf("Intro\n\n```sh\nls\n```\n\nMore words", streaming = true)
        assertEquals(first.map { it.start }, grown.map { it.start })
    }

    @Test
    fun `reading only past what is settled gives the parts a whole read gives, wherever the held text ends`() {
        val sample =
            "Intro line\nsecond line\n\n```sh\nls -la\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\ntail | x\n\n" +
                "- a list item\n- another\n\n```kotlin\nval open = 1\n"
        (0..sample.length).forEach { held ->
            val text = sample.take(held)
            listOf(true, false).forEach { streaming ->
                val before = segmentsOf(text, streaming)
                val after = segmentsAfter(before, held, sample, streaming)
                assertEquals(segmentsOf(sample, streaming), after, "held $held, streaming $streaming")
            }
        }
    }

    @Test
    fun `the cursor stands after a paragraph's words, after an open fence's chip, and below anything else`() {
        assertEquals(CursorHome.PARAGRAPH, cursorHome("The worker restarts"))
        assertEquals(CursorHome.PARAGRAPH, cursorHome("Steps:\n\n- one\n- two, and"))
        assertEquals(CursorHome.PARAGRAPH, cursorHome("> quoted and"))
        assertEquals(CursorHome.FENCE, cursorHome("Here:\n\n```kotlin\nval x"))
        assertEquals(CursorHome.OTHER, cursorHome("Here:\n\n```kotlin\nval x\n```"))
        assertEquals(CursorHome.OTHER, cursorHome("## A heading"))
        assertEquals(CursorHome.OTHER, cursorHome(""))
    }
}
