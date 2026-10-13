package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.ClientEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** An answer of three lines, as the agent's rows read. */
private const val ANSWER = "It timed out at 60 s.\n\nRaised it to 120 s.\nIt finished in 84 s."

/** A quote of [lines], whole. */
private fun quoted(vararg lines: String) = ReplyQuote(lines.toList(), cut = false)

/** [text]'s length in code points, as the quote's bound counts it. */
private fun points(text: String): Int = text.codePointCount(0, text.length)

/** What [quote] weighs in a frame as `text` carries it: its markers, its lines and its line breaks, escaped. */
private fun bytes(quote: ReplyQuote): Int = escapedSize(withQuote(quote, "?")) - 1

/** A question of 1,200 bytes, in a script three bytes a character. */
private val ASKED = "क".repeat(400)

/**
 * A reply's quote (the owner, 2026-10-11 and 2026-10-12): the message's lines, its leading quote and its markers
 * gone, whole up to the bound and cut at a line's end past it; the one shape the daemon sees, a blockquote, a blank
 * line and the question; the chip's parse of it back; and a reply never making a command.
 */
class RepliesTest {
    @Test
    fun `a quote is the message's lines, its blank lines dropped and CR LF read as LF`() {
        assertEquals(
            quoted("It timed out at 60 s.", "Raised it to 120 s.", "It finished in 84 s."),
            quoteOf(ANSWER.replace("\n", "\r\n") + "\r\n"),
        )
    }

    @Test
    fun `every line goes, however many, and the strip and the chip show three of them`() {
        val lines = (1..12).map { "line $it" }
        val quote = checkNotNull(quoteOf(lines.joinToString("\n")))
        assertEquals(quoted(*lines.toTypedArray()), quote)
        assertEquals(listOf("line 1", "line 2", "line 3$QUOTE_MARK"), shownLines(quote.lines))
        assertEquals(listOf("one", "two", "three"), shownLines(listOf("one", "two", "three")))
        assertEquals(listOf("one"), shownLines(listOf("one")))
    }

    @Test
    fun `each line's quote markers go, repeated, so a quote that is itself quoted stays flat`() {
        assertEquals(quoted("one", "two", "three", "four"), quoteOf("> one\n>> two\n> > three\n   > four"))
    }

    @Test
    fun `an owner's reply quotes its question, never the context it carried`() {
        val asked = withQuote(quoted("It timed out at 60 s."), "Why 60?")
        assertEquals(quoted("Why 60?"), quoteOf(asked))
    }

    @Test
    fun `the agent's markdown is quoted as written, only its quote markers go`() {
        assertEquals(
            quoted("**Raised** it to `120 s`.", "the job's default", "- one", "```sh", "make export", "```"),
            quoteOf("**Raised** it to `120 s`.\n\n> the job's default\n- one\n```sh\nmake export\n```"),
        )
    }

    @Test
    fun `a text with no words has no quote`() {
        assertNull(quoteOf(""))
        assertNull(quoteOf("  \n\r\n\t"))
        assertNull(quoteOf("> \n>>\n > "))
    }

    @Test
    fun `a quote at its bound goes whole, counted in code points`() {
        val exact = "x".repeat(1_000) + "\n" + "x".repeat(999)
        assertEquals(quoted("x".repeat(1_000), "x".repeat(999)), quoteOf(exact))
        val faces = "😀".repeat(15) + "x".repeat(1_980)
        assertFalse(checkNotNull(quoteOf(faces)).cut, "1,995 code points in 2,010 chars")
    }

    @Test
    fun `a quote past its bound is cut at the end of the last line that fits whole, marked`() {
        val line = "x".repeat(999)
        val quote = checkNotNull(quoteOf(listOf(line, line, line).joinToString("\n")))
        assertEquals(ReplyQuote(listOf(line, line + QUOTE_MARK), cut = true), quote)
        assertEquals(QUOTE_MAX_CHARS, points(quote.lines.joinToString("\n")))
        val over = checkNotNull(quoteOf("x".repeat(1_000) + "\n" + "x".repeat(1_000)))
        assertEquals(ReplyQuote(listOf("x".repeat(1_000) + QUOTE_MARK), cut = true), over)
    }

    @Test
    fun `a first line longer than the bound is cut on a character's edge`() {
        val quote = checkNotNull(quoteOf("x".repeat(2_500) + "\nmore"))
        val line = quote.lines.single()
        assertTrue(quote.cut)
        assertEquals(QUOTE_MAX_CHARS, points(line))
        assertEquals("x".repeat(QUOTE_MAX_CHARS - 1) + QUOTE_MARK, line)
        // Four bytes a face: (2,048 - 6 for the marker and the line breaks - 3 for the mark) / 4 = 509 whole faces.
        val faces = checkNotNull(quoteOf("😀".repeat(2_500)))
        assertEquals(ReplyQuote(listOf("😀".repeat(509) + QUOTE_MARK), cut = true), faces)
    }

    @Test
    fun `a quote weighs at most half a frame, so a question still goes with it whatever the script`() {
        val line = "क".repeat(49)
        val long = checkNotNull(quoteOf(List(40) { line }.joinToString("\n")))
        assertEquals(ReplyQuote(List(12) { line } + (line + QUOTE_MARK), cut = true), long, "1,999 code points")
        val three = checkNotNull(quoteOf("क".repeat(QUOTE_MAX_CHARS)))
        val escaped = checkNotNull(quoteOf("\"".repeat(QUOTE_MAX_CHARS)))
        val quotes = listOf(long, three, escaped)
        quotes.forEach { quote ->
            assertTrue(quote.cut)
            assertTrue(bytes(quote) <= QUOTE_MAX_BYTES, "${bytes(quote)} bytes")
            assertTrue(fitsOneMsg(withQuote(quote, ASKED), PROFILE), "a question of 1,200 bytes goes with it")
        }
        assertEquals("क".repeat(679) + QUOTE_MARK, three.lines.single(), "three bytes a character")
        assertEquals("\"".repeat(1_019) + QUOTE_MARK, escaped.lines.single(), "escaped, two bytes a quote mark")
    }

    @Test
    fun `withQuote puts each line after a marker, then a blank line, then the words trimmed`() {
        assertEquals("> one\n> two\n\nwhy?", withQuote(quoted("one", "two"), "  why?\n"))
    }

    @Test
    fun `the chip reads the quote and the words after it back, as withQuote wrote them`() {
        val quote = ReplyQuote(listOf("one", " two", "**three** $QUOTE_MARK"), cut = true)
        assertEquals(LeadingQuote(quote.lines, "why?\nand how?"), leadingQuote(withQuote(quote, "why?\nand how?")))
        assertEquals(LeadingQuote(listOf("a", "b"), "c"), leadingQuote(">a\r\n> b\r\n\r\nc"))
        assertEquals(LeadingQuote(listOf("a"), "c"), leadingQuote("> a\n>\n\nc"))
    }

    @Test
    fun `a text that does not start with a quote, or is only one, has no leading quote`() {
        assertNull(leadingQuote("why?\n> no"))
        assertNull(leadingQuote(" > a\n\nb"))
        assertNull(leadingQuote("> only a quote"))
        assertNull(leadingQuote("> a quote\n\n  \n"))
        assertNull(leadingQuote("> a quote\nrunning on"))
        assertNull(leadingQuote(">\n\nwords"))
    }

    @Test
    fun `a reply never makes a command, as its words start with the quote`() {
        val text = withQuote(quoted("It timed out at 60 s."), "/stop")
        assertEquals(ClientEvent.Msg("c1", PROFILE, text, emptyList()), requestOf(text, COMMANDS, "c1", PROFILE))
        assertEquals(ClientEvent.Command("c2", PROFILE, "stop", null), requestOf("/stop", COMMANDS, "c2", PROFILE))
    }

    @Test
    fun `the quote goes ahead of words alone, and a reply with no words adds nothing`() {
        val draft = ReplyDraft(Sender.Agent, quoted("It timed out at 60 s."), 4uL, null)
        assertEquals("Why 60?", composedOf(null, "Why 60?"))
        assertEquals(" \n", composedOf(draft, " \n"))
        assertEquals(withQuote(draft.quote, "Why 60?"), composedOf(draft, "Why 60?"))
    }

    @Test
    fun `a message with words that does not stream takes a reply, and no other`() {
        val answer = ShownMessage(Sender.Agent, ANSWER, wallAt(1), Delivery.NONE)
        assertTrue(takesReply(answer))
        assertFalse(takesReply(answer.copy(streaming = true)))
        assertFalse(takesReply(answer.copy(text = " \n")))
        assertFalse(takesReply(answer.copy(text = "> >")))
    }

    @Test
    fun `the strip names the Fermix for the agent's message and none for the owner's, with three lines`() {
        val quote = checkNotNull(quoteOf("one\ntwo\nthree\nfour"))
        val agents = ReplyDraft(Sender.Agent, quote, 4uL, null, revision = 2)
        assertEquals(ReplyUi("suj-mbp", listOf("one", "two", "three$QUOTE_MARK"), 2), replyUiOf(agents, "suj-mbp"))
        assertNull(replyUiOf(agents.copy(sender = Sender.User, clientMsgId = "m3"), "suj-mbp").to)
    }
}
