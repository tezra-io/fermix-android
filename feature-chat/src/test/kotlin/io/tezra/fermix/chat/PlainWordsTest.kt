package io.tezra.fermix.chat

import io.tezra.fermix.session.TimelineRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A Chats row's last message reads the daemon's markdown as its plain words. */
class PlainWordsTest {
    @Test
    fun `emphasis, code and headings lose their marks`() {
        assertEquals("Raised the timeout to 300 s.", plainWords("**Raised** the `timeout` to _300 s_."))
        assertEquals("Done Exported the report.", plainWords("# Done\n\nExported the report."))
        assertEquals("one two", plainWords("- one\n- two"))
        assertEquals("gone", plainWords("~~gone~~"))
    }

    @Test
    fun `a link keeps its words and loses where it goes`() {
        assertEquals(
            "The report is in reports/today.pdf.",
            plainWords("The report is in [reports/today.pdf](https://x.test/r)."),
        )
        assertEquals("see docs", plainWords("see [docs][1]\n\n[1]: https://x.test \"Docs\""))
        assertEquals("a chart", plainWords("![a chart](https://x.test/c.png)"))
    }

    @Test
    fun `a star that is no markup stays`() {
        assertEquals("2 * 3 = 6", plainWords("2 * 3 = 6"))
        assertEquals("*not emphasis*", plainWords("\\*not emphasis\\*"))
    }

    @Test
    fun `a fence keeps its code, not its language, and raw HTML stays as written`() {
        assertEquals("def export do end", plainWords("```elixir\ndef export do\nend\n```"))
        assertEquals("<b>raw</b> text", plainWords("<b>raw</b> text"))
    }

    @Test
    fun `a table reads cell by cell`() {
        assertEquals("Report Rows sales 48,210", plainWords("| Report | Rows |\n|---|---:|\n| sales | 48,210 |"))
    }

    @Test
    fun `a row's words are the agent's plain words, and the owner's as typed`() {
        assertEquals("Raised the timeout.", rowWords(agentRow(2, "**Raised** the `timeout`.")))
        assertEquals("**as typed**", rowWords(userRow(1, "**as typed**")))
        val reply =
            TimelineRow.Reply(
                3uL,
                "turn-m1",
                "Sent to [the team](https://x.test).",
                truncated = false,
                route = null,
            )
        assertEquals("Sent to the team.", rowWords(reply))
    }
}
