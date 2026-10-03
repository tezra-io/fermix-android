package io.tezra.fermix.chats

import io.tezra.fermix.instance.Link
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.session.APPROVAL_ANSWER_PREFIX
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.transport.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** A Chats row's second line, title parts and time (design sections 9.2 and 9.4). */
class ChatRowsTest {
    private val now = Instant.parse("2026-09-27T12:00:00Z")
    private val nowUtc = now.atZone(ZoneOffset.UTC)
    private val up = Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true)

    private fun row(
        link: Link = up,
        thinking: Boolean = false,
        draft: String? = null,
        newest: List<TimelineRow> = listOf(message(7, "Raised the export timeout.")),
    ) = rowOf(
        sample(1),
        "main",
        RowFacts(link, thinking, PLAIN_CHAT.copy(draft = draft), newest, unread = 2),
        nowUtc,
        Locale.US,
    )

    @Test
    fun `the second line is a speaking link, then thinking, then the draft, then the last message`() {
        assertEquals(RowLine.Speaks(Link.Revoked), row(link = Link.Revoked, thinking = true, draft = "x").line)
        assertEquals(RowLine.Thinking, row(thinking = true, draft = "restart the worker").line)
        assertEquals(RowLine.Draft("restart the worker"), row(draft = "restart the worker").line)
        assertEquals(RowLine.Message("Raised the export timeout."), row().line)
        assertEquals(RowLine.Empty, row(newest = emptyList()).line)
    }

    @Test
    fun `a row whose link ended on 1002 says protocol error, never revoked`() {
        val failed = row(link = Link.ProtocolError)
        assertEquals(RowLine.Speaks(Link.ProtocolError), failed.line)
        assertFalse(failed.line == RowLine.Speaks(Link.Revoked))
    }

    @Test
    fun `an offline or connecting row keeps its last message`() {
        assertEquals(RowLine.Message("Raised the export timeout."), row(link = Link.CannotReach).line)
        assertEquals(RowLine.Message("Raised the export timeout."), row(link = Link.Connecting).line)
    }

    @Test
    fun `the time is the newest message's, a reply's bubble having none, and an unreadable one is none`() {
        val reply = TimelineRow.Reply(8u, "turn-1", "Done.", truncated = false, route = null)
        val sealed = row(newest = listOf(reply, message(7, "Run it", ts = "2026-09-27T09:41:00Z")))
        assertEquals(RowLine.Message("Done."), sealed.line)
        // In the locale the row is handed, whatever the JVM's own is.
        assertEquals("9:41\u202FAM", sealed.time)
        val facts = RowFacts(up, thinking = false, PLAIN_CHAT, listOf(message(7, "Run it")), unread = 0)
        assertEquals("09:41", rowOf(sample(1), "main", facts, nowUtc, Locale.GERMANY).time)
        assertNull(row(newest = listOf(message(7, "x", ts = "yesterday"))).time)
    }

    @Test
    fun `a row's time is the hour today, the weekday this week, and the date before`() {
        val utc = ZoneOffset.UTC
        // CLDR sets a narrow no-break space before the day period.
        assertEquals("9:41\u202FAM", rowTime(Instant.parse("2026-09-27T09:41:00Z"), now, utc, Locale.US))
        assertEquals("Tue", rowTime(Instant.parse("2026-09-22T09:41:00Z"), now, utc, Locale.US))
        assertEquals("9/20/26", rowTime(Instant.parse("2026-09-20T09:41:00Z"), now, utc, Locale.US))
    }

    @Test
    fun `the title carries the agent's name unless it is Fermix, and the DEV tag follows the profile or the name`() {
        val named = row().copy(agentName = "Juno")
        assertEquals("Juno", named.agent)
        assertNull(row().copy(agentName = "Fermix").agent)
        val dev =
            rowOf(
                sample(2, profile = "fermix-dev"),
                "main",
                RowFacts(up, false, PLAIN_CHAT, emptyList(), 0),
                nowUtc,
                Locale.US,
            )
        assertTrue(dev.dev)
        assertFalse(row().dev)
    }

    @Test
    fun `an approval's answer or a model pick is never the last message, nor its time, the row before them is`() {
        val answerId = "${APPROVAL_ANSWER_PREFIX}a:0"
        val answeredAt = "2026-09-27T11:00:00Z"
        val answer = TimelineRow.Message(HistoryMessage(9u, "user", "", answeredAt, listOf(), clientMsgId = answerId))
        val pick =
            TimelineRow.Message(
                HistoryMessage(
                    8u,
                    "user",
                    "/model codex/gpt-6-luna",
                    "2026-09-27T10:00:00Z",
                    emptyList(),
                    // feature-chat's MODEL_PICK_PREFIX, which it keeps internal.
                    clientMsgId = "model-pick:m3",
                ),
            )
        val shown = row(newest = listOf(answer, pick, message(7, "Raised the export timeout.")))
        assertEquals(RowLine.Message("Raised the export timeout."), shown.line)
        assertEquals("9:41\u202FAM", shown.time)
    }

    @Test
    fun `the last message is the plain words of the daemon's markdown`() {
        val marked = message(7, "**Raised** the `export` timeout, see [the log](https://x.test/log).")
        assertEquals(RowLine.Message("Raised the export timeout, see the log."), row(newest = listOf(marked)).line)
        val reply = TimelineRow.Reply(8u, "turn-1", "# Done\n\n- exported", truncated = false, route = null)
        assertEquals(RowLine.Message("Done exported"), row(newest = listOf(reply)).line)
    }
}
