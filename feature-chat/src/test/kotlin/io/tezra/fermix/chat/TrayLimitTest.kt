package io.tezra.fermix.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.data.Instance
import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val MIB = 1_024L * 1_024

/** The sample Fermix as its record stands before the daemon's first `hello_ack`: no caps, so no daemon's limit. */
private fun noCaps(): Instance = sample().copy(caps = null)

/** The sample Fermix whose daemon states [maxBytes] as its `caps.max_media_bytes`. */
private fun limitedTo(maxBytes: Long): Instance =
    sample().let { it.copy(caps = it.caps?.copy(maxMediaBytes = maxBytes)) }

/** A file its provider describes at [uri], picked from [from], of [size] as it says: 0 when it says none. */
private fun describedAs(
    uri: String,
    from: PickedFrom,
    size: Long,
): Picked = Picked(uri, uri, PickedKind.FILE, "application/octet-stream", uri.substringAfterLast('/'), size, from)

/** A video its provider describes at [uri], picked from [from], of [size] as it says. */
private fun videoAs(
    uri: String,
    from: PickedFrom,
    size: Long,
): Picked = Picked(uri, uri, PickedKind.VIDEO, "video/quicktime", uri.substringAfterLast('/'), size, from)

/**
 * The tray, Send and the too-big line as the daemon's limit comes and changes, over a fake session (design sections
 * 8.5 and 13.9): before the daemon states a limit, the tray and Send hold an item to the app's own [LANDING_MAX_BYTES],
 * one bound for both, and a limit it states holds them as it is, above that or below, the tray following it as it
 * comes; a line Send gave names the limit it was made under and shows only while that is the bound, a landing's line
 * stays whatever the limit does, and no line outlives a send the session took, while one that lands as it runs stays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrayLimitTest {
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
        val scratches = mutableListOf<File>()
        val records = MutableStateFlow(listOf(sample()))

        /** Holds Send's making of each item until a test completes it; none is held while it is null. */
        var preparing: CompletableDeferred<Unit>? = null
        private val media =
            object : MediaPipeline by pipeline {
                override suspend fun prepare(
                    picked: Picked,
                    asFile: Boolean,
                    into: File,
                    maxBytes: Long,
                ): Prepared {
                    preparing?.await()
                    return pipeline.prepare(picked, asFile, into, maxBytes)
                }
            }
        private val parts =
            fakeParts(sample(), session, store, scope.backgroundScope).copy(
                records = records,
                media = media,
                clip = clip,
                files = files,
                scratch = { File.createTempFile("scratch", null, dir).also { scratches += it } },
                io = main,
            )
        val viewModels = ViewModelStore()
        val model: ChatViewModel =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { ChatViewModel(parts, SavedStateHandle()) } })
                .get(ChatViewModel::class)
        val ui: AttachUi get() = model.attach.ui.value

        /** The tray's too-big line and how many items "Send {n}" counts, as the sheet shows them together. */
        val shown: Pair<TooBig?, Int> get() = ui.tooBig to ui.sendable

        /** Deletes what a send staged, which the fake keeps in a directory of its own, outside the test's. */
        fun dropStaged() = files.staged.value.forEach { File(it).delete() }
    }

    @Test
    fun `before the daemon states a limit, a file sized at nothing that streams past the app's bound stops the send`() =
        runTest(main) {
            val rig = Rig(this)
            val uri = "content://docs/archive.zip"
            val past = Zeros(LANDING_MAX_BYTES + 1)
            rig.pipeline.streams = mapOf(uri to { past })
            rig.pipeline.describe = { picked, from -> describedAs(picked, from, size = 0L) }
            rig.records.value = listOf(noCaps())
            runCurrent()
            rig.model.attach.add(listOf(uri), PickedFrom.FILES)
            runCurrent()
            assertEquals(null to 1, rig.shown)
            try {
                rig.model.attach.send {}
                runCurrent()
                assertEquals(TooBig("archive.zip", LANDING_MAX_BYTES + 1, LANDING_MAX_BYTES), rig.ui.tooBig)
                assertEquals(LANDING_MAX_BYTES + 1, past.handed)
                assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
                assertEquals(emptyList<String>(), rig.files.staged.value)
                assertEquals(listOf(uri), rig.ui.picked.map { it.uri })
                assertEquals(1, rig.scratches.size)
                assertTrue(rig.scratches.none { it.exists() }, "a scratch file was left")
            } finally {
                rig.dropStaged()
            }
        }

    @Test
    fun `before the daemon states a limit, a file sized at nothing that streams the app's bound exactly goes whole`() =
        runTest(main) {
            val rig = Rig(this)
            val uri = "content://docs/archive.zip"
            rig.pipeline.streams = mapOf(uri to { Zeros(LANDING_MAX_BYTES) })
            rig.pipeline.describe = { picked, from -> describedAs(picked, from, size = 0L) }
            rig.records.value = listOf(noCaps())
            runCurrent()
            rig.model.attach.add(listOf(uri), PickedFrom.FILES)
            runCurrent()
            try {
                rig.model.attach.send {}
                runCurrent()
                val msg =
                    rig.session.sent.value
                        .single() as ClientEvent.Msg
                val attachment =
                    rig.store.outbox.value
                        .single()
                        .attachments
                        .single()
                assertEquals(listOf(attachment.attachId), msg.attachIds)
                assertEquals(LANDING_MAX_BYTES, attachment.sizeBytes)
                assertEquals(LANDING_MAX_BYTES, File(attachment.source).length())
                assertEquals(null to 0, rig.shown)
            } finally {
                rig.dropStaged()
            }
        }

    @Test
    fun `before the daemon states a limit, the tray holds a file past the app's bound too big until one above it`() =
        runTest(main) {
            val rig = Rig(this)
            val uri = "content://docs/walkthrough.mov"
            rig.pipeline.describe = { picked, from -> videoAs(picked, from, 200 * MIB) }
            // No record read for the chat yet, then one whose daemon has sent no caps: no daemon's limit either way.
            rig.records.value = emptyList()
            runCurrent()
            rig.model.attach.add(listOf(uri), PickedFrom.FILES)
            runCurrent()
            val held = TooBig("walkthrough.mov", 200 * MIB, LANDING_MAX_BYTES)
            assertEquals(held to 0, rig.shown)
            rig.records.value = listOf(noCaps())
            runCurrent()
            assertEquals(held to 0, rig.shown)
            rig.model.attach.send {}
            runCurrent()
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertEquals(emptyList<Pair<String, Boolean>>(), rig.pipeline.prepared.value, "Send made a held item")
            rig.records.value = listOf(limitedTo(512 * MIB))
            runCurrent()
            assertEquals(null to 1, rig.shown)
            rig.model.attach.send {}
            runCurrent()
            val msg =
                rig.session.sent.value
                    .single() as ClientEvent.Msg
            assertEquals(1, msg.attachIds.size)
            assertEquals(listOf(uri to true), rig.pipeline.prepared.value)
            assertEquals(emptyList<Picked>(), rig.ui.picked)
            assertEquals(null to 0, rig.shown)
        }

    @Test
    fun `a file that may go before the daemon states a limit is too big once it states a lower one`() =
        runTest(main) {
            val rig = Rig(this)
            val uri = "content://docs/walkthrough.mov"
            rig.pipeline.describe = { picked, from -> videoAs(picked, from, 50 * MIB) }
            rig.records.value = listOf(noCaps())
            runCurrent()
            rig.model.attach.add(listOf(uri), PickedFrom.FILES)
            runCurrent()
            assertEquals(null to 1, rig.shown)
            rig.records.value = listOf(limitedTo(20 * MIB))
            runCurrent()
            assertEquals(TooBig("walkthrough.mov", 50 * MIB, 20 * MIB) to 0, rig.shown)
            rig.model.attach.send {}
            runCurrent()
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertEquals(emptyList<Pair<String, Boolean>>(), rig.pipeline.prepared.value, "Send made a held item")
            assertEquals(listOf(uri), rig.ui.picked.map { it.uri })
        }

    @Test
    fun `a daemon that states a limit above the app's bound has a file past that bound sent whole`() =
        runTest(main) {
            val rig = Rig(this)
            val uri = "content://docs/walkthrough.mov"
            val size = 200 * MIB
            rig.pipeline.streams = mapOf(uri to { Zeros(size) })
            rig.pipeline.describe = { picked, from -> videoAs(picked, from, size) }
            rig.records.value = listOf(limitedTo(512 * MIB))
            runCurrent()
            rig.model.attach.add(listOf(uri), PickedFrom.FILES)
            runCurrent()
            assertEquals(null to 1, rig.shown)
            try {
                rig.model.attach.send {}
                runCurrent()
                val attachment =
                    rig.store.outbox.value
                        .single()
                        .attachments
                        .single()
                assertEquals(AttachKind.VIDEO, attachment.kind)
                assertEquals(size, attachment.sizeBytes)
                assertEquals(size, File(attachment.source).length())
                assertEquals(null to 0, rig.shown)
            } finally {
                rig.dropStaged()
            }
        }

    @Test
    fun `a send the session takes leaves no line of an item Send stopped at before`() =
        runTest(main) {
            val rig = Rig(this)
            val uri = "content://docs/report.pdf"
            var opened = 0
            // The provider hands over more than the limit once, then its file as it now stands.
            rig.pipeline.streams = mapOf(uri to { Zeros(if (opened++ == 0) 5_000L else 10L) })
            rig.pipeline.describe = { picked, from -> describedAs(picked, from, size = 0L) }
            rig.records.value = listOf(limitedTo(1_000L))
            runCurrent()
            rig.model.attach.add(listOf(uri), PickedFrom.FILES)
            rig.model.attach.open()
            runCurrent()
            rig.model.attach.send {}
            runCurrent()
            assertEquals(TooBig("report.pdf", 1_001L, 1_000L) to 1, rig.shown)
            rig.model.attach.send {}
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
            assertEquals(null to 0, rig.shown)
            rig.model.attach.open()
            runCurrent()
            assertEquals(null to 0, rig.shown, "the line came back with the sheet")
        }

    @Test
    fun `a send the session takes leaves no line of an item a paste brought past what its copy may hold`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.describe = { picked, from ->
                describedAs(picked, from, if (from == PickedFrom.PASTE) 5_000L else 10L)
            }
            rig.records.value = listOf(limitedTo(1_000L))
            runCurrent()
            rig.model.attach.add(listOf("content://docs/notes.pdf"), PickedFrom.FILES)
            rig.clip.clip = "content://clip/big.pdf"
            rig.model.attach.paste()
            runCurrent()
            assertEquals(TooBig("big.pdf", 5_000L, 1_000L) to 1, rig.shown)
            rig.model.attach.send {}
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
            assertEquals(null to 0, rig.shown)
        }

    @Test
    fun `a paste's line that lands while a send runs stays once the session takes the send`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.describe = { uri, from ->
                if (from == PickedFrom.PASTE) {
                    Picked(uri, uri, PickedKind.IMAGE, "image/png", "huge.png", 200 * MIB, from)
                } else {
                    describedAs(uri, from, 10L)
                }
            }
            rig.records.value = listOf(limitedTo(20 * MIB))
            runCurrent()
            rig.model.attach.add(listOf("content://docs/a.pdf"), PickedFrom.FILES)
            rig.model.attach.open()
            runCurrent()
            val making = CompletableDeferred<Unit>()
            rig.preparing = making
            rig.model.attach.send {}
            runCurrent()
            // Send is making a.pdf as a paste of an image past what its copy may hold lands.
            rig.clip.clip = "content://clip/huge.png"
            rig.model.attach.paste()
            runCurrent()
            val line = TooBig("huge.png", 200 * MIB, LANDING_MAX_BYTES)
            assertEquals(line to 1, rig.shown)
            making.complete(Unit)
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
            rig.model.attach.open()
            runCurrent()
            assertEquals(line to 0, rig.shown, "the send took the line of a paste that landed as it ran")
        }

    @Test
    fun `a line Send gave before the daemon stated a limit goes once it does, and the next Send says the new one`() =
        runTest(main) {
            val rig = Rig(this)
            val uri = "content://docs/archive.zip"
            rig.pipeline.streams = mapOf(uri to { Zeros(LANDING_MAX_BYTES + 1) })
            rig.pipeline.describe = { picked, from -> describedAs(picked, from, size = 0L) }
            rig.records.value = listOf(noCaps())
            runCurrent()
            rig.model.attach.add(listOf(uri), PickedFrom.FILES)
            rig.model.attach.open()
            runCurrent()
            try {
                rig.model.attach.send {}
                runCurrent()
                assertEquals(TooBig("archive.zip", LANDING_MAX_BYTES + 1, LANDING_MAX_BYTES) to 1, rig.shown)
                rig.records.value = listOf(limitedTo(20 * MIB))
                runCurrent()
                assertEquals(null to 1, rig.shown)
                rig.model.attach.send {}
                runCurrent()
                assertEquals(TooBig("archive.zip", 20 * MIB + 1, 20 * MIB) to 1, rig.shown)
                rig.records.value = listOf(limitedTo(512 * MIB))
                runCurrent()
                assertEquals(null to 1, rig.shown)
                rig.model.attach.send {}
                runCurrent()
                assertEquals(1, rig.session.sent.value.size)
                assertEquals(null to 0, rig.shown)
            } finally {
                rig.dropStaged()
            }
        }

    @Test
    fun `a JPEG's line made past a small limit goes once the limit changes, and the image then goes`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.bytes = mapOf("content://p/big.png" to ByteArray(2_000))
            rig.pipeline.describe =
                { uri, from -> Picked(uri, uri, PickedKind.IMAGE, "image/png", "big.png", 900L, from) }
            rig.records.value = listOf(limitedTo(1_000L))
            runCurrent()
            rig.model.attach.add(listOf("content://p/big.png"), PickedFrom.PHOTOS)
            rig.model.attach.open()
            runCurrent()
            rig.model.attach.send {}
            runCurrent()
            assertEquals(TooBig("big.png", 2_000L, 1_000L) to 1, rig.shown)
            rig.records.value = listOf(limitedTo(5_000L))
            runCurrent()
            assertEquals(null to 1, rig.shown)
            rig.model.attach.send {}
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
            assertEquals(null to 0, rig.shown)
        }

    @Test
    fun `a paste's line of an item past the app's own bound stays as the daemon states its limit`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.describe =
                { uri, from -> Picked(uri, uri, PickedKind.IMAGE, "image/png", "huge.png", 200 * MIB, from) }
            rig.records.value = listOf(noCaps())
            runCurrent()
            rig.clip.clip = "content://clip/huge.png"
            rig.model.attach.paste()
            runCurrent()
            val line = TooBig("huge.png", 200 * MIB, LANDING_MAX_BYTES)
            assertEquals(line to 0, rig.shown)
            rig.records.value = listOf(limitedTo(20 * MIB))
            runCurrent()
            assertEquals(line to 0, rig.shown)
            rig.records.value = listOf(limitedTo(512 * MIB))
            runCurrent()
            assertEquals(line to 0, rig.shown)
        }
}
