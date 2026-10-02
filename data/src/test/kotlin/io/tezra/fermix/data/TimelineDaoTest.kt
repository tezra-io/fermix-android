package io.tezra.fermix.data

import androidx.room.execSQL
import androidx.room.useWriterConnection
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MediaRef
import io.tezra.fermix.protocol.MessageKind
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The timeline cache (design section 9.1): every row the wire carries, kept as it came and read back
 * equal; a reply's sealed text standing in for its row until the history page brings the row whole; and
 * the local full-text search of design section 13.7, newest first.
 */
class TimelineDaoTest {
    private val database = inMemoryDatabase()
    private val timeline = database.timeline()

    @AfterEach
    fun close() = database.close()

    @Test
    fun `every row of the vendored fixtures is kept and read back equal`() =
        runTest {
            val events = fixtureServerEvents()
            val pages = events.filterIsInstance<ServerEvent.HistoryPage>().flatMap { it.messages }
            val rows = events.filterIsInstance<ServerEvent.Row>().map { it.asHistoryMessage() }
            val replies = events.filterIsInstance<ServerEvent.TextDone>().map { it.asReply() }
            val all = (pages + rows).map { TimelineRow.Message(it) } + replies
            assertTrue(all.size >= SOME_ROWS, "the fixtures hold ${all.size} rows")
            // Seqs repeat across the fixture's events, so each row is kept and read back on its own.
            all.forEach { row ->
                timeline.persist(row)
                assertEquals(row, timeline.row(row.serverSeq))
                if (row is TimelineRow.Message) assertColumns(row.message)
                database.cache().rebuildCache(0L)
            }
            val messages = all.filterIsInstance<TimelineRow.Message>().map { it.message }
            assertTrue(messages.any { it.kind != null }, "no fixture row has a kind")
            assertTrue(messages.any { it.mediaRefs.isNotEmpty() }, "no fixture row has media")
            assertTrue(messages.any { it.metadata != null }, "no fixture row has metadata")
            assertTrue(messages.any { it.linkPreviews != null }, "no fixture row has link previews")
        }

    /** The query columns of [message]'s cached row hold its fields, each read back as it was written. */
    private suspend fun assertColumns(message: HistoryMessage) {
        val entity = checkNotNull(timeline.entity(message.serverSeq.toColumn()))
        val wireKinds = mapOf(MessageKind.TEXT to "text", MessageKind.MEDIA to "media", MessageKind.SYSTEM to "system")
        assertEquals(message.kind?.let(wireKinds::getValue), entity.kind)
        assertEquals(message.role, entity.role)
        assertEquals(message.ts, entity.ts)
        assertEquals(message.content, entity.content)
        assertEquals(message.clientMsgId, entity.clientMsgId)
        assertEquals(message.inReplyTo, entity.inReplyTo)
        assertEquals(message.truncated, entity.truncated)
        assertEquals(message.mediaRefs, STORED_JSON.decodeFromString<List<MediaRef>>(checkNotNull(entity.mediaRefs)))
        assertEquals(message.metadata, entity.metadata?.let { STORED_JSON.decodeFromString<JsonObject>(it) })
        assertEquals(
            message.linkPreviews,
            entity.linkPreviews?.let { STORED_JSON.decodeFromString<List<LinkPreviewCard>>(it) },
        )
    }

    @Test
    fun `a reply stands in for its row until the history page brings the row whole, which replaces it`() =
        runTest {
            val reply = TimelineRow.Reply(5uL, "turn-1", "It is raining.", truncated = false, Route("openai", "gpt"))
            val whole = TimelineRow.Message(HistoryMessage(5uL, "assistant", "It is raining.", TS, emptyList()))
            timeline.persist(reply)
            assertEquals(reply, timeline.row(5uL))
            timeline.persistAll(listOf(whole))
            assertEquals(whole, timeline.row(5uL))
            timeline.persist(reply)
            assertEquals(whole, timeline.row(5uL), "a reply replaced the whole row")
        }

    @Test
    fun `an older page is kept in one go, and the newest rows come first`() =
        runTest {
            val page =
                (1uL..4uL).map { seq ->
                    TimelineRow.Message(HistoryMessage(seq, "user", "row $seq", TS, emptyList()))
                }
            timeline.persistAll(page)
            assertEquals(page.reversed().take(3), timeline.newest(3).first())
        }

    @Test
    fun `search finds the cached rows by word or by the start of one, newest first and at most the limit`() =
        runTest {
            persist(1uL, "The café on Main Street opens at nine")
            persist(2uL, "Main branch builds are green")
            persist(3uL, "Nothing to see here")
            persist(4uL, "MAINTENANCE window tonight")
            assertEquals(listOf(4uL, 2uL, 1uL), timeline.search("main", 10).map { it.serverSeq })
            assertEquals(listOf(4uL, 2uL), timeline.search("main", 2).map { it.serverSeq })
            assertEquals(listOf(1uL), timeline.search("CAFE street", 10).map { it.serverSeq })
            assertEquals(listOf(1uL), timeline.search("\"opens\" nine*", 10).map { it.serverSeq })
            assertEquals(emptyList<TimelineRow>(), timeline.search("opens NOT nine", 10))
            assertEquals(emptyList<TimelineRow>(), timeline.search("  \" * ", 10))
        }

    @Test
    fun `a word of punctuation alone in the query does not hide the rows the other words find`() =
        runTest {
            persist(1uL, "hello world!!!")
            persist(2uL, "Q&A at nine")
            assertEquals(listOf(1uL), timeline.search("hello -", 10).map { it.serverSeq })
            assertEquals(listOf(1uL), timeline.search("hello ?", 10).map { it.serverSeq })
            assertEquals(listOf(2uL), timeline.search("Q & A", 10).map { it.serverSeq })
            assertEquals(listOf(2uL), timeline.search("Q&A", 10).map { it.serverSeq })
            assertEquals(emptyList<TimelineRow>(), timeline.search("- & ?", 10))
        }

    @Test
    fun `a word is read as the index reads it, punctuation on it a separator and every term of it required`() =
        runTest {
            persist(1uL, "hello world")
            persist(2uL, "Maintenance window")
            persist(3uL, "rain today")
            persist(4uL, "rain 🌧 tonight")
            assertEquals(listOf(1uL), seqsOf("hel,"))
            assertEquals(listOf(2uL), seqsOf("main?"))
            assertEquals(listOf(2uL), seqsOf("(main),"))
            // The tokenizer separates only what Unicode 6.1 calls a space, punctuation or a symbol, whatever the
            // JVM's tables say. U+1F327 came later, so it is a term, which the JVM calls no letter or digit;
            // U+1F600 is a 6.1 symbol, a separator; U+1C80, a later letter, is a term to both.
            assertEquals(listOf(4uL), seqsOf("rain 🌧"))
            assertEquals(listOf(4uL, 3uL), seqsOf("rain 😀"))
            assertEquals(emptyList<ULong>(), seqsOf("rain ᲀ"))
        }

    private suspend fun seqsOf(query: String): List<ULong> = timeline.search(query, 10).map { it.serverSeq }

    @Test
    fun `an older page that fails partway keeps none of its rows`() =
        runTest {
            database.useWriterConnection { connection ->
                connection.execSQL(
                    "CREATE TRIGGER planted_row_fault BEFORE INSERT ON timeline WHEN NEW.server_seq = 3 " +
                        "BEGIN SELECT RAISE(ABORT, 'a planted fault'); END",
                )
            }
            val page = (1uL..4uL).map { seq -> message(seq, "row $seq") }
            assertNotNull(runCatching { timeline.persistAll(page) }.exceptionOrNull())
            (1uL..4uL).forEach { seq -> assertNull(timeline.row(seq), "row $seq of the failed page was kept") }
        }

    @Test
    fun `search follows the cache through an edit, a reply replaced by its row, and a rebuild`() =
        runTest {
            persist(1uL, "voice note")
            timeline.persist(TimelineRow.Reply(2uL, "turn-1", "draft answer", truncated = false, route = null))
            val store = RoomSessionStore(database)
            store.applyMutations(listOf(MutationRow(1uL, 1uL, content = "the transcript says umbrella")), 1uL)
            timeline.persist(TimelineRow.Message(HistoryMessage(2uL, "assistant", "final answer", TS, emptyList())))
            assertEquals(listOf(1uL), timeline.search("umbrella", 10).map { it.serverSeq })
            assertEquals(emptyList<TimelineRow>(), timeline.search("voice", 10))
            assertEquals(listOf(2uL), timeline.search("final", 10).map { it.serverSeq })
            assertEquals(emptyList<TimelineRow>(), timeline.search("draft", 10))
            store.rebuildCache(9uL)
            assertEquals(emptyList<TimelineRow>(), timeline.search("umbrella", 10))
        }

    private fun message(
        seq: ULong,
        content: String,
    ) = TimelineRow.Message(HistoryMessage(seq, "user", content, TS, emptyList()))

    private suspend fun persist(
        seq: ULong,
        content: String,
    ) = timeline.persist(message(seq, content))

    /** core-session's own mapping of a live `row` (TimelineRow.kt), which it keeps internal. */
    private fun ServerEvent.Row.asHistoryMessage() =
        HistoryMessage(
            serverSeq,
            role,
            text,
            ts,
            mediaRefs,
            kind,
            clientMsgId,
            inReplyTo,
            metadata,
            linkPreviews,
            truncated,
        )

    private fun ServerEvent.TextDone.asReply() = TimelineRow.Reply(serverSeq, turnId, text, truncated == true, route)

    private companion object {
        const val TS = "2026-10-01T09:00:00Z"

        /** The vendored fixtures' history pages, rows and sealed replies hold at least this many rows. */
        const val SOME_ROWS = 5
    }
}
