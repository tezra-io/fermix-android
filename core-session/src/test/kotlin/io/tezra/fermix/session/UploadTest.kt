package io.tezra.fermix.session

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.AttachStatus
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.MAX_RAW_BYTES
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.Locale

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

private val UPLOAD = ServerEvent.AttachStatusEvent("", AttachStatus.UPLOAD)
private val PRESENT = ServerEvent.AttachStatusEvent("", AttachStatus.PRESENT)

private fun DaemonConnection.status(
    of: ServerEvent.AttachStatusEvent,
    attachId: String,
) = send(of.copy(attachId = attachId))

/** Whether the phone has written no frame the daemon has not read; one it has is taken, and the test fails. */
private fun DaemonConnection.nothingSent(): Boolean = link.toDaemon.tryReceive().getOrNull() == null

/** A `msg` of [clientMsgId] with no words, carrying [attachments]. */
private fun msgOf(
    clientMsgId: String,
    vararg attachments: OutboxAttachment,
): ClientEvent.Msg = ClientEvent.Msg(clientMsgId, PROFILE, "", attachments.map { it.attachId })

/**
 * The upload unit (design section 8.5, PROTOCOL.md "Attachments") against the vendored fixtures where they exist,
 * `attach_begin`, `attach_chunk`, `attach_end`, `attach_status` and the `msg` that names its upload, and against
 * protocol v2's shapes where they do not, `kind: video` among them (design section 7, the `attach_begin.kind:
 * video` row): persisted before anything is written, each attachment in order and its chunks within the frame
 * bound, its `msg` only after its last `attach_end` is answered, each restart after a disconnect from
 * `attach_begin` again, three of them at most, and its progress for the bubble's ring.
 */
class UploadTest {
    @TempDir
    lateinit var dir: File

    private fun attachment(
        attachId: String,
        bytes: ByteArray,
        kind: AttachKind = AttachKind.IMAGE,
        mime: String = "image/jpeg",
        name: String? = "photo.jpg",
    ): OutboxAttachment {
        val file = File(dir, attachId).apply { writeBytes(bytes) }
        return OutboxAttachment(attachId, kind, mime, bytes.size.toLong(), sha256(bytes), name, file.path)
    }

    @Test
    fun `an attachment goes up as the vendored frames have it, and then its msg`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val (vendoredChunk, chunkBytes) = vendoredClientBinary("attach_chunk")
            val photo = attachment("attach-1", chunkBytes)
            val request = vendoredClient("msg", nth = 2) as ClientEvent.Msg
            harness.session.send(request, listOf(photo))
            val begin = vendoredClient("attach_begin") as ClientEvent.AttachBegin
            assertEquals(begin.copy(sha256 = photo.sha256), connection.expect<ClientEvent.AttachBegin>())
            connection.send(vendoredServer("attach_status", nth = 1))
            val chunk = connection.next()
            assertEquals(vendoredChunk, chunk.event)
            assertArrayEquals(chunkBytes, chunk.raw)
            val end = vendoredClient("attach_end") as ClientEvent.AttachEnd
            assertEquals(end.copy(sha256 = photo.sha256), connection.expect<ClientEvent.AttachEnd>())
            connection.send(vendoredServer("attach_status", nth = 2))
            assertEquals(request, connection.expect<ClientEvent.Msg>())
            val item = harness.store.items.single()
            assertTrue(item.written && item.attachments.single().uploaded && item.uploadStarts == 1)
        }

    @Test
    fun `the item is persisted with each attachment before any frame is written`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val clip = attachment("v1", ByteArray(10) { 3 }, AttachKind.VIDEO, "video/mp4", "clip.mp4")
            val stored = CompletableDeferred<Unit>()
            harness.store.enqueueGate = stored
            val sending = launch { harness.session.send(msgOf("m1", clip), listOf(clip)) }
            harness.settle()
            assertEquals(listOf(OutboxItem(msgOf("m1", clip), attachments = listOf(clip))), harness.store.items)
            assertTrue(connection.nothingSent(), "nothing is written before the item is stored")
            stored.complete(Unit)
            sending.join()
            assertEquals(AttachKind.VIDEO, connection.expect<ClientEvent.AttachBegin>().kind)
        }

    @Test
    fun `the msg is never written before its last attach_end is answered`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val first = attachment("a1", ByteArray(5) { 1 })
            val second =
                attachment("a2", ByteArray(MAX_RAW_BYTES + 7) { 2 }, AttachKind.DOCUMENT, "application/pdf", "r.pdf")
            harness.session.send(msgOf("m1", first, second), listOf(first, second))
            connection.expect<ClientEvent.AttachBegin>()
            connection.status(UPLOAD, "a1")
            connection.expect<ClientEvent.AttachChunk>()
            connection.expect<ClientEvent.AttachEnd>()
            connection.status(PRESENT, "a1")
            assertEquals("a2", connection.expect<ClientEvent.AttachBegin>().attachId)
            // a1 is in and a2 is not: a second offer of the outbox now must still hold the msg back.
            harness.session.send(msg("t2"))
            connection.status(UPLOAD, "a2")
            assertEquals(
                listOf(
                    0,
                    1,
                ),
                listOf(
                    connection.expect<ClientEvent.AttachChunk>(),
                    connection.expect<ClientEvent.AttachChunk>(),
                ).map {
                    it.index
                },
            )
            assertEquals("a2", connection.expect<ClientEvent.AttachEnd>().attachId)
            harness.settle()
            assertTrue(connection.nothingSent(), "nothing goes before the last attach_end is answered")
            assertFalse(harness.store.items.any { it.written })
            connection.status(PRESENT, "a2")
            assertEquals(listOf("a1", "a2"), connection.expect<ClientEvent.Msg>().attachIds)
            assertEquals("t2", connection.expect<ClientEvent.Msg>().clientMsgId)
        }

    @Test
    fun `chunks run from index 0, each at most 60 KiB, and add up to the source`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val bytes = ByteArray(2 * MAX_RAW_BYTES + 100) { (it % 251).toByte() }
            val big = attachment("big", bytes)
            harness.session.send(msgOf("m1", big), listOf(big))
            connection.expect<ClientEvent.AttachBegin>()
            connection.status(UPLOAD, "big")
            val chunks = List(3) { connection.next() }
            assertEquals(listOf(0, 1, 2), chunks.map { (it.event as ClientEvent.AttachChunk).index })
            assertTrue(chunks.all { it.raw.size <= MAX_RAW_BYTES })
            assertArrayEquals(bytes, chunks.fold(ByteArray(0)) { all, chunk -> all + chunk.raw })
            connection.expect<ClientEvent.AttachEnd>()
            val progress =
                harness.session.uploads.value
                    .getValue("big")
            assertEquals(
                UploadProgress("m1", "big", bytes.size.toLong(), bytes.size.toLong(), UploadStage.UPLOADING),
                progress,
            )
        }

    @Test
    fun `a digest the daemon holds goes with no chunk, and shows as sent instantly`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val photo = attachment("p1", ByteArray(100) { 9 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            connection.expect<ClientEvent.AttachBegin>()
            connection.status(PRESENT, "p1")
            assertEquals(listOf("p1"), connection.expect<ClientEvent.Msg>().attachIds)
            assertEquals(
                UploadStage.DUPLICATE,
                harness.session.uploads.value
                    .getValue("p1")
                    .stage,
            )
            connection.send(ServerEvent.Accepted("m1", duplicate = false))
            harness.settle()
            assertTrue(
                harness.session.uploads.value
                    .isEmpty(),
            )
        }

    @Test
    fun `a reconnect starts every attachment not yet in again from attach_begin, and skips the one that is`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            val done = attachment("a1", ByteArray(5) { 1 })
            val cut = attachment("a2", ByteArray(MAX_RAW_BYTES + 1) { 2 })
            harness.session.send(msgOf("m1", done, cut), listOf(done, cut))
            first.expect<ClientEvent.AttachBegin>()
            first.status(PRESENT, "a1")
            first.expect<ClientEvent.AttachBegin>()
            first.status(UPLOAD, "a2")
            first.expect<ClientEvent.AttachChunk>()
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            harness.settle()
            assertEquals(
                UploadStage.INTERRUPTED,
                harness.session.uploads.value
                    .getValue("a2")
                    .stage,
            )
            val second = harness.daemon.accept()
            second.connect()
            assertEquals(ClientEvent.RequestStatus(listOf("m1")), second.expect<ClientEvent.RequestStatus>())
            second.send(ServerEvent.RequestStatusPage(emptyList()))
            assertEquals("a2", second.expect<ClientEvent.AttachBegin>().attachId)
            second.status(UPLOAD, "a2")
            assertEquals(0, second.expect<ClientEvent.AttachChunk>().index)
            second.expect<ClientEvent.AttachChunk>()
            second.expect<ClientEvent.AttachEnd>()
            second.status(PRESENT, "a2")
            assertEquals(listOf("a1", "a2"), second.expect<ClientEvent.Msg>().attachIds)
            assertEquals(
                2,
                harness.store.items
                    .single()
                    .uploadStarts,
            )
        }

    @Test
    fun `an item restarts three times at most, fails upload_interrupted as the last is cut, never sending its msg`() =
        runTest {
            val harness = Harness(this)
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.store.items += OutboxItem(msgOf("m1", photo), attachments = listOf(photo))
            harness.open()
            repeat(1 + MAX_UPLOAD_RESTARTS) { start ->
                val connection = harness.daemon.accept()
                connection.connect()
                connection.expect<ClientEvent.RequestStatus>()
                connection.send(ServerEvent.RequestStatusPage(emptyList()))
                assertEquals("p1", connection.expect<ClientEvent.AttachBegin>().attachId)
                val held = harness.store.items.single()
                assertEquals(start + 1, held.uploadStarts)
                assertEquals(null, held.failure, "a start is not a cut")
                connection.close(NORMAL_CLOSURE, LIFETIME_REASON)
            }
            harness.settle()
            val failure = RequestFailure(UPLOAD_INTERRUPTED, "its upload was cut off 4 times")
            assertEquals(
                failure,
                harness.store.items
                    .single()
                    .failure,
                "the fourth cut fails it, with no connection after it",
            )
            assertTrue(SessionEvent.RequestFailed("m1", failure, inOutbox = true) in harness.events)
            val last = harness.daemon.accept()
            last.connect()
            last.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), last.next().event)
        }

    @Test
    fun `an item kept past its last start fails as it is read again, with no attach_begin`() =
        runTest {
            val harness = Harness(this)
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.store.items +=
                OutboxItem(msgOf("m1", photo), attachments = listOf(photo), uploadStarts = 1 + MAX_UPLOAD_RESTARTS)
            val connection = harness.connect()
            connection.expect<ClientEvent.RequestStatus>()
            connection.send(ServerEvent.RequestStatusPage(emptyList()))
            harness.settle()
            val failure = RequestFailure(UPLOAD_INTERRUPTED, "its upload was cut off 4 times")
            assertEquals(
                failure,
                harness.store.items
                    .single()
                    .failure,
            )
            assertEquals(
                1 + MAX_UPLOAD_RESTARTS,
                harness.store.items
                    .single()
                    .uploadStarts,
            )
            assertTrue(SessionEvent.RequestFailed("m1", failure, inOutbox = true) in harness.events)
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.next().event, "no attach_begin went")
        }

    @Test
    fun `an item kept at its third restart starts once more`() =
        runTest {
            val harness = Harness(this)
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.store.items +=
                OutboxItem(msgOf("m1", photo), attachments = listOf(photo), uploadStarts = MAX_UPLOAD_RESTARTS)
            val connection = harness.connect()
            connection.expect<ClientEvent.RequestStatus>()
            connection.send(ServerEvent.RequestStatusPage(emptyList()))
            assertEquals("p1", connection.expect<ClientEvent.AttachBegin>().attachId)
            assertEquals(
                1 + MAX_UPLOAD_RESTARTS,
                harness.store.items
                    .single()
                    .uploadStarts,
            )
            connection.status(PRESENT, "p1")
            assertEquals("m1", connection.expect<ClientEvent.Msg>().clientMsgId)
            assertEquals(
                null,
                harness.store.items
                    .single()
                    .failure,
            )
        }

    @Test
    fun `a daemon silent after attach_begin ends the connection at the answer's 30 s, and the next starts it again`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            first.expect<ClientEvent.AttachBegin>()
            val asked = harness.now
            // The link stays well: every ping is answered, so only the upload's silence can end the connection.
            backgroundScope.launch { first.answerPings(backgroundScope, afterMs = 10, mutableListOf()) }
            assertEquals(NORMAL_CLOSURE, first.phoneClosed().code)
            assertTrue(harness.now - asked in ANSWER_TIMEOUT_MS..<2 * ANSWER_TIMEOUT_MS, "the stall ends it at 30 s")
            assertEquals(
                UploadStage.INTERRUPTED,
                harness.session.uploads.value
                    .getValue("p1")
                    .stage,
            )
            val second = harness.daemon.accept()
            second.connect()
            second.expect<ClientEvent.RequestStatus>()
            second.send(ServerEvent.RequestStatusPage(emptyList()))
            assertEquals("p1", second.expect<ClientEvent.AttachBegin>().attachId)
            assertEquals(
                2,
                harness.store.items
                    .single()
                    .uploadStarts,
                "the stall counts as a cut",
            )
            second.status(PRESENT, "p1")
            assertEquals("m1", second.expect<ClientEvent.Msg>().clientMsgId)
        }

    @Test
    fun `a daemon silent after attach_end ends the connection, and a msg behind it goes on the next`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            harness.session.send(msg("t2"))
            first.expect<ClientEvent.AttachBegin>()
            first.status(UPLOAD, "p1")
            first.expect<ClientEvent.AttachChunk>()
            first.expect<ClientEvent.AttachEnd>()
            val ended = harness.now
            backgroundScope.launch { first.answerPings(backgroundScope, afterMs = 10, mutableListOf()) }
            assertEquals(NORMAL_CLOSURE, first.phoneClosed().code)
            assertTrue(harness.now - ended in ANSWER_TIMEOUT_MS..<2 * ANSWER_TIMEOUT_MS, "the stall ends it at 30 s")
            assertFalse(harness.store.items.any { it.written }, "the text never passed the stalled upload")
            val second = harness.daemon.accept()
            second.connect()
            second.expect<ClientEvent.RequestStatus>()
            second.send(ServerEvent.RequestStatusPage(emptyList()))
            assertEquals("p1", second.expect<ClientEvent.AttachBegin>().attachId)
            second.status(PRESENT, "p1")
            assertEquals("m1", second.expect<ClientEvent.Msg>().clientMsgId)
            assertEquals("t2", second.expect<ClientEvent.Msg>().clientMsgId)
        }

    @Test
    fun `a request_failed naming nothing while an upload waits ends the connection at once`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            first.expect<ClientEvent.AttachBegin>()
            first.status(UPLOAD, "p1")
            first.expect<ClientEvent.AttachChunk>()
            first.expect<ClientEvent.AttachEnd>()
            val ended = harness.now
            first.send(ServerEvent.Error("request_failed", "commit failed"))
            assertEquals(NORMAL_CLOSURE, first.phoneClosed().code)
            assertTrue(harness.now - ended < ANSWER_TIMEOUT_MS, "no 30 s wait for an answer that came")
            assertEquals(
                null,
                harness.store.items
                    .single()
                    .failure,
                "the next connection tries it again",
            )
            assertFalse(harness.events.any { it is SessionEvent.Refused })
            val second = harness.daemon.accept()
            second.connect()
            second.expect<ClientEvent.RequestStatus>()
            second.send(ServerEvent.RequestStatusPage(emptyList()))
            assertEquals("p1", second.expect<ClientEvent.AttachBegin>().attachId)
        }

    @Test
    fun `a msg after one that uploads waits for it, so the daemon has them in the outbox's order`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            harness.session.send(msg("t2"))
            connection.expect<ClientEvent.AttachBegin>()
            harness.settle()
            assertTrue(connection.nothingSent(), "the text waits behind the photo's msg")
            connection.status(PRESENT, "p1")
            assertEquals("m1", connection.expect<ClientEvent.Msg>().clientMsgId)
            assertEquals("t2", connection.expect<ClientEvent.Msg>().clientMsgId)
        }

    @Test
    fun `a command passes a msg that uploads, and a msg behind a failed upload goes`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            harness.session.send(ClientEvent.Command("c2", PROFILE, "status", null))
            harness.session.send(msg("t3"))
            connection.expect<ClientEvent.AttachBegin>()
            assertEquals("c2", connection.expect<ClientEvent.Command>().clientMsgId)
            connection.send(ServerEvent.Error("media_too_large", "past the largest blob"))
            assertEquals("t3", connection.expect<ClientEvent.Msg>().clientMsgId)
        }

    @Test
    fun `a dropped upload's late refusals are let go, and the next attach_begin is answered as its own`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val first = attachment("a1", ByteArray(MAX_RAW_BYTES + 1) { 1 })
            val second = attachment("b1", ByteArray(5) { 2 })
            harness.session.send(msgOf("m1", first), listOf(first))
            harness.session.send(msgOf("m2", second), listOf(second))
            connection.expect<ClientEvent.AttachBegin>()
            connection.status(UPLOAD, "a1")
            connection.expect<ClientEvent.AttachChunk>()
            connection.expect<ClientEvent.AttachChunk>()
            connection.expect<ClientEvent.AttachEnd>()
            connection.send(ServerEvent.Error("size_exceeded", "past its announced size"))
            connection.send(ServerEvent.Error("unknown_upload", "no such upload"))
            assertEquals("b1", connection.expect<ClientEvent.AttachBegin>().attachId)
            connection.send(ServerEvent.Error("unknown_upload", "no such upload"))
            connection.status(PRESENT, "b1")
            assertEquals("m2", connection.expect<ClientEvent.Msg>().clientMsgId)
            val failures = harness.store.items.associate { it.clientMsgId to it.failure?.code }
            assertEquals(mapOf("m1" to "size_exceeded", "m2" to null), failures)
        }

    @Test
    fun `a refusal while the chunks go stops them, and no attach_end follows`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val big = attachment("big", ByteArray(3 * MAX_RAW_BYTES) { 4 })
            connection.link.queued = UPLOAD_QUEUE_BYTES + 1
            harness.session.send(msgOf("m1", big), listOf(big))
            connection.expect<ClientEvent.AttachBegin>()
            connection.status(UPLOAD, "big")
            connection.send(ServerEvent.Error("store_quota_exceeded", "the media store is full"))
            harness.settle()
            connection.link.queued = 0L
            harness.settle()
            assertTrue(connection.nothingSent(), "no chunk and no attach_end after the refusal")
            assertEquals(
                "store_quota_exceeded",
                harness.store.items
                    .single()
                    .failure
                    ?.code,
            )
        }

    @Test
    fun `an upload the daemon refuses fails its item, and its msg never goes`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            connection.expect<ClientEvent.AttachBegin>()
            connection.send(ServerEvent.Error("store_quota_exceeded", "the media store is full"))
            harness.settle()
            val failure = RequestFailure("store_quota_exceeded", "p1 was refused")
            assertEquals(
                failure,
                harness.store.items
                    .single()
                    .failure,
            )
            assertFalse(harness.events.any { it is SessionEvent.Refused })
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.next().event)
        }

    @Test
    fun `a source that no longer holds its bytes fails the item before attach_end`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val photo = attachment("p1", ByteArray(5) { 1 })
            File(photo.source).writeBytes(ByteArray(5) { 2 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            connection.expect<ClientEvent.AttachBegin>()
            connection.status(UPLOAD, "p1")
            connection.expect<ClientEvent.AttachChunk>()
            harness.settle()
            assertEquals(
                UPLOAD_SOURCE_CHANGED,
                harness.store.items
                    .single()
                    .failure
                    ?.code,
            )
            assertTrue(connection.nothingSent())
        }

    @Test
    fun `an item removed while it uploads never sends its msg`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            connection.expect<ClientEvent.AttachBegin>()
            assertTrue(harness.session.remove("m1"))
            connection.status(PRESENT, "p1")
            harness.settle()
            assertTrue(harness.store.items.isEmpty())
            connection.send(row(1uL))
            assertEquals(ClientEvent.Ack(1uL), connection.next().event)
        }

    @Test
    fun `a chunk waits while the socket holds too much, then goes`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            connection.expect<ClientEvent.AttachBegin>()
            connection.link.queued = UPLOAD_QUEUE_BYTES + 1
            connection.status(UPLOAD, "p1")
            harness.settle()
            assertTrue(connection.nothingSent())
            connection.link.queued = 0L
            assertEquals(0, connection.expect<ClientEvent.AttachChunk>().index)
        }

    @Test
    fun `a msg whose attachments are in waits for the turn that shows, as any msg does`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            harness.session.send(msg("t1"))
            connection.expect<ClientEvent.Msg>()
            connection.send(ServerEvent.Accepted("t1", duplicate = false))
            val photo = attachment("p1", ByteArray(5) { 1 })
            harness.session.send(msgOf("m1", photo), listOf(photo))
            connection.expect<ClientEvent.AttachBegin>()
            connection.status(PRESENT, "p1")
            harness.settle()
            assertTrue(connection.nothingSent(), "the msg waits for the turn")
            connection.send(ServerEvent.TurnDone("turn-t1"))
            assertEquals(listOf("p1"), connection.expect<ClientEvent.Msg>().attachIds)
        }

    @Test
    fun `an upload is in flight from an item's first attach_begin until its msg goes, and not after a cut`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            assertFalse(harness.session.uploading.value)
            val first = attachment("a1", ByteArray(5) { 1 })
            val second = attachment("a2", ByteArray(5) { 2 })
            harness.session.send(msgOf("m1", first), listOf(first))
            harness.session.send(msgOf("m2", second), listOf(second))
            connection.expect<ClientEvent.AttachBegin>()
            assertTrue(harness.session.uploading.value)
            connection.status(PRESENT, "a1")
            connection.expect<ClientEvent.Msg>()
            harness.settle()
            assertTrue(harness.session.uploading.value, "the second item is still to go")
            assertEquals("a2", connection.expect<ClientEvent.AttachBegin>().attachId)
            connection.close(NORMAL_CLOSURE, LIFETIME_REASON)
            harness.settle()
            assertFalse(harness.session.uploading.value, "a cut upload is no longer in flight")
        }

    @Test
    fun `an attachment its msg does not name, or past ten, is refused before anything is stored`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            val photo = attachment("p1", ByteArray(5) { 1 })
            assertThrows<IllegalArgumentException> { harness.session.send(msg("m1"), listOf(photo)) }
            val many = List(MAX_ATTACHMENTS + 1) { attachment("x$it", ByteArray(1) { 1 }) }
            val tooMany = ClientEvent.Msg("m2", PROFILE, "", many.map { it.attachId })
            assertThrows<IllegalArgumentException> { harness.session.send(tooMany, many) }
            assertTrue(harness.store.items.isEmpty())
        }

    @Test
    fun `a transcript lands on the owner's cached row through the store, then reaches the app`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val note = TimelineRow.Message(message(3uL, role = "user", clientMsgId = "v1"))
            harness.store.cached[3uL] = note
            connection.send(ServerEvent.Transcript("v1", "Check the backup tonight"))
            harness.settle()
            val kept = assertInstanceOf<TimelineRow.Message>(harness.store.cached[3uL])
            assertEquals("Check the backup tonight", kept.message.content)
            assertTrue(SessionEvent.Transcript("v1", "Check the backup tonight", stored = true) in harness.events)
        }
}
