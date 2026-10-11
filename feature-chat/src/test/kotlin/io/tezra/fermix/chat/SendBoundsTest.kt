package io.tezra.fermix.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A small limit of the daemon's, the size of a test's documents. */
private const val LIMIT = 1_000L

/** The engine's own `caps.max_media_bytes` when its config names none: 20 MiB. */
private const val DAEMON_LIMIT = 20L * 1024 * 1024

/**
 * Where a test's stream that never ends gives up: four times the daemon's limit, so a copy with no bound of its own
 * reads all of it and fails its test, rather than filling the disk.
 */
private const val OVERRUN = 4 * DAEMON_LIMIT

private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

/** Another app's stream of [size] zeros, which counts what it [handed] over and ends past [size]. */
private class Zeros(
    private val size: Long,
) : InputStream() {
    var handed = 0L
        private set

    override fun read(): Int = if (read(ByteArray(1), 0, 1) < 0) -1 else 0

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val left = size - handed
        if (left <= 0) return -1
        val count = minOf(left, length.toLong()).toInt()
        into.fill(0, offset, offset + count)
        handed += count
        return count
    }
}

/** A file picked from Files, named [name], of [size] as its provider says: 0 when it says none. */
private fun document(
    name: String,
    size: Long = 0L,
    kind: PickedKind = PickedKind.FILE,
): Picked = Picked(name, "content://docs/$name", kind, "application/octet-stream", name, size, PickedFrom.FILES)

/**
 * What Send makes of an item that goes as its own bytes is held to the daemon's limit as it is copied, whatever its
 * provider says of its size (AttachMaker over the fake pipeline, which copies as PhoneMedia does): a stream past the
 * limit is read a byte past it and no further, and refused as too big before it is hashed or staged, those after it
 * never made and no scratch file left; one at the limit or under it goes whole, sized by its provider or not. The line
 * says the bytes the copy read, and an image's JPEG its own size. A copy holds to the limit its send began with, and
 * each item to the smaller of that and the limit as it is weighed; a send cancelled as a copy stalls keeps nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendBoundsTest {
    @TempDir
    lateinit var dir: File

    private inner class Rig(
        scope: TestScope,
        maxBytes: () -> Long,
    ) {
        val store = FakeChatStore()
        val pipeline = FakePipeline()
        val files = FakeChatFiles(store)
        val scratches = mutableListOf<File>()
        private val io = StandardTestDispatcher(scope.testScheduler)
        private val parts =
            fakeParts(sample(), FakeChatSession(store), store, scope.backgroundScope).copy(
                media = pipeline,
                files = files,
                scratch = { File.createTempFile("scratch", null, dir).also { scratches += it } },
                io = io,
            )
        val maker = AttachMaker(parts, maxBytes, io)

        /** The send made [made] scratch files and kept none of them, nothing staged and nothing let go from staging. */
        fun keptNothing(made: Int) {
            assertEquals(made, scratches.size, "the scratch files made")
            assertTrue(scratches.none(File::exists), "a scratch file was left: $scratches")
            assertEquals(emptyList<String>(), files.staged.value, "staged")
            assertEquals(emptyList<String>(), files.released.value, "let go from staging")
        }
    }

    @Test
    fun `a document its provider sizes at nothing and streams a byte past the limit stops the send, kept nowhere`() =
        runTest {
            val rig = Rig(this) { LIMIT }
            val past = Zeros(LIMIT + 1)
            rig.pipeline.streams =
                mapOf("content://docs/notes.pdf" to { Zeros(10) }, "content://docs/report.pdf" to { past })
            val chosen = listOf(document("notes.pdf"), document("report.pdf"), document("after.pdf"))
            val made = rig.maker.make(chosen, asFiles = false)
            assertEquals(Made.TooBigMade(TooBig("report.pdf", LIMIT + 1, LIMIT)), made)
            assertEquals(LIMIT + 1, past.handed)
            val prepared =
                rig.pipeline.prepared.value
                    .map { it.first }
            assertEquals(listOf("content://docs/notes.pdf", "content://docs/report.pdf"), prepared)
            rig.keptNothing(made = 2)
        }

    @Test
    fun `a stream that never ends is read a byte past the daemon's limit and no further, then refused`() =
        runTest {
            val rig = Rig(this) { DAEMON_LIMIT }
            val endless = Zeros(OVERRUN)
            rig.pipeline.streams = mapOf("content://docs/endless.bin" to { endless })
            val made = rig.maker.make(listOf(document("endless.bin")), asFiles = false)
            assertEquals(DAEMON_LIMIT + 1, endless.handed, "the copy read past a byte over the limit")
            assertEquals(Made.TooBigMade(TooBig("endless.bin", DAEMON_LIMIT + 1, DAEMON_LIMIT)), made)
            rig.keptNothing(made = 1)
        }

    @Test
    fun `a video and an image sent as a file are held to the limit as a document is`() =
        runTest {
            val rig = Rig(this) { LIMIT }
            val video = Zeros(OVERRUN)
            val photo = Zeros(OVERRUN)
            rig.pipeline.streams = mapOf("content://docs/clip.mp4" to { video }, "content://docs/cat.heic" to { photo })
            val clip = rig.maker.make(listOf(document("clip.mp4", kind = PickedKind.VIDEO)), asFiles = false)
            val cat = rig.maker.make(listOf(document("cat.heic", kind = PickedKind.IMAGE)), asFiles = true)
            assertEquals(Made.TooBigMade(TooBig("clip.mp4", LIMIT + 1, LIMIT)), clip)
            assertEquals(Made.TooBigMade(TooBig("cat.heic", LIMIT + 1, LIMIT)), cat)
            assertEquals(LIMIT + 1 to LIMIT + 1, video.handed to photo.handed)
            rig.keptNothing(made = 2)
        }

    @Test
    fun `a document at the limit and one under it, neither sized by its provider, go whole`() =
        runTest {
            val rig = Rig(this) { LIMIT }
            rig.pipeline.streams =
                mapOf("content://docs/at.pdf" to { Zeros(LIMIT) }, "content://docs/under.pdf" to { Zeros(10) })
            val made = rig.maker.make(listOf(document("at.pdf"), document("under.pdf")), asFiles = false)
            val staged = (made as Made.Staged).attachments
            assertEquals(listOf(LIMIT, 10L), staged.map { it.sizeBytes })
            assertEquals(listOf(sha(ByteArray(LIMIT.toInt())), sha(ByteArray(10))), staged.map { it.sha256 })
            assertEquals(staged.map { it.source }, rig.files.staged.value)
            assertTrue(rig.scratches.none(File::exists), "a scratch file was left: ${rig.scratches}")
        }

    @Test
    fun `the line says the bytes the copy read, whatever size its provider gave`() =
        runTest {
            val rig = Rig(this) { 500L }
            val past = Zeros(OVERRUN)
            rig.pipeline.streams = mapOf("content://docs/report.pdf" to { past })
            val made = rig.maker.make(listOf(document("report.pdf", size = 900L)), asFiles = false)
            assertEquals(Made.TooBigMade(TooBig("report.pdf", 501L, 500L)), made)
            assertEquals(501L, past.handed)
            rig.keptNothing(made = 1)
        }

    @Test
    fun `an image whose JPEG comes out past the limit says the JPEG's size, whatever its provider said`() =
        runTest {
            val rig = Rig(this) { LIMIT }
            rig.pipeline.bytes = mapOf("content://docs/big.png" to ByteArray(2_000))
            val made = rig.maker.make(listOf(document("big.png", 5_000L, PickedKind.IMAGE)), asFiles = false)
            assertEquals(Made.TooBigMade(TooBig("big.png", 2_000L, LIMIT)), made)
            rig.keptNothing(made = 1)
        }

    @Test
    fun `a copy holds to the limit its send began with, and each item to the smaller of it and the limit then`() =
        runTest {
            // Caps that arrive as the send goes, a first hello_ack's, are applied to the item made under them.
            val arriving = ArrayDeque(listOf(Long.MAX_VALUE, LIMIT))
            val first = Rig(this) { arriving.removeFirst() }
            first.pipeline.streams = mapOf("content://docs/a.pdf" to { Zeros(5_000) })
            val capped = first.maker.make(listOf(document("a.pdf")), asFiles = false)
            assertEquals(Made.TooBigMade(TooBig("a.pdf", 5_000L, LIMIT)), capped)
            first.keptNothing(made = 1)
            // A limit that rises as it goes never lets a copy cut at the first one through.
            val rising = ArrayDeque(listOf(LIMIT, DAEMON_LIMIT))
            val second = Rig(this) { rising.removeFirst() }
            val endless = Zeros(OVERRUN)
            second.pipeline.streams = mapOf("content://docs/b.pdf" to { endless })
            val cut = second.maker.make(listOf(document("b.pdf")), asFiles = false)
            assertEquals(Made.TooBigMade(TooBig("b.pdf", LIMIT + 1, LIMIT)), cut)
            assertEquals(LIMIT + 1, endless.handed)
            assertEquals(emptyList<Long>(), arriving.toList() + rising.toList(), "each limit read once")
        }

    @Test
    fun `a send cancelled as an item's copy stalls leaves no scratch file and stages nothing`() =
        runTest {
            val rig = Rig(this) { LIMIT }
            rig.pipeline.streams = mapOf("content://docs/a.pdf" to { Zeros(10) })
            rig.pipeline.stallsCopying = setOf("content://docs/b.pdf")
            val sending = backgroundScope.async { rig.maker.make(listOf(document("a.pdf"), document("b.pdf")), false) }
            runCurrent()
            assertTrue(sending.isActive, "the send ended before the stalled copy")
            sending.cancel()
            runCurrent()
            assertTrue(sending.isCancelled)
            rig.keptNothing(made = 2)
        }
}
