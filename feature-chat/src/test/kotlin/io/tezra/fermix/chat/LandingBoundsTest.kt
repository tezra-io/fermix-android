package io.tezra.fermix.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.session.MAX_ATTACHMENTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import java.io.InputStream

/** Where a test's stream that never ends gives up: past four times the bound a landing copy may hold. */
private const val OVERRUN = 4 * LANDING_MAX_BYTES

/**
 * Another app's stream that never ends: zeros for as long as it is read, until the reader has taken past [OVERRUN],
 * when it fails the test, so a copy with no bound of its own is caught, not left to fill the disk.
 */
private class GuardedEndlessStream : InputStream() {
    private var handed = 0L

    override fun read(): Int = if (read(ByteArray(1), 0, 1) < 0) -1 else 0

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        handed += length
        if (handed > OVERRUN) throw AssertionError("a landing copy read $handed bytes, past four times its bound")
        return length
    }
}

private fun shared(uris: List<String>) = Shared(uris, null, refused = emptyList(), past = 0)

/** A file another app shares, of [size] as its provider says. */
private fun file(
    uri: String,
    from: PickedFrom,
    size: Long = 0L,
) = Picked(uri, uri, PickedKind.FILE, "application/octet-stream", uri.substringAfterLast('/'), size, from)

/** The sample Fermix as its record stands before the daemon's first `hello_ack`: no caps. */
private fun noCaps(): Instance = sample().copy(caps = null)

/** Ten items of a provider that stalls as each is described. */
private val STALLED = (1..MAX_ATTACHMENTS).map { "content://stalls/$it.png" }

/** How long after one share another comes, in a test of shares that wait for those before them. */
private const val LATER_MILLIS = 1_000L

/**
 * What lands of another app's share, a paste or the keyboard is bounded whatever the other side says: its copy in
 * bytes, by the app's own [LANDING_MAX_BYTES] whatever the chat's record or the daemon states; its item's name and type
 * by the app's own bounds on a name and a type, so its `attach_begin` fits one frame; what lands at once in one chat,
 * by the tray's ten, the landings running one at a time; and in time, a landing given [LANDING_WAIT_MILLIS] in all
 * once its turn comes and as long to wait for it, the wait for the chat's draft [READ_WAIT_MILLIS]. The owner's own
 * picks never wait behind another app's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LandingBoundsTest {
    private val main = StandardTestDispatcher()

    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    private inner class Rig(
        scope: TestScope,
        record: Instance = sample(),
        cache: (FakeChatStore) -> ChatStore = { it },
    ) {
        val store = FakeChatStore()
        val session = FakeChatSession(store)
        val pipeline = FakePipeline()
        val log = FakeLog()
        val records = MutableStateFlow(listOf(record))
        val viewModels = ViewModelStore()

        /** What happens as a scratch file is made, after it is: a test's cancellation in that instant. */
        var onScratch: () -> Unit = {}
        private val parts =
            fakeParts(sample(), session, store, scope.backgroundScope).copy(
                records = records,
                store = cache(store),
                media = pipeline,
                files = FakeChatFiles(store),
                log = log.log,
                scratch = { File.createTempFile("scratch", null, dir).also { onScratch() } },
                io = main,
            )
        val model: ChatViewModel =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { ChatViewModel(parts, SavedStateHandle()) } })
                .get(ChatViewModel::class)
        val ui: AttachUi get() = model.attach.ui.value

        fun logged(part: String): Boolean = log.lines.value.any { part in it }
    }

    /** [record]'s chat given a share of a stream that never ends; what it copied, with nothing left of it. */
    private fun TestScope.endlessInto(record: Instance) {
        val rig = Rig(this, record)
        val uri = "content://another.app/endless.bin"
        rig.pipeline.describe = { shared, from -> file(shared, from) }
        rig.pipeline.streams = mapOf(uri to { GuardedEndlessStream() })
        runCurrent()
        rig.model.share(shared(listOf(uri)))
        runCurrent()
        assertEquals(listOf(uri to LANDING_MAX_BYTES + 1), rig.pipeline.copied.value)
        assertEquals(emptyList<Picked>(), rig.ui.picked)
        assertEquals(TooBig("endless.bin", LANDING_MAX_BYTES + 1, LANDING_MAX_BYTES), rig.ui.tooBig)
        assertEquals(emptyList<File>(), dir.listFiles().orEmpty().toList(), "the part-copy was left")
    }

    @Test
    fun `a landing cancelled as its scratch file is made leaves no file behind`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.describe = { shared, from -> file(shared, from, size = 16) }
            rig.onScratch = { rig.viewModels.clear() }
            runCurrent()
            rig.model.share(shared(listOf("content://another.app/a.bin")))
            runCurrent()
            assertEquals(emptyList<File>(), dir.listFiles().orEmpty().toList(), "a scratch file was left unnamed")
        }

    @Test
    fun `a stream that never ends, shared into a chat whose record has no caps, stops a byte past the app's bound`() =
        runTest(main) { endlessInto(noCaps()) }

    @Test
    fun `a daemon that states the largest limit there is still has a file's copy held to the app's own bound`() =
        runTest(main) {
            val record = sample().let { it.copy(caps = it.caps?.copy(maxMediaBytes = Long.MAX_VALUE)) }
            endlessInto(record)
        }

    @Test
    fun `shares that land at once take the tray's room before they copy, so no more than ten are ever copied`() =
        runTest(main) {
            val rig = Rig(this)
            val first = (1..MAX_ATTACHMENTS).map { "content://one/a$it" }
            // Both shares come before the chat's record is read, and wait for it together.
            rig.records.value = emptyList()
            rig.model.share(shared(first))
            rig.model.share(shared((1..MAX_ATTACHMENTS).map { "content://two/b$it" }))
            runCurrent()
            rig.records.value = listOf(sample())
            runCurrent()
            assertEquals(
                first,
                rig.pipeline.copied.value
                    .map { it.first },
            )
            assertEquals(first.map { it.substringAfterLast('/') }, rig.ui.picked.map { it.name })
            assertEquals(MAX_ATTACHMENTS, dir.listFiles().orEmpty().size)
            assertTrue(rig.logged("10 picked past the ten a send takes were left out"), "${rig.log.lines.value}")
        }

    @Test
    fun `a share of ten items its provider stalls on ends in one landing's time, and the share behind it lands`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.stallsDescribing = STALLED.toSet()
            runCurrent()
            rig.model.share(shared(STALLED))
            advanceTimeBy(LATER_MILLIS)
            rig.model.share(shared(listOf("content://media/2.png")))
            runCurrent()
            advanceTimeBy(LANDING_WAIT_MILLIS - LATER_MILLIS - 1)
            runCurrent()
            assertEquals(emptyList<Picked>(), rig.ui.picked, "the next landed before the one ahead of it ended")
            advanceTimeBy(2)
            runCurrent()
            assertTrue(rig.logged("did not describe its items in time"), "${rig.log.lines.value}")
            assertEquals(listOf("2.png"), rig.ui.picked.map { it.name })
        }

    @Test
    fun `a provider that stalls handing over its bytes is given up in time, its part-copy gone, and the next lands`() =
        runTest(main) {
            val rig = Rig(this)
            val stalled = "content://stalls/1.bin"
            rig.pipeline.describe = { uri, from -> file(uri, from, size = 10L) }
            rig.pipeline.bytes = mapOf("content://media/2.bin" to ByteArray(10))
            rig.pipeline.stallsCopying = setOf(stalled)
            runCurrent()
            rig.model.share(shared(listOf(stalled)))
            advanceTimeBy(LATER_MILLIS)
            rig.model.share(shared(listOf("content://media/2.bin")))
            runCurrent()
            assertEquals(1, dir.listFiles().orEmpty().size, "the stalled copy's part-copy")
            advanceTimeBy(LANDING_WAIT_MILLIS - LATER_MILLIS + 1)
            runCurrent()
            assertTrue(rig.logged("did not hand its items over in time"), "${rig.log.lines.value}")
            assertEquals(listOf("2.bin"), rig.ui.picked.map { it.name })
            assertEquals(listOf("content://media/2.bin" to 10L), rig.pipeline.copied.value)
            assertEquals(1, dir.listFiles().orEmpty().size, "a part-copy was left")
        }

    @Test
    fun `a share waits for those before it no longer than a landing's time, then is left out, logged`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.stallsDescribing = STALLED.toSet()
            runCurrent()
            rig.model.share(shared(STALLED))
            advanceTimeBy(LATER_MILLIS)
            rig.model.share(shared(STALLED))
            advanceTimeBy(LATER_MILLIS)
            rig.model.share(shared(listOf("content://media/3.png")))
            runCurrent()
            // The first ends at its time, the second takes its turn then, and the third has waited its time by then.
            advanceTimeBy(LANDING_WAIT_MILLIS + 1)
            runCurrent()
            assertTrue(rig.logged("waited past its time"), "${rig.log.lines.value}")
            advanceTimeBy(LANDING_WAIT_MILLIS)
            runCurrent()
            assertEquals(emptyList<Picked>(), rig.ui.picked)
            rig.model.share(shared(listOf("content://media/4.png")))
            runCurrent()
            assertEquals(listOf("4.png"), rig.ui.picked.map { it.name })
        }

    @Test
    fun `the owner's own pick lands at once while another app's shares stall ahead of it`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pipeline.stallsDescribing = STALLED.toSet()
            runCurrent()
            rig.model.share(shared(STALLED))
            rig.model.share(shared(STALLED))
            runCurrent()
            rig.model.attach.add(listOf("content://media/photo.png"), PickedFrom.PHOTOS)
            runCurrent()
            assertEquals(listOf("photo.png"), rig.ui.picked.map { it.name })
        }

    @Test
    fun `an item another app names and types past one frame lands named and typed within the app's bounds, and goes`() =
        runTest(main) {
            val rig = Rig(this)
            val uri = "content://another.app/long"
            val name = "n".repeat(5_000) + ".bin"
            rig.pipeline.describe = { shared, from ->
                Picked(shared, shared, PickedKind.FILE, "application/" + "x".repeat(5_000), name, 10L, from)
            }
            rig.pipeline.bytes = mapOf(uri to ByteArray(10))
            runCurrent()
            rig.model.share(shared(listOf(uri)))
            runCurrent()
            val landed = rig.ui.picked.single()
            assertTrue(landed.name.encodeToByteArray().size <= NAME_MAX_BYTES, "a name of ${landed.name.length}")
            assertTrue(landed.name.endsWith(".bin"), landed.name)
            assertEquals(UNKNOWN_MIME, landed.mime)
            rig.model.attach.send {}
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
        }

    @Test
    fun `a share's words wait for the chat's draft no longer than the read wait, then are left out, logged`() =
        runTest(main) {
            val never: (FakeChatStore) -> ChatStore = { store ->
                object : ChatStore by store {
                    override fun chat(): Flow<ChatState> = flow { awaitCancellation() }
                }
            }
            val rig = Rig(this, cache = never)
            rig.model.share(Shared(emptyList(), "look at this", refused = emptyList(), past = 0))
            runCurrent()
            advanceTimeBy(READ_WAIT_MILLIS + 1)
            runCurrent()
            assertTrue(rig.logged("draft was not read back in time"), "${rig.log.lines.value}")
            assertEquals("", rig.model.composer.field.value.text)
        }
}
