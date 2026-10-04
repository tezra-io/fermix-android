package io.tezra.fermix.data

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MediaRef
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.session.APPROVAL_ANSWER_PREFIX
import io.tezra.fermix.session.OutboxAttachment
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.SessionStore
import io.tezra.fermix.session.StoredCursors
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.linkPreviewsOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * core-session's SessionStore, rule by rule as its KDoc states them: each call one write, made whole or
 * not at all; the cursor and the ack frontier written together; the mutations applied to the rows the
 * cache holds and to no other; the rebuild dropping the cache and its cursor and keeping the ack frontier;
 * the outbox in the order it was enqueued, a dequeue of an id it does not hold no change, a failure
 * marked on the item the session names, an item marked written in place, and one withdrawn only while it
 * was never written or has failed. An implementation's test extends it, over the cache the store
 * shares a database with, and a fault that makes every write of the stored cursors fail. The fault lands
 * on the cursor, which each write records last, so a write that is not whole leaves what it did before.
 */
abstract class SessionStoreContract {
    protected abstract val store: SessionStore

    /** Persists [row] in the cache the store's mutations and rebuild act on, as the app's announcer does. */
    protected abstract suspend fun persist(row: TimelineRow)

    /** The row the cache holds at [serverSeq], if any. */
    protected abstract suspend fun cached(serverSeq: ULong): TimelineRow?

    /** Makes every later write of the stored cursors fail, as a full disk would. */
    protected abstract suspend fun failCursorWrites()

    @Test
    fun `a fresh store's cursors are all zero`() =
        runTest {
            assertEquals(cursors(0uL, 0uL, 0uL, 0uL, 0uL), store.cursors())
        }

    @Test
    fun `the server cursor and the ack frontier are written together, the read and mutation cursors kept`() =
        runTest {
            store.setReadFrontier(4uL)
            store.applyMutations(emptyList(), 9uL)
            store.setServerCursor(7uL, announcedUpToSeq = 5uL, lastUnannouncedSeq = 6uL)
            assertEquals(cursors(7uL, 4uL, 9uL, 5uL, 6uL), store.cursors())
        }

    @Test
    fun `a server cursor write that fails leaves the cursor and the ack frontier as they were`() =
        runTest {
            store.setServerCursor(3uL, announcedUpToSeq = 3uL, lastUnannouncedSeq = 0uL)
            failCursorWrites()
            assertNotNull(runCatching { store.setServerCursor(7uL, 5uL, 6uL) }.exceptionOrNull())
            assertEquals(cursors(3uL, 0uL, 0uL, 3uL, 0uL), store.cursors())
        }

    @Test
    fun `the read frontier is written alone`() =
        runTest {
            store.setServerCursor(8uL, announcedUpToSeq = 8uL, lastUnannouncedSeq = 0uL)
            store.setReadFrontier(6uL)
            assertEquals(cursors(8uL, 6uL, 0uL, 8uL, 0uL), store.cursors())
        }

    @Test
    fun `mutations update the cached rows they name in place, and the mutation cursor with them`() =
        runTest {
            val voiceNote = message(1uL, "user", "", mediaRefs = listOf(VOICE))
            val question = message(2uL, "user", "is it raining?")
            persist(TimelineRow.Message(voiceNote))
            persist(TimelineRow.Message(question))
            val transcript = MutationRow(1uL, 10uL, content = "is it raining?", metadata = TRANSCRIBED)
            val reaction = MutationRow(2uL, 11uL, metadata = REACTED)
            val uncached = MutationRow(9uL, 12uL, content = "a row this phone never cached")
            store.applyMutations(listOf(transcript, reaction, uncached), 12uL)

            val transcribed = voiceNote.copy(content = "is it raining?", metadata = TRANSCRIBED)
            assertEquals(TimelineRow.Message(transcribed), cached(1uL))
            assertEquals(TimelineRow.Message(question.copy(metadata = REACTED)), cached(2uL))
            assertNull(cached(9uL), "a mutation made a row the cache did not hold")
            assertEquals(cursors(0uL, 0uL, 12uL, 0uL, 0uL), store.cursors())
        }

    @Test
    fun `a mutation never gives an approval's answer its words back`() =
        runTest {
            val answer = message(3uL, "user", "").copy(clientMsgId = "${APPROVAL_ANSWER_PREFIX}ap-1:00")
            persist(TimelineRow.Message(answer))
            val words = MutationRow(3uL, 13uL, content = "/confirm opaque-token", metadata = REACTED)
            store.applyMutations(listOf(words), 13uL)
            assertEquals(TimelineRow.Message(answer.copy(metadata = REACTED)), cached(3uL))
        }

    @Test
    fun `mutations whose cursor write fails leave every row as it was`() =
        runTest {
            val question = message(2uL, "user", "is it raining?")
            persist(TimelineRow.Message(question))
            failCursorWrites()
            val reaction = MutationRow(2uL, 11uL, content = "edited", metadata = REACTED)
            assertNotNull(runCatching { store.applyMutations(listOf(reaction), 11uL) }.exceptionOrNull())
            assertEquals(TimelineRow.Message(question), cached(2uL))
            assertEquals(0uL, store.cursors().lastMutationSeq)
        }

    @Test
    fun `a rebuild drops the cache and the server cursor, and keeps the ack and read frontiers and the outbox`() =
        runTest {
            (1uL..3uL).forEach { seq -> persist(TimelineRow.Message(message(seq, "assistant", "row $seq"))) }
            store.setServerCursor(3uL, announcedUpToSeq = 2uL, lastUnannouncedSeq = 3uL)
            store.setReadFrontier(2uL)
            store.applyMutations(emptyList(), 5uL)
            store.enqueue(OutboxItem(msg("m1")))
            store.rebuildCache(mutationHeadSeq = 40uL)

            assertEquals(cursors(0uL, 2uL, 40uL, 2uL, 3uL), store.cursors())
            (1uL..3uL).forEach { seq -> assertNull(cached(seq), "row $seq outlived the rebuild") }
            assertEquals(listOf(OutboxItem(msg("m1"))), store.outbox())
        }

    @Test
    fun `a rebuild whose cursor write fails leaves the cache and its cursor`() =
        runTest {
            persist(TimelineRow.Message(message(1uL, "assistant", "kept")))
            store.setServerCursor(1uL, announcedUpToSeq = 1uL, lastUnannouncedSeq = 0uL)
            failCursorWrites()
            assertNotNull(runCatching { store.rebuildCache(40uL) }.exceptionOrNull())
            assertEquals(TimelineRow.Message(message(1uL, "assistant", "kept")), cached(1uL))
            assertEquals(cursors(1uL, 0uL, 0uL, 1uL, 0uL), store.cursors())
        }

    @Test
    fun `the outbox holds every item in the order it was enqueued`() =
        runTest {
            val retried = msg("m3").copy(attachIds = listOf("a1"), retryOf = "m0")
            val command = ClientEvent.Command("c4", PROFILE, "model", args = "default")
            store.enqueue(OutboxItem(msg("m1")))
            store.enqueue(OutboxItem(msg("m2")))
            store.enqueue(OutboxItem(retried))
            store.dequeue("m2")
            store.enqueue(OutboxItem(command))
            assertEquals(listOf(OutboxItem(msg("m1")), OutboxItem(retried), OutboxItem(command)), store.outbox())
        }

    @Test
    fun `a dequeue of an id the outbox does not hold is no change`() =
        runTest {
            store.enqueue(OutboxItem(msg("m1")))
            store.dequeue("m9")
            store.dequeue("m1")
            store.dequeue("m1")
            assertEquals(emptyList<OutboxItem>(), store.outbox())
        }

    @Test
    fun `a failure is marked on the item named, which keeps its place`() =
        runTest {
            val failure = RequestFailure("client_message_conflict", "content differs")
            store.enqueue(OutboxItem(msg("m1")))
            store.enqueue(OutboxItem(msg("m2")))
            store.markFailed("m1", failure)
            assertEquals(listOf(OutboxItem(msg("m1"), failure), OutboxItem(msg("m2"))), store.outbox())
        }

    @Test
    fun `an item is marked written in place, and an id the outbox does not hold is not`() =
        runTest {
            store.enqueue(OutboxItem(msg("m1")))
            store.enqueue(OutboxItem(msg("m2")))
            assertTrue(store.markWritten("m1"))
            assertTrue(store.markWritten("m1"))
            assertFalse(store.markWritten("m9"))
            assertEquals(listOf(OutboxItem(msg("m1"), written = true), OutboxItem(msg("m2"))), store.outbox())
        }

    @Test
    fun `an item never written or failed is withdrawn, and one written that did not fail stays`() =
        runTest {
            val failure = RequestFailure("client_message_conflict", "content differs")
            listOf("unwritten", "written", "failed").forEach { store.enqueue(OutboxItem(msg(it))) }
            store.markWritten("written")
            store.markWritten("failed")
            store.markFailed("failed", failure)
            assertTrue(store.withdraw("unwritten"))
            assertFalse(store.withdraw("written"))
            assertTrue(store.withdraw("failed"))
            assertFalse(store.withdraw("absent"))
            assertEquals(listOf(OutboxItem(msg("written"), written = true)), store.outbox())
        }

    @Test
    fun `a failure for an id the outbox does not hold fails, since the session never names one`() =
        runTest {
            store.enqueue(OutboxItem(msg("m1")))
            val refusal = runCatching { store.markFailed("m9", RequestFailure("x", "y")) }.exceptionOrNull()
            assertNotNull(refusal)
            assertEquals(listOf(OutboxItem(msg("m1"))), store.outbox())
        }

    @Test
    fun `a reaction lands in the owner's row's metadata, its other keys kept, and nowhere else`() =
        runTest {
            val asked = message(2uL, "user", "is it raining?").copy(clientMsgId = "c-2", metadata = TRANSCRIBED)
            val answer = message(3uL, "assistant", "yes").copy(clientMsgId = "c-2")
            persist(TimelineRow.Message(asked))
            persist(TimelineRow.Message(answer))
            assertTrue(store.applyReaction("c-2", "👍"))
            val reacted =
                buildJsonObject {
                    put("transcript", JsonPrimitive(true))
                    put("reaction", buildJsonObject { put("emoji", "👍") })
                }
            assertEquals(TimelineRow.Message(asked.copy(metadata = reacted)), cached(2uL))
            assertEquals(TimelineRow.Message(answer), cached(3uL), "only the owner's row is reacted to")
            assertFalse(store.applyReaction("c-9", "👍"), "a row the cache does not hold")
        }

    @Test
    fun `a link preview is kept on its row, a reply's too, once per url and four at most`() =
        runTest {
            persist(TimelineRow.Message(message(4uL, "assistant", "see https://example.com")))
            persist(TimelineRow.Reply(5uL, "turn-c-5", "see https://example.org", truncated = false, route = null))
            val example = LinkPreviewCard("https://example.com", "Example", "Example page")
            assertTrue(store.addLinkPreview(4uL, example))
            assertTrue(store.addLinkPreview(4uL, example))
            (1..5).forEach { store.addLinkPreview(5uL, example.copy(url = "https://example.org/$it")) }
            assertEquals(listOf(example), linkPreviewsOf(checkNotNull(cached(4uL))))
            assertEquals(
                (1..4).map { "https://example.org/$it" },
                linkPreviewsOf(checkNotNull(cached(5uL))).map { it.url },
            )
            assertFalse(store.addLinkPreview(9uL, example), "a row the cache does not hold")
        }

    @Test
    fun `an item keeps its attachments in order, each marked uploaded in place, and its upload starts`() =
        runTest {
            val photo = attachment("a1")
            val note = attachment("a2").copy(kind = AttachKind.AUDIO, mime = "audio/ogg", name = null)
            val request = ClientEvent.Msg("m1", PROFILE, "", listOf("a1", "a2"))
            store.enqueue(OutboxItem(request, attachments = listOf(photo, note)))
            assertTrue(store.setUploadStarts("m1", 2))
            assertTrue(store.markUploaded("m1", "a2"))
            val expected =
                OutboxItem(request, attachments = listOf(photo, note.copy(uploaded = true)), uploadStarts = 2)
            assertEquals(listOf(expected), store.outbox())
            assertFalse(store.markUploaded("m9", "a1"), "an item the outbox does not hold")
            assertFalse(store.setUploadStarts("m9", 1), "an item the outbox does not hold")
        }

    @Test
    fun `a transcript becomes the owner's row's content, and lands nowhere else`() =
        runTest {
            val note = message(6uL, "user", "").copy(clientMsgId = "v-6", mediaRefs = listOf(VOICE))
            persist(TimelineRow.Message(note))
            assertTrue(store.applyTranscript("v-6", "Check the backup tonight"))
            assertEquals(TimelineRow.Message(note.copy(content = "Check the backup tonight")), cached(6uL))
            assertFalse(store.applyTranscript("v-9", "nothing"), "a row the cache does not hold")
        }

    protected companion object {
        const val PROFILE = "main"
        val VOICE = MediaRef("media-1", "audio", "audio/ogg", 4_096L, sha256 = "ab".repeat(32))
        val TRANSCRIBED = buildJsonObject { put("transcript", JsonPrimitive(true)) }
        val REACTED = buildJsonObject { put("reaction", buildJsonObject { put("emoji", "👍") }) }

        fun msg(id: String) = ClientEvent.Msg(id, PROFILE, "hello from $id", emptyList())

        fun attachment(attachId: String) =
            OutboxAttachment(
                attachId,
                AttachKind.IMAGE,
                "image/jpeg",
                5L,
                "cd".repeat(32),
                "photo.jpg",
                "/staged/$attachId",
            )

        fun message(
            seq: ULong,
            role: String,
            content: String,
            mediaRefs: List<MediaRef> = emptyList(),
        ) = HistoryMessage(seq, role, content, "2026-10-01T09:00:0${seq % 10uL}Z", mediaRefs)

        fun cursors(
            lastServerSeq: ULong,
            readUpToSeq: ULong,
            lastMutationSeq: ULong,
            announcedUpToSeq: ULong,
            lastUnannouncedSeq: ULong,
        ) = StoredCursors(lastServerSeq, readUpToSeq, lastMutationSeq, announcedUpToSeq, lastUnannouncedSeq)
    }
}
