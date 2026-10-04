package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.MediaRef
import io.tezra.fermix.session.MAX_ATTACHMENTS
import io.tezra.fermix.session.OutboxAttachment
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.UploadProgress
import io.tezra.fermix.session.UploadStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private val DIGEST = "ab".repeat(32)
private const val MAX = 20_000_000L

private fun picked(
    name: String,
    kind: PickedKind = PickedKind.IMAGE,
    size: Long = 1_000L,
    uri: String = "content://media/$name",
) = Picked(name, uri, kind, "x/y", name, size, PickedFrom.FILES)

private fun attachment(
    id: String,
    kind: AttachKind = AttachKind.IMAGE,
    uploaded: Boolean = false,
) = OutboxAttachment(id, kind, "image/jpeg", 1_000L, DIGEST, "$id.jpg", "/staged/$id", uploaded)

private fun item(vararg attachments: OutboxAttachment): OutboxItem =
    OutboxItem(
        ClientEvent.Msg("m1", PROFILE, "look", attachments.map { it.attachId }),
        attachments = attachments.toList(),
    )

/**
 * How attachments go and show (design sections 8.5, 13.5 and 13.9): what each picked item goes as, the size line
 * and the ten-item cap; the image layouts and the single image's clamped aspect; a row's blobs by shape; an
 * outbox item's ring and the line under it.
 */
class MediaItemsTest {
    @Test
    fun `an image goes as a JPEG image unless sent as a file, a video as video, a note as audio, the rest documents`() {
        assertEquals(AttachKind.IMAGE, attachKindOf(picked("a.heic"), asFiles = false))
        assertEquals(AttachKind.DOCUMENT, attachKindOf(picked("a.heic"), asFiles = true))
        assertEquals(AttachKind.VIDEO, attachKindOf(picked("v.mp4", PickedKind.VIDEO), asFiles = false))
        assertEquals(AttachKind.AUDIO, attachKindOf(picked("n.ogg", PickedKind.VOICE), asFiles = false))
        assertEquals(AttachKind.DOCUMENT, attachKindOf(picked("s.mp3", PickedKind.AUDIO), asFiles = false))
        assertEquals(AttachKind.DOCUMENT, attachKindOf(picked("r.pdf", PickedKind.FILE), asFiles = false))
        assertEquals(PickedKind.VIDEO, pickedKindOf("video/quicktime"))
        assertEquals(PickedKind.FILE, pickedKindOf("text/html"))
    }

    @Test
    fun `an item past the limit that goes as its own bytes gets the line and stays, an image made a JPEG goes`() {
        val video = picked("video.mov", PickedKind.VIDEO, size = 34_000_000L)
        val photo = picked("huge.heic", size = 34_000_000L)
        val items = listOf(photo, video, picked("small.pdf", PickedKind.FILE))
        assertEquals(TooBig("video.mov", 34_000_000L, MAX), tooBigOf(items, asFiles = false, MAX))
        assertEquals(listOf("huge.heic", "small.pdf"), sendableOf(items, asFiles = false, MAX).map { it.name })
        assertEquals(TooBig("huge.heic", 34_000_000L, MAX), tooBigOf(items, asFiles = true, MAX))
        assertEquals(listOf("small.pdf"), sendableOf(items, asFiles = true, MAX).map { it.name })
        val ui = attachUiOf(items, sheet = true, asFiles = false, MAX)
        assertEquals(2, ui.sendable)
    }

    @Test
    fun `at most ten items, each once by its URI, in the order picked`() {
        val first = (1..6).map { picked("p$it") }
        val more = (4..14).map { picked("p$it") }
        val held = withPicked(first, more)
        assertEquals(MAX_ATTACHMENTS, held.size)
        assertEquals((1..10).map { "p$it" }, held.map { it.name })
    }

    @Test
    fun `one, two and three images show all, four and more a 2 x 2 with +N, and one image's aspect is clamped`() {
        assertEquals(ImageLayout(1, 0), imageLayoutOf(1))
        assertEquals(ImageLayout(2, 0), imageLayoutOf(2))
        assertEquals(ImageLayout(3, 0), imageLayoutOf(3))
        assertEquals(ImageLayout(4, 0), imageLayoutOf(4))
        assertEquals(ImageLayout(4, 1), imageLayoutOf(5))
        assertEquals(16f / 9f, singleAspect(4_000, 1_000))
        assertEquals(3f / 4f, singleAspect(1_000, 4_000))
        assertEquals(1.5f, singleAspect(1_500, 1_000))
        assertEquals(4f / 3f, singleAspect(0, 0))
    }

    @Test
    fun `a row's image shows as an image, the owner's audio as a voice note, everything else as a document`() {
        val refs =
            listOf(
                MediaRef("r1", "image", "image/png", 10, DIGEST),
                MediaRef("r2", "audio", "audio/ogg", 10),
                MediaRef("r3", "video", "video/mp4", 10, filename = "clip.mp4"),
                MediaRef("r4", "document", "text/html", 10, filename = "page.html"),
            )
        assertEquals(
            listOf(MediaShape.IMAGE, MediaShape.VOICE, MediaShape.DOCUMENT, MediaShape.DOCUMENT),
            rowMedia(refs, user = true).map { it.shape },
        )
        assertEquals(MediaShape.DOCUMENT, rowMedia(refs, user = false)[1].shape)
        assertEquals(DIGEST, rowMedia(refs, user = true)[0].cacheName)
        assertEquals("r2", rowMedia(refs, user = true)[1].cacheName)
    }

    @Test
    fun `an outbox image draws from its staged file with its ring, none once it is in`() {
        val waiting = attachment("a1")
        val done = attachment("a2", uploaded = true)
        val outbox = item(waiting, done)
        val progress = mapOf("a1" to UploadProgress("m1", "a1", 250L, 1_000L, UploadStage.UPLOADING))
        val media = outboxMedia(outbox, progress)
        assertEquals(listOf(0.25f, null), media.map { it.sent })
        assertEquals("/staged/a1", media[0].local)
        assertEquals(0f, outboxMedia(outbox, emptyMap())[0].sent)
        val present = mapOf("a1" to UploadProgress("m1", "a1", 0L, 1_000L, UploadStage.DUPLICATE))
        assertNull(outboxMedia(outbox, present)[0].sent)
        val failed = outbox.copy(failure = RequestFailure("upload_interrupted", "cut off 4 times"))
        assertNull(outboxMedia(failed, progress)[0].sent, "a failed item goes up no more: no ring")
    }

    @Test
    fun `the line says interrupted while a cut upload waits for a connection, sent instantly on present, else none`() {
        val outbox = item(attachment("a1"), attachment("a2"))
        val cut = mapOf("a1" to UploadProgress("m1", "a1", 10L, 1_000L, UploadStage.INTERRUPTED))
        assertEquals(UploadLine.INTERRUPTED, uploadLineOf(outbox, cut, connected = true))
        val begun = outbox.copy(uploadStarts = 1)
        assertEquals(UploadLine.INTERRUPTED, uploadLineOf(begun, emptyMap(), connected = false))
        assertNull(uploadLineOf(outbox, emptyMap(), connected = false), "composed offline, nothing was cut: Queued")
        assertNull(uploadLineOf(outbox, emptyMap(), connected = true))
        val present = mapOf("a1" to UploadProgress("m1", "a1", 0L, 1_000L, UploadStage.DUPLICATE))
        assertEquals(UploadLine.DUPLICATE, uploadLineOf(outbox, present, connected = true))
        val failed = outbox.copy(failure = RequestFailure("upload_interrupted", "cut off 4 times"))
        assertNull(uploadLineOf(failed, cut, connected = false))
    }

    @Test
    fun `an outbox item with attachments offers Edit and Remove until written, and takes them to a retry`() {
        val outbox = item(attachment("a1"))
        val inputs =
            ChatInputs(
                emptyList(),
                listOf(outbox),
                emptyList(),
                ChatLive(),
                connected = true,
                unreadAt = null,
                requests = emptyMap(),
                profileId = PROFILE,
                nowWall = 0L,
                zone = UTC,
            )
        val message = outboxMessage(outbox, Delivery.SENDING, inputs)
        assertEquals(listOf(OutboxEntry.EDIT, OutboxEntry.REMOVE), outboxMenuOf(message))
        assertEquals(outbox.attachments, message.attachments)
        val refused = outbox.copy(failure = RequestFailure("attachment_unavailable", "gone"))
        assertEquals(outbox.attachments, notSentError(refused).attachments)
    }

    @Test
    fun `an outbox voice note waits for its words only while the daemon transcribes notes`() {
        val note = item(attachment("v1", AttachKind.AUDIO))
        val inputs =
            ChatInputs(
                emptyList(),
                listOf(note),
                emptyList(),
                ChatLive(),
                connected = true,
                unreadAt = null,
                requests = emptyMap(),
                profileId = PROFILE,
                nowWall = 0L,
                zone = UTC,
            )
        assertEquals(true, outboxMessage(note, Delivery.SENDING, inputs.copy(transcripts = true)).transcribing)
        assertEquals(false, outboxMessage(note, Delivery.SENDING, inputs).transcribing)
        val image = item(attachment("a1"))
        assertEquals(false, outboxMessage(image, Delivery.SENDING, inputs.copy(transcripts = true)).transcribing)
    }

    @Test
    fun `the viewer pages through every image the list holds, oldest first, each by its cell's key`() {
        fun message(
            key: String,
            vararg shapes: MediaShape,
        ) = ChatItem.Message(
            key,
            ShownMessage(
                Sender.User,
                "",
                null,
                Delivery.DELIVERED,
                media = shapes.mapIndexed { index, shape -> ShownMedia("$key-$index", null, shape, "x/y", 1L, null) },
            ),
        )
        val newestFirst =
            listOf(
                message("row:3", MediaShape.IMAGE),
                message("row:2", MediaShape.DOCUMENT, MediaShape.IMAGE, MediaShape.IMAGE),
                ChatItem.Pill("pill", PillText.Stopped),
                message("row:1", MediaShape.VOICE),
            )
        val viewed = viewerImages(newestFirst)
        assertEquals(listOf("row:2/0", "row:2/1", "row:3/0"), viewed.map { it.key })
        assertEquals(listOf("row:2-1", "row:2-2", "row:3-0"), viewed.map { it.media.ref })
        assertEquals(listOf("row:2", "row:2", "row:3"), viewed.map { it.messageKey })
    }

    @Test
    fun `Save puts a blob in Pictures only when it is drawn as an image and its type is an image's`() {
        val rows =
            listOf(
                MediaShape.IMAGE to "image/png" to true,
                MediaShape.IMAGE to "image/jpeg" to true,
                MediaShape.IMAGE to "application/pdf" to false,
                MediaShape.IMAGE to "text/html" to false,
                MediaShape.IMAGE to "image" to false,
                MediaShape.IMAGE to "" to false,
                MediaShape.IMAGE to "IMAGE/PNG" to false,
                MediaShape.DOCUMENT to "image/png" to false,
                MediaShape.DOCUMENT to "application/pdf" to false,
                MediaShape.VOICE to "audio/ogg" to false,
            )
        val saved = rows.map { (row, _) -> savesIntoPictures(ShownMedia("r", null, row.first, row.second, 1L, "x")) }
        assertEquals(rows.map { it.second }, saved)
    }

    @Test
    fun `a type from the wire or another app reaches an intent or the media store bounded, or as bytes`() {
        val part = "a".repeat(127)
        val huge = "text/${"x".repeat(1_048_576)}"
        val rows =
            listOf(
                "application/pdf" to "application/pdf",
                "IMAGE/PNG" to "image/png",
                " text/plain ; charset=utf-8" to "text/plain",
                "application/vnd.ms-excel" to "application/vnd.ms-excel",
                "image/svg+xml" to "image/svg+xml",
                "$part/$part" to "$part/$part",
                "$part/${part}a" to UNKNOWN_MIME,
                "${part}a/$part" to UNKNOWN_MIME,
                huge to UNKNOWN_MIME,
                "text/plain; x=${"y".repeat(1_048_576)}" to "text/plain",
                "" to UNKNOWN_MIME,
                "image" to UNKNOWN_MIME,
                "image/" to UNKNOWN_MIME,
                "/png" to UNKNOWN_MIME,
                "image/png/x" to UNKNOWN_MIME,
                "image/p ng" to UNKNOWN_MIME,
                "image/png\u0000" to UNKNOWN_MIME,
                "image/pñg" to UNKNOWN_MIME,
                "-image/png" to UNKNOWN_MIME,
            )
        assertEquals(rows.map { it.second }, rows.map { mediaTypeOf(it.first) })
        val refs =
            listOf(
                MediaRef("r1", "file", huge, 10, filename = "a.txt"),
                MediaRef("r2", "image", "Image/PNG", 10),
            )
        assertEquals(listOf(UNKNOWN_MIME, "image/png"), rowMedia(refs, user = false).map { it.mime })
        val outbox = item(attachment("a1").copy(mime = huge))
        assertEquals(listOf(UNKNOWN_MIME), outboxMedia(outbox, emptyMap()).map { it.mime })
    }
}
