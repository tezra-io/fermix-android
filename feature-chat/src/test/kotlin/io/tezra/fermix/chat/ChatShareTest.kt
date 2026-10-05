package io.tezra.fermix.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.MAX_ATTACHMENTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI

private fun shared(
    uris: List<String> = emptyList(),
    words: String? = null,
) = Shared(uris, words, refused = emptyList(), past = 0)

/** The sample Fermix with the daemon's limit at [maxBytes]. */
private fun limitedTo(maxBytes: Long) = sample().let { it.copy(caps = it.caps?.copy(maxMediaBytes = maxBytes)) }

/**
 * Another app's share landing in a chat over a fake session (design section 13.6, "Share into Fermix"): its items in
 * the tray as the chat's own copies, made as they land, up to the ten with what the tray held; its words at the end of
 * the draft; nothing sent until the owner sends; and the copies kept through a process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatShareTest {
    private val main = StandardTestDispatcher()

    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    private inner class Rig(
        scope: TestScope,
        val saved: SavedStateHandle = SavedStateHandle(),
        draft: String? = null,
    ) {
        val store = FakeChatStore(draft = draft)
        val session = FakeChatSession(store)
        val pipeline = FakePipeline()
        val log = FakeLog()
        val records = MutableStateFlow(listOf(sample()))
        private val parts =
            fakeParts(sample(), session, store, scope.backgroundScope).copy(
                records = records,
                media = pipeline,
                files = FakeChatFiles(store),
                log = log.log,
                scratch = { File.createTempFile("scratch", null, dir) },
                io = main,
            )
        val viewModels = ViewModelStore()
        val model: ChatViewModel =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { ChatViewModel(parts, saved) } })
                .get(ChatViewModel::class)
        val ui: AttachUi get() = model.attach.ui.value
    }

    @Test
    fun `a shared item lands in the tray as the chat's own copy, made as it lands, and nothing is sent`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.bytes = mapOf("content://media/1" to byteArrayOf(7, 8))
            runCurrent()
            rig.model.share(shared(listOf("content://media/1")))
            runCurrent()
            val item = rig.ui.picked.single()
            assertEquals(PickedFrom.SHARE, item.from)
            assertTrue(item.uri.startsWith("file:"), item.uri)
            assertArrayEquals(byteArrayOf(7, 8), File(URI(item.uri)).readBytes())
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertTrue(
                rig.store.outbox.value
                    .isEmpty(),
            )
        }

    @Test
    fun `a share's words land at the end of the draft as words, and nothing is sent, run or stopped`() =
        runTest(main) {
            val rig = Rig(this, draft = "my draft")
            runCurrent()
            val words = "/stop fermix://chat/x intent://scan/#Intent;end https://example.com"
            rig.model.share(shared(words = words))
            runCurrent()
            assertEquals("my draft\n$words", rig.model.composer.field.value.text)
            assertEquals("my draft\n$words", rig.store.state.value.draft)
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertEquals(emptyList<String>(), rig.session.stopped.value)
            assertTrue(
                rig.store.outbox.value
                    .isEmpty(),
            )
        }

    @Test
    fun `words that come before the stored draft is back wait for it, so the draft is never lost`() =
        runTest(main) {
            val rig = Rig(this, draft = "kept")
            // The share comes before the composer has read the draft back.
            rig.model.share(shared(words = "shared"))
            runCurrent()
            assertEquals("kept\nshared", rig.model.composer.field.value.text)
        }

    @Test
    fun `a share past the ten, with what the tray held, copies and lands only what fits and logs the rest`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add((1..8).map { "content://photos/$it.png" }, PickedFrom.PHOTOS)
            runCurrent()
            rig.model.share(shared((1..MAX_ATTACHMENTS).map { "content://media/$it" }))
            runCurrent()
            assertEquals(MAX_ATTACHMENTS, rig.ui.picked.size)
            assertEquals(2, rig.ui.picked.count { it.from == PickedFrom.SHARE })
            assertTrue(
                "8 picked past the ten a send takes were left out" in rig.log.lines.value,
                "${rig.log.lines.value}",
            )
            // The eight with no room were never copied: no file was written for them.
            val copied = rig.pipeline.copied.value
            assertEquals(listOf("content://media/1", "content://media/2"), copied.map { it.first })
            assertEquals(2, dir.listFiles().orEmpty().size)
        }

    @Test
    fun `a share while a send is in flight joins the tray, and the send takes only what it chose`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://photos/1.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.model.attach.send {}
            rig.model.share(shared(listOf("content://media/2")))
            runCurrent()
            val sent =
                rig.store.outbox.value
                    .single()
            assertEquals(1, sent.attachments.size)
            assertEquals(
                PickedFrom.SHARE,
                rig.ui.picked
                    .single()
                    .from,
            )
        }

    @Test
    fun `a shared item its provider says is past the daemon's limit is never copied, and its line says so`() =
        runTest(main) {
            val rig = Rig(this)
            rig.records.value = listOf(limitedTo(1_000L))
            rig.pipeline.describe = { uri, from ->
                val size = if (uri.endsWith("9")) 5_000L else 500L
                Picked(uri, uri, PickedKind.VIDEO, "video/mp4", uri.substringAfterLast('/') + ".mp4", size, from)
            }
            rig.pipeline.bytes = mapOf("content://media/9" to ByteArray(5_000), "content://media/1" to ByteArray(500))
            runCurrent()
            rig.model.share(shared(listOf("content://media/9", "content://media/1")))
            runCurrent()
            assertEquals(TooBig("9.mp4", 5_000L, 1_000L), rig.ui.tooBig)
            val copied = rig.pipeline.copied.value
            assertEquals(listOf("content://media/1"), copied.map { it.first })
            assertEquals(1, dir.listFiles().orEmpty().size)
            val landed = rig.ui.picked.single()
            assertEquals("1.mp4" to 500L, landed.name to landed.sizeBytes)
            // What landed goes; the item past the limit was never in the tray to go.
            rig.model.attach.send {}
            runCurrent()
            val sent =
                rig.store.outbox.value
                    .single()
            assertEquals(listOf(500L), sent.attachments.map { it.sizeBytes })
        }

    @Test
    fun `a provider that streams past the limit it understated is stopped a byte past it, and nothing is kept`() =
        runTest(main) {
            val rig = Rig(this)
            rig.records.value = listOf(limitedTo(1_000L))
            rig.pipeline.describe =
                { uri, from -> Picked(uri, uri, PickedKind.VIDEO, "video/mp4", "clip.mp4", 0L, from) }
            rig.pipeline.bytes = mapOf("content://media/9" to ByteArray(500_000))
            runCurrent()
            rig.model.share(shared(listOf("content://media/9")))
            runCurrent()
            assertEquals(listOf("content://media/9" to 1_001L), rig.pipeline.copied.value)
            assertEquals(emptyList<Picked>(), rig.ui.picked)
            assertEquals(TooBig("clip.mp4", 1_001L, 1_000L), rig.ui.tooBig)
            assertEquals(emptyList<File>(), dir.listFiles().orEmpty().toList(), "the part-copy was left")
        }

    @Test
    fun `an image past the daemon's limit lands whole and goes as the smaller JPEG made of it, as a picked one does`() =
        runTest(main) {
            val rig = Rig(this)
            rig.records.value = listOf(limitedTo(1_000L))
            rig.pipeline.describe =
                { uri, from -> Picked(uri, uri, PickedKind.IMAGE, "image/png", "big.png", 5_000L, from) }
            rig.pipeline.bytes = mapOf("content://media/9" to ByteArray(5_000))
            rig.pipeline.jpeg = { it.copyOf(100) }
            runCurrent()
            rig.model.share(shared(listOf("content://media/9")))
            runCurrent()
            assertEquals(listOf("content://media/9" to 5_000L), rig.pipeline.copied.value)
            assertEquals(
                5_000L,
                rig.ui.picked
                    .single()
                    .sizeBytes,
            )
            assertEquals(null, rig.ui.tooBig)
            // As a file it would go as its own bytes, past the limit, and its line says so, as a picked image's does.
            rig.model.attach.sendAsFiles(true)
            runCurrent()
            assertEquals(TooBig("big.png", 5_000L, 1_000L), rig.ui.tooBig)
            rig.model.attach.sendAsFiles(false)
            rig.model.attach.send {}
            runCurrent()
            val sent =
                rig.store.outbox.value
                    .single()
                    .attachments
                    .single()
            assertEquals(Triple(AttachKind.IMAGE, "image/jpeg", 100L), Triple(sent.kind, sent.mime, sent.sizeBytes))
        }

    @Test
    fun `an image past the app's own bound for an image's copy is never copied, and its line names that bound`() =
        runTest(main) {
            val rig = Rig(this)
            rig.records.value = listOf(limitedTo(1_000L))
            val size = LANDING_MAX_BYTES + 1
            rig.pipeline.describe =
                { uri, from -> Picked(uri, uri, PickedKind.IMAGE, "image/png", "huge.png", size, from) }
            runCurrent()
            rig.model.share(shared(listOf("content://media/9")))
            runCurrent()
            assertEquals(emptyList<Pair<String, Long>>(), rig.pipeline.copied.value)
            assertEquals(emptyList<Picked>(), rig.ui.picked)
            assertEquals(TooBig("huge.png", size, LANDING_MAX_BYTES), rig.ui.tooBig)
        }

    @Test
    fun `a share that lands before the chat's record is read waits for the daemon's limit, and is held to it`() =
        runTest(main) {
            val rig = Rig(this)
            // The record as the app's DataStore reads it: not there yet as the chat's model is made for the share.
            rig.records.value = emptyList()
            rig.pipeline.describe =
                { uri, from -> Picked(uri, uri, PickedKind.VIDEO, "video/mp4", "clip.mp4", 0L, from) }
            rig.pipeline.bytes = mapOf("content://media/9" to ByteArray(500_000))
            rig.model.share(shared(listOf("content://media/9")))
            runCurrent()
            assertEquals(emptyList<Pair<String, Long>>(), rig.pipeline.copied.value)
            rig.records.value = listOf(limitedTo(1_000L))
            runCurrent()
            assertEquals(listOf("content://media/9" to 1_001L), rig.pipeline.copied.value)
            assertEquals(emptyList<Picked>(), rig.ui.picked)
            assertEquals(TooBig("clip.mp4", 1_001L, 1_000L), rig.ui.tooBig)
        }

    @Test
    fun `a share whose chat's record never comes is not copied once the wait for it ends, and the log says so`() =
        runTest(main) {
            val rig = Rig(this)
            rig.records.value = emptyList()
            rig.model.share(shared(listOf("content://media/9")))
            runCurrent()
            advanceTimeBy(READ_WAIT_MILLIS + 1)
            runCurrent()
            assertEquals(emptyList<Pair<String, Long>>(), rig.pipeline.copied.value)
            assertEquals(emptyList<Picked>(), rig.ui.picked)
            assertTrue(
                "The chat's record was not read in time: 1 pasted, typed-in or shared items were not copied" in
                    rig.log.lines.value,
                "${rig.log.lines.value}",
            )
        }

    @Test
    fun `an item refused or whose grant is gone is logged by the exception's class alone, never by the URI it names`() =
        runTest(main) {
            val rig = Rig(this)
            val described = "content://downloads/all_downloads/secret-described"
            val copied = "content://media/external/images/media/secret-copied"
            rig.pipeline.describe = { uri, from ->
                if (uri == described) throw SecurityException("Permission Denial: reading uri $uri requires a grant")
                Picked(uri, uri, PickedKind.IMAGE, "image/png", "ok.png", 10L, from)
            }
            rig.pipeline.refused = setOf(copied)
            runCurrent()
            rig.model.share(shared(listOf(described, copied)))
            runCurrent()
            assertEquals(emptyList<Picked>(), rig.ui.picked)
            val lines = rig.log.lines.value
            assertTrue("A picked item was refused, or its grant is gone: SecurityException" in lines, "$lines")
            assertTrue(
                "A pasted, typed-in or shared item was refused, or its grant is gone: SecurityException" in lines,
                "$lines",
            )
            assertTrue(lines.none { "secret" in it }, "$lines")
            assertEquals(emptyList<Throwable>(), rig.log.thrown.value)
        }

    @Test
    fun `shared words none of which fit the draft's message leave the draft as it was, and the log says so`() =
        runTest(main) {
            val full = "a".repeat(MAX_SHARED_CHARS)
            val rig = Rig(this, draft = full)
            runCurrent()
            rig.model.share(shared(words = "more"))
            runCurrent()
            assertEquals(full, rig.model.composer.field.value.text)
            assertTrue(
                "None of a share's words fit what one message carries; the draft is as it was" in rig.log.lines.value,
                "${rig.log.lines.value}",
            )
        }

    @Test
    fun `a provider that fails as its item is described or copied leaves the item out, and the chat goes on`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.describe = { uri, from ->
                // As the binder carries a provider's own fault back: an IllegalArgumentException.
                require(!uri.endsWith("bad")) { "Invalid column: _display_name" }
                Picked(uri, uri, PickedKind.IMAGE, "image/png", "ok.png", 10L, from)
            }
            rig.pipeline.faulty = setOf("content://media/faulty")
            runCurrent()
            rig.model.share(shared(listOf("content://settings/bad", "content://media/faulty")))
            runCurrent()
            assertEquals(emptyList<Picked>(), rig.ui.picked)
            assertEquals(emptyList<File>(), dir.listFiles().orEmpty().toList(), "a part-copy was left")
            val lines = rig.log.lines.value
            assertTrue("A picked item's provider failed: IllegalArgumentException" in lines, "$lines")
            assertTrue("A pasted, typed-in or shared item's provider failed: IllegalStateException" in lines, "$lines")
            // The chat lives on: the next share lands.
            rig.model.share(shared(listOf("content://media/1")))
            runCurrent()
            val landed = rig.ui.picked.single()
            assertEquals(PickedFrom.SHARE, landed.from)
        }

    @Test
    fun `a process death keeps the tray's copies, and leaves out one whose file is gone`() =
        runTest(main) {
            val before = Rig(this)
            runCurrent()
            before.model.share(shared(listOf("content://media/1", "content://media/2")))
            before.model.attach.add(listOf("content://photos/3.png"), PickedFrom.PHOTOS)
            runCurrent()
            assertEquals(3, before.ui.picked.size)
            val kept = before.saved.get<String>(KEPT_TRAY)
            // The process dies: no ViewModel is cleared, and the saved state is all that is left of the chat.
            val copies = before.ui.picked.filter { it.from == PickedFrom.SHARE }
            File(URI(copies[1].uri)).delete()
            val after = Rig(this, SavedStateHandle(mapOf(KEPT_TRAY to kept)))
            runCurrent()
            // The Photo Picker's grant ended with the process: only the chat's own file comes back.
            assertEquals(listOf(copies[0]), after.ui.picked)
            assertTrue("1 kept tray files were gone" in after.log.lines.value, "${after.log.lines.value}")
        }

    @Test
    fun `a closed chat deletes a share's copies with the tray`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.share(shared(listOf("content://media/1")))
            runCurrent()
            val copy =
                File(
                    URI(
                        rig.ui.picked
                            .single()
                            .uri,
                    ),
                )
            assertTrue(copy.isFile)
            rig.viewModels.clear()
            runCurrent()
            assertTrue(!copy.exists(), "a share's copy outlived its chat")
        }
}
