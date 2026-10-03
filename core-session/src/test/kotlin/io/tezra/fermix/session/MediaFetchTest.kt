package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.CoroutineContext

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

/**
 * [inner], but the first block it runs is followed by [after], before whoever waits on the block resumes: a
 * cancellation there is one that lands as a `withContext` returns, which drops what its block returned.
 */
private class AfterFirstBlock(
    private val inner: CoroutineDispatcher,
    private val after: () -> Unit,
) : CoroutineDispatcher() {
    private var first = true

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val hooked = first
        first = false
        inner.dispatch(context) {
            block.run()
            if (hooked) after()
        }
    }
}

/** The blob frames the app was handed as they came. */
private fun Harness.blobFrames(): List<ServerEvent.Known> =
    events.filterIsInstance<SessionEvent.Server>().map { it.event }.filter { it !is ServerEvent.HelloAck }

/**
 * Session.fetchMedia over the vendored blob (server_events.jsonl's `media_begin` and `media_end`,
 * server_binary_frames.jsonl's `media_chunk`) and the vendored refusals: the blob streams into the file, and
 * one whose bytes break what its frames said, or that never ends, leaves no file behind.
 */
class MediaFetchTest {
    @TempDir
    lateinit var dir: File

    private val begin = vendoredServer("media_begin") as ServerEvent.MediaBegin
    private val end = vendoredServer("media_end") as ServerEvent.MediaEnd
    private val chunk = vendoredServerBinary("media_chunk")

    /** The vendored blob with its digest made true: the fixture's "bbbb…" is no hash of its three bytes. */
    private val whole = sha256(chunk.second)

    @Test
    fun `the phone's media_fetch is the vendored one`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            launch { harness.session.fetchMedia("a".repeat(64), File(dir, "blob")) }
            assertEquals(vendoredClient("media_fetch"), connection.expect<ClientEvent.MediaFetch>())
            connection.send(vendoredError("media_fetch_backlog_full"))
        }

    @Test
    fun `a blob streams into the file, its size and digest checked at its end`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val into = File(dir, "answer.pdf")
            val fetched = async { harness.session.fetchMedia(begin.ref, into) }
            assertEquals(ClientEvent.MediaFetch(begin.ref), connection.expect<ClientEvent.MediaFetch>())
            connection.send(begin.copy(sha256 = whole))
            connection.sendWithRaw(chunk.first, chunk.second)
            connection.send(end.copy(sha256 = whole))
            val expected = FetchedMedia("document", "application/pdf", 3, whole, "answer.pdf")
            assertEquals(OneShot.Answered(expected), fetched.await())
            assertArrayEquals(chunk.second, into.readBytes())
            assertTrue(harness.blobFrames().isEmpty(), "the fetch took every frame of its blob")
        }

    @Test
    fun `the vendored blob's bytes do not hash to its digest, so the fetch fails loud and leaves no file`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val into = File(dir, "answer.pdf")
            val fetched = async { runCatching { harness.session.fetchMedia(begin.ref, into) } }
            connection.expect<ClientEvent.MediaFetch>()
            connection.send(begin)
            connection.sendWithRaw(chunk.first, chunk.second)
            connection.send(end)
            assertTrue(fetched.await().exceptionOrNull() is MediaMismatchException)
            assertFalse(into.exists())
        }

    @Test
    fun `media_descriptor_mismatch in place of media_end discards what came`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val into = File(dir, "answer.pdf")
            val fetched = async { harness.session.fetchMedia(begin.ref, into) }
            connection.expect<ClientEvent.MediaFetch>()
            connection.send(begin)
            connection.sendWithRaw(chunk.first, chunk.second)
            connection.send(vendoredError("media_descriptor_mismatch"))
            assertEquals(OneShot.Refused("media_descriptor_mismatch"), fetched.await())
            assertFalse(into.exists())
        }

    @Test
    fun `a fetch the daemon's backlog refuses says so`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val ref = "a".repeat(64)
            val fetched = async { harness.session.fetchMedia(ref, File(dir, "blob")) }
            connection.expect<ClientEvent.MediaFetch>()
            connection.send(vendoredError("media_fetch_backlog_full"))
            assertEquals(OneShot.Refused("media_fetch_backlog_full"), fetched.await())
            assertFalse(File(dir, "blob").exists())
        }

    @Test
    fun `a chunk out of order breaks the blob`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val into = File(dir, "answer.pdf")
            val fetched = async { runCatching { harness.session.fetchMedia(begin.ref, into) } }
            connection.expect<ClientEvent.MediaFetch>()
            connection.send(begin.copy(sha256 = whole))
            connection.sendWithRaw(ServerEvent.MediaChunk(begin.ref, 1), chunk.second)
            connection.send(end.copy(sha256 = whole))
            assertTrue(fetched.await().exceptionOrNull() is MediaMismatchException)
            assertFalse(into.exists())
        }

    @Test
    fun `more bytes than its media_begin said break the blob`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val into = File(dir, "answer.pdf")
            val fetched = async { runCatching { harness.session.fetchMedia(begin.ref, into) } }
            connection.expect<ClientEvent.MediaFetch>()
            connection.send(begin.copy(sha256 = whole, sizeBytes = 2))
            connection.sendWithRaw(chunk.first, chunk.second)
            assertTrue(fetched.await().exceptionOrNull() is MediaMismatchException)
            assertFalse(into.exists())
            connection.send(end.copy(sha256 = whole))
            harness.settle()
            assertTrue(harness.blobFrames().isEmpty(), "the broken blob's end was the fetch's")
        }

    @Test
    fun `30 s with no frame times the fetch out and leaves no file`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val others = mutableListOf<ClientEvent>()
            backgroundScope.launch { connection.answerPings(backgroundScope, afterMs = 10, others) }
            val into = File(dir, "answer.pdf")
            val fetched = async { harness.session.fetchMedia(begin.ref, into) }
            harness.settle()
            connection.send(begin.copy(sha256 = whole))
            delay(ANSWER_TIMEOUT_MS + 1)
            assertEquals(OneShot.TimedOut, fetched.await())
            assertFalse(into.exists())
        }

    @Test
    fun `a caller cancelled as its file opens leaves no file`() =
        runTest {
            var fetch: Job? = null
            // The fetch's first block on its io dispatcher is the file's open.
            val io = AfterFirstBlock(StandardTestDispatcher(testScheduler)) { checkNotNull(fetch).cancel() }
            val harness = Harness(this)
            val connection = harness.connect(io = io)
            val into = File(dir, "answer.pdf")
            fetch = launch { harness.session.fetchMedia(begin.ref, into) }
            connection.expect<ClientEvent.MediaFetch>()
            checkNotNull(fetch).join()
            assertTrue(checkNotNull(fetch).isCancelled)
            assertFalse(into.exists(), "the file opened as its caller was cancelled was left")
        }

    @Test
    fun `no connection is offline, and no file is made`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            val into = File(dir, "answer.pdf")
            assertEquals(OneShot.Offline, harness.session.fetchMedia(begin.ref, into))
            assertFalse(into.exists())
        }

    @Test
    fun `a media reply no fetch asked for goes to the app as it came`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(begin)
            connection.sendWithRaw(chunk.first, chunk.second)
            connection.send(end)
            harness.settle()
            assertEquals(listOf(begin, chunk.first, end), harness.blobFrames())
        }

    @Test
    fun `a media reply pushed between a fetch's chunks goes to the app, and the fetch comes whole`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val into = File(dir, "answer.pdf")
            val twice = chunk.second + chunk.second
            val fetched = async { harness.session.fetchMedia(begin.ref, into) }
            connection.expect<ClientEvent.MediaFetch>()
            connection.send(begin.copy(sha256 = sha256(twice), sizeBytes = twice.size.toLong()))
            connection.sendWithRaw(chunk.first, chunk.second)
            // The engine pushes a media reply as the turn writes it, between a fetch's chunks.
            val reply = begin.copy(ref = "c".repeat(64), serverSeq = 17uL)
            val replyChunk = ServerEvent.MediaChunk(reply.ref, 0)
            connection.send(reply)
            connection.sendWithRaw(replyChunk, chunk.second)
            connection.sendWithRaw(ServerEvent.MediaChunk(begin.ref, 1), chunk.second)
            connection.send(end.copy(sha256 = sha256(twice)))
            assertEquals(sha256(twice), (fetched.await() as OneShot.Answered).value.sha256)
            assertArrayEquals(twice, into.readBytes())
            assertFalse(connection.link.phoneClose.isCompleted, "the phone closed its connection")
            assertEquals(listOf(reply, replyChunk), harness.blobFrames())
        }

    @Test
    fun `an empty ref is refused before anything goes`() =
        runTest {
            val harness = Harness(this)
            harness.connect()
            assertThrows<IllegalArgumentException> { harness.session.fetchMedia("", File(dir, "blob")) }
        }
}
