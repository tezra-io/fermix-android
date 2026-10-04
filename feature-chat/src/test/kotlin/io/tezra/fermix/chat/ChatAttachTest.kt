package io.tezra.fermix.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

/**
 * The attach sheet and the tray over a fake session (design sections 8.5, 13.6 and 13.9): picked items land in
 * the tray in order; Paste takes the clipboard's; one past the daemon's limit says so and stays; Send makes each
 * ready, stages it and sends one `msg` with the composer's words as its caption, an image as a JPEG kept in the
 * media cache, "Send as files" as documents; an item that cannot be read sends nothing and leaves no file; Edit
 * returns an outbox item's attachments to the tray.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatAttachTest {
    private val main = StandardTestDispatcher()

    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    private inner class Rig(
        scope: TestScope,
    ) {
        val store = FakeChatStore()
        val session = FakeChatSession(store)
        val pipeline = FakePipeline()
        val clip = FakeClip()
        val files = FakeChatFiles(store)
        val log = FakeLog()
        val scratches = mutableListOf<File>()
        val records = MutableStateFlow(listOf(sample()))
        private val parts =
            fakeParts(sample(), session, store, scope.backgroundScope).copy(
                records = records,
                media = pipeline,
                clip = clip,
                files = files,
                log = log.log,
                scratch = { File.createTempFile("scratch", null, dir).also { scratches += it } },
                io = main,
            )
        val viewModels = ViewModelStore()
        val model: ChatViewModel =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { ChatViewModel(parts, SavedStateHandle()) } })
                .get(ChatViewModel::class)
        val ui: AttachUi get() = model.attach.ui.value
    }

    @Test
    fun `photos, files and a paste land in the tray in order, at most ten, each once`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://photos/1.png", "content://photos/2.png"), PickedFrom.PHOTOS)
            rig.model.attach.add(listOf("content://docs/report.pdf", "content://photos/1.png"), PickedFrom.FILES)
            rig.clip.clip = "content://clip/3.png"
            rig.model.attach.paste()
            runCurrent()
            assertEquals(
                listOf(
                    "content://photos/1.png",
                    "content://photos/2.png",
                    "content://docs/report.pdf",
                ),
                rig.ui.picked
                    .take(3)
                    .map { it.uri },
            )
            assertEquals("3.png", rig.ui.picked[3].name)
            assertEquals(
                listOf(
                    PickedFrom.PHOTOS,
                    PickedFrom.PHOTOS,
                    PickedFrom.FILES,
                    PickedFrom.PASTE,
                ),
                rig.ui.picked.map {
                    it.from
                },
            )
            rig.model.attach.add((10..30).map { "content://photos/$it.png" }, PickedFrom.PHOTOS)
            runCurrent()
            assertEquals(10, rig.ui.picked.size)
        }

    @Test
    fun `a pasted item is copied as it lands, so Send reads no grant, and the copy goes once it was sent`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.bytes = mapOf("content://clip/3.png" to byteArrayOf(4, 5, 6))
            runCurrent()
            rig.clip.clip = "content://clip/3.png"
            rig.model.attach.paste()
            runCurrent()
            val pasted = rig.ui.picked.single()
            assertEquals(PickedFrom.PASTE, pasted.from)
            assertEquals("3.png" to "image/png", pasted.name to pasted.mime)
            val copy = File(java.net.URI(pasted.uri))
            assertArrayEquals(byteArrayOf(4, 5, 6), copy.readBytes())
            assertEquals(3L, pasted.sizeBytes)
            // The clip changed: the grant that read it is gone.
            rig.pipeline.refused = setOf("content://clip/3.png")
            rig.model.attach.send {}
            runCurrent()
            val attachment =
                rig.store.outbox.value
                    .single()
                    .attachments
                    .single()
            assertEquals(sha(byteArrayOf(4, 5, 6)), attachment.sha256)
            assertFalse(copy.exists(), "the pasted copy outlived its send")
        }

    @Test
    fun `the keyboard's items are copied while its grant is held, and each past the ten is let go`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add((1..9).map { "content://photos/$it.png" }, PickedFrom.PHOTOS)
            runCurrent()
            rig.model.attach.add(listOf("content://ime/a.png", "content://ime/b.png"), PickedFrom.KEYBOARD, Any())
            runCurrent()
            assertEquals(10, rig.ui.picked.size)
            val typed = rig.ui.picked.last()
            assertEquals(PickedFrom.KEYBOARD to "a.png", typed.from to typed.name)
            assertTrue(File(java.net.URI(typed.uri)).isFile)
            val left = rig.scratches.filter { it.exists() }
            assertEquals(listOf(File(java.net.URI(typed.uri))), left, "the copy past the ten was left")
        }

    @Test
    fun `an item whose read grant is gone at Send stays in the tray, sends nothing and leaves no file`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://files/1.pdf", "content://files/2.pdf"), PickedFrom.FILES)
            runCurrent()
            rig.pipeline.refused = setOf("content://files/2.pdf")
            rig.model.attach.send {}
            runCurrent()
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
            assertEquals(2, rig.ui.picked.size)
            assertTrue(rig.scratches.none { it.exists() }, "a scratch file was left")
            assertTrue("An attachment was refused, or its grant is gone" in rig.log.lines.value)
        }

    @Test
    fun `a send its session does not take lets its staged files go, and the tray keeps them`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://p/1.png", "content://p/2.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.session.takes = false
            rig.model.attach.send { error("nothing was taken") }
            runCurrent()
            assertEquals(2, rig.files.staged.value.size)
            assertEquals(rig.files.staged.value, rig.files.released.value)
            assertTrue(
                rig.files.staged.value
                    .none { File(it).exists() },
                "a staged file was left",
            )
            assertEquals(2, rig.ui.picked.size)
        }

    @Test
    fun `a stage that stops part-way lets go of those staged before it, and leaves no scratch file`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://p/1.png", "content://p/2.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.files.stagesLeft = 1
            rig.model.attach.send {}
            runCurrent()
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
            assertEquals(rig.files.staged.value, rig.files.released.value)
            assertEquals(1, rig.files.released.value.size)
            assertTrue(rig.scratches.none { it.exists() }, "a scratch file was left")
            assertEquals(2, rig.ui.picked.size)
        }

    @Test
    fun `a stage that fails on a full disk is logged, lets go of what was staged and leaves no scratch file`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://p/1.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.files.failing = true
            rig.model.attach.send {}
            runCurrent()
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
            assertTrue(rig.scratches.none { it.exists() }, "a scratch file was left")
            assertTrue("An attachment could not be made ready or staged" in rig.log.lines.value)
            assertEquals(1, rig.ui.picked.size)
        }

    @Test
    fun `the embedded picker's un-pick takes its own items out and leaves the rest`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://photos/1.png", "content://photos/2.png"), PickedFrom.PHOTOS)
            rig.model.attach.add(listOf("content://docs/1.png"), PickedFrom.FILES)
            runCurrent()
            rig.model.attach.unpick(listOf("content://photos/1.png", "content://docs/1.png"))
            runCurrent()
            assertEquals(listOf("content://photos/2.png", "content://docs/1.png"), rig.ui.picked.map { it.uri })
        }

    @Test
    fun `the sheet's caption is the field's words, written into the composer's field once`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            val before = rig.model.composer.written.value.revision
            val caption = TextFieldValue("The chart I mean.", TextRange(3))
            rig.model.composer.caption(caption)
            assertEquals(caption, rig.model.composer.field.value)
            assertEquals(FieldWrite(before + 1, caption), rig.model.composer.written.value)
            rig.model.composer.edit(TextFieldValue("typed"))
            assertEquals(FieldWrite(before + 1, caption), rig.model.composer.written.value, "typing is no write")
        }

    @Test
    fun `a paste the clipboard hands nothing adds nothing and the log says so`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.paste()
            runCurrent()
            assertTrue(rig.ui.picked.isEmpty())
            assertEquals(listOf("Paste took nothing from the clipboard"), rig.log.lines.value)
        }

    @Test
    fun `a video past the limit shows its line and stays, and Send takes the rest`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.describe = { uri, from ->
                val video = uri.endsWith(".mov")
                val kind = if (video) PickedKind.VIDEO else PickedKind.IMAGE
                val size = if (video) 34_000_000L else 1_000L
                Picked(
                    uri,
                    uri,
                    kind,
                    if (video) "video/quicktime" else "image/png",
                    uri.substringAfterLast('/'),
                    size,
                    from,
                )
            }
            runCurrent()
            rig.model.attach.add(listOf("content://v/video.mov", "content://p/cat.png"), PickedFrom.PHOTOS)
            runCurrent()
            assertEquals(TooBig("video.mov", 34_000_000L, 20_000_000L), rig.ui.tooBig)
            assertEquals(1, rig.ui.sendable)
            rig.model.attach.send {}
            runCurrent()
            val msg =
                rig.session.sent.value
                    .single() as ClientEvent.Msg
            assertEquals(1, msg.attachIds.size)
            assertEquals(listOf("content://v/video.mov"), rig.ui.picked.map { it.uri })
        }

    @Test
    fun `Send stages each item and sends one msg captioned with the field, an image as a cached JPEG`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.bytes = mapOf("content://p/cat.png" to byteArrayOf(1, 2, 3))
            runCurrent()
            rig.model.composer.edit(TextFieldValue("  the cat  "))
            rig.model.attach.add(listOf("content://p/cat.png"), PickedFrom.PHOTOS)
            rig.model.attach.open()
            runCurrent()
            var taken = false
            rig.model.attach.send { taken = true }
            runCurrent()
            assertTrue(taken)
            val msg =
                rig.session.sent.value
                    .single() as ClientEvent.Msg
            assertEquals("the cat", msg.text)
            val item =
                rig.store.outbox.value
                    .single()
            val attachment = item.attachments.single()
            assertEquals(msg.attachIds, listOf(attachment.attachId))
            assertEquals(AttachKind.IMAGE, attachment.kind)
            assertEquals("image/jpeg", attachment.mime)
            assertEquals("cat.jpg", attachment.name)
            assertEquals(sha(byteArrayOf(1, 2, 3)), attachment.sha256)
            assertEquals(listOf(attachment.source), rig.files.staged.value)
            assertArrayEquals(byteArrayOf(1, 2, 3), rig.store.media.value[attachment.sha256])
            assertEquals(listOf("content://p/cat.png" to false), rig.pipeline.prepared.value)
            assertEquals("", rig.model.composer.field.value.text)
            assertTrue(rig.ui.picked.isEmpty())
            assertFalse(rig.ui.sheet)
        }

    @Test
    fun `Send as files sends an image's own bytes as a document, kept out of the media cache`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://p/cat.heic"), PickedFrom.PHOTOS)
            rig.model.attach.sendAsFiles(true)
            runCurrent()
            rig.model.attach.send {}
            runCurrent()
            val attachment =
                rig.store.outbox.value
                    .single()
                    .attachments
                    .single()
            assertEquals(AttachKind.DOCUMENT, attachment.kind)
            assertEquals("cat.heic", attachment.name)
            assertEquals(listOf("content://p/cat.heic" to true), rig.pipeline.prepared.value)
            assertTrue(
                rig.store.media.value
                    .isEmpty(),
            )
        }

    @Test
    fun `an item that cannot be read sends nothing, stays in the tray and leaves no scratch file`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.unreadable = setOf("content://p/2.png")
            runCurrent()
            rig.model.attach.add(listOf("content://p/1.png", "content://p/2.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.model.attach.send {}
            runCurrent()
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
            assertEquals(2, rig.ui.picked.size)
            assertEquals(2, rig.scratches.size)
            assertTrue(rig.scratches.none { it.exists() }, "a scratch file was left")
            assertTrue(
                rig.files.staged.value
                    .isEmpty(),
            )
        }

    @Test
    fun `an image made past a small limit stops the send with its line`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.bytes = mapOf("content://p/big.png" to ByteArray(2_000))
            rig.pipeline.describe =
                { uri, from -> Picked(uri, uri, PickedKind.IMAGE, "image/png", "big.png", 900L, from) }
            rig.records.value = listOf(sample().let { it.copy(caps = it.caps?.copy(maxMediaBytes = 1_000L)) })
            runCurrent()
            rig.model.attach.add(listOf("content://p/big.png"), PickedFrom.PHOTOS)
            runCurrent()
            assertNull(rig.ui.tooBig)
            rig.model.attach.send {}
            runCurrent()
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
            assertEquals(TooBig("big.png", 2_000L, 1_000L), rig.ui.tooBig)
        }

    @Test
    fun `the tray's cross takes an item out`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://p/1.png", "content://p/2.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.model.attach.remove(
                rig.ui.picked
                    .first()
                    .id,
            )
            runCurrent()
            assertEquals(listOf("content://p/2.png"), rig.ui.picked.map { it.uri })
        }

    @Test
    fun `Edit on an outbox item with attachments returns its words to the field and its files to the tray`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.bytes = mapOf("content://p/cat.png" to byteArrayOf(9, 9))
            runCurrent()
            rig.model.composer.edit(TextFieldValue("the cat"))
            rig.model.attach.add(listOf("content://p/cat.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.model.attach.send {}
            runCurrent()
            val message =
                checkNotNull(
                    rig.model.state.value,
                ).items.filterIsInstance<ChatItem.Message>().single().message
            assertEquals(1, message.media.size)
            rig.model.edit(message)
            runCurrent()
            assertTrue(
                rig.store.outbox.value
                    .isEmpty(),
            )
            assertEquals("the cat", rig.model.composer.field.value.text)
            val back = rig.ui.picked.single()
            assertEquals(PickedFrom.OUTBOX, back.from)
            assertEquals("cat.jpg", back.name)
            assertArrayEquals(byteArrayOf(9, 9), File(java.net.URI(back.uri)).readBytes())
        }

    @Test
    fun `a closed chat deletes the files it made for the tray, a camera's capture, a paste and Edit's copy`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.bytes = mapOf("content://p/cat.png" to byteArrayOf(9, 9))
            val capture = File(dir, "camera.jpg").apply { writeBytes(byteArrayOf(1)) }
            rig.pipeline.describe = { uri, from ->
                Picked(uri, uri, PickedKind.IMAGE, "image/jpeg", uri.substringAfterLast('/'), 1L, from)
            }
            runCurrent()
            rig.model.attach.add(listOf("content://p/cat.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.model.attach.send {}
            runCurrent()
            val message =
                checkNotNull(rig.model.state.value)
                    .items
                    .filterIsInstance<ChatItem.Message>()
                    .single()
                    .message
            rig.model.edit(message)
            rig.model.attach.add(listOf(capture.toURI().toString()), PickedFrom.CAMERA)
            rig.clip.clip = "content://clip/3.png"
            rig.model.attach.paste()
            runCurrent()
            val made = rig.ui.picked.map { File(java.net.URI(it.uri)) }
            assertEquals(3, made.size)
            assertTrue(made.all(File::exists))
            rig.viewModels.clear()
            runCurrent()
            assertTrue(made.none(File::exists), "a file the tray held was left: $made")
        }

    @Test
    fun `an Edit whose attachment cannot be copied back leaves no copy, and the item stays`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.bytes = mapOf("content://p/cat.png" to byteArrayOf(9, 9))
            runCurrent()
            rig.model.attach.add(listOf("content://p/cat.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.model.attach.send {}
            runCurrent()
            val item =
                rig.store.outbox.value
                    .single()
            File(item.attachments.single().source).delete()
            val message =
                checkNotNull(rig.model.state.value)
                    .items
                    .filterIsInstance<ChatItem.Message>()
                    .single()
                    .message
            rig.model.edit(message)
            runCurrent()
            assertEquals(listOf(item), rig.store.outbox.value)
            assertTrue(rig.ui.picked.isEmpty())
            assertTrue(rig.scratches.none(File::exists), "a copy was left: ${rig.scratches}")
            assertTrue("Edit could not copy an attachment back; the item stays in the outbox" in rig.log.lines.value)
        }
}
