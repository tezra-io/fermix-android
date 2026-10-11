package io.tezra.fermix.chat

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private const val TAG = "SendCopyDeviceTest"

/** The send's limit here: several of the copy's 64 KiB reads, and past the pipe's own 64 KiB buffer. */
private const val BOUND = 256L * 1024

/** What the writer hands over at a time. */
private const val CHUNK_BYTES = 64 * 1024

/**
 * Where the endless writer stops, its loop's one bound: far past [BOUND], so a copy with no bound of its own reads all
 * of it and fails its test, not the phone's storage.
 */
private const val WRITER_STOP_BYTES = 64L * 1024 * 1024

/** What the stalled stream hands over before it stalls. */
private const val FIRST_BYTES = 1_024

/**
 * How long the stalled writer keeps its end open and silent at most, past every wait of its test: only the copy's own
 * close of its stream can end its read within the test's waits.
 */
private const val STALL_MILLIS = 60_000L

/** How long a cancelled copy may take to hand its caller back, and to let its thread go: a close takes milliseconds. */
private const val RELEASE_MILLIS = 5_000L

/** How often a wait on the copy's file looks again. */
private const val POLL_MILLIS = 20L

/** What the slow stream hands over in all, within [BOUND], a chunk at a time with a pause after each. */
private const val SLOW_BYTES = 64 * 1024
private const val SLOW_CHUNK_BYTES = 4 * 1024

/** The slow stream's own pace, a span the test defines: its reader finds the pipe empty between chunks. */
private const val SLOW_STEP_MILLIS = 20L

/** The name and type the provider gives its item. */
private const val NAME = "endless.bin"
private const val MIME = "application/octet-stream"

/**
 * Send's copy of an item's own bytes on a device (PhoneMedia.prepare): another app's stream that says no size and
 * never ends is copied at most the send's limit and a byte more, its stream then closed, which its writer sees; one
 * that ends at the limit or under it is copied whole, and so is one whose reading end is non-blocking and finds the
 * pipe empty between its writer's chunks; and one that stalls is given up as its caller is cancelled, its read ended
 * by the close, its file gone and its thread free again within [RELEASE_MILLIS].
 *
 * The stream is a FIFO under the chat's cache, written by a thread of the test's as a provider writes into the pipe it
 * hands over (ParcelFileDescriptor.createPipe): the copy opens it through the ContentResolver and reads it through the
 * same AutoCloseInputStream over a pipe as a provider's item. A provider of this test APK would be the app's own, which
 * the chat refuses (mayRead), so the FIFO stands in for it; what it leaves out is the provider's open over the binder,
 * which the copy's CancellationSignal cancels.
 */
class SendCopyDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) }
    private val directory = File(context.cacheDir, "send-copy-test")
    private val fifos = mutableListOf<File>()

    @Before
    fun clean() {
        directory.deleteRecursively()
        check(directory.mkdirs()) { "$directory could not be made" }
    }

    @After
    fun end() {
        fifos.forEach(::release)
        directory.deleteRecursively()
    }

    /** A FIFO named [name] under the cache, as a `file:` URI the chat reads held in its tray. */
    private fun fifo(name: String): File {
        val made = File(directory, name)
        Os.mkfifo(made.path, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        fifos += made
        return made
    }

    private fun picked(fifo: File): Picked =
        Picked("p1", Uri.fromFile(fifo).toString(), PickedKind.FILE, MIME, NAME, 0L, PickedFrom.FILES)

    @Test
    fun a_stream_that_says_no_size_and_never_ends_is_copied_a_byte_past_the_limit_then_closed() {
        val fifo = fifo("endless")
        val handed = AtomicLong()
        val closed = AtomicBoolean(false)
        val writer = writer(fifo) { zerosInto(it, WRITER_STOP_BYTES, handed, closed) }
        val into = File(directory, "prepared")
        val pipeline = PhoneMedia(context, log)
        val prepared = runBlocking { withTimeout(STEP_MILLIS) { pipeline.prepare(picked(fifo), true, into, BOUND) } }
        assertEquals(Prepared(MIME, NAME), prepared)
        assertEquals(BOUND + 1, into.length())
        writer.join(STEP_MILLIS)
        assertFalse("the writer still writes", writer.isAlive)
        assertTrue("the copy never closed its stream: the writer stopped at ${handed.get()} bytes", closed.get())
    }

    @Test
    fun a_stream_that_says_no_size_and_ends_at_the_limit_or_under_it_is_copied_whole() {
        val pipeline = PhoneMedia(context, log)
        for ((name, size) in listOf("at" to BOUND, "under" to BOUND / 2)) {
            val fifo = fifo(name)
            val writer = writer(fifo) { zerosInto(it, size, AtomicLong(), AtomicBoolean(false)) }
            val into = File(directory, "$name-prepared")
            runBlocking { withTimeout(STEP_MILLIS) { pipeline.prepare(picked(fifo), true, into, BOUND) } }
            writer.join(STEP_MILLIS)
            assertArrayEquals("$name: copied whole", ByteArray(size.toInt()), into.readBytes())
        }
    }

    @Test
    fun a_stream_whose_reading_end_is_non_blocking_and_finds_the_pipe_empty_at_times_is_copied_whole() {
        val fifo = fifo("non-blocking")
        val readable = CountDownLatch(1)
        val writer = writer(fifo) { slowlyInto(it, readable) }
        val into = File(directory, "prepared")
        val pipeline = PhoneMedia(context, log)
        try {
            runBlocking {
                val copying = async { pipeline.prepare(picked(fifo), true, into, BOUND) }
                nonBlocking(awaitReadingEnd(fifo))
                readable.countDown()
                withTimeout(STEP_MILLIS) { copying.await() }
            }
        } finally {
            readable.countDown()
            writer.join(STEP_MILLIS)
        }
        assertArrayEquals(ByteArray(SLOW_BYTES), into.readBytes())
    }

    /** The descriptor the copy reads [fifo] through once it has opened it, waited for at most [STEP_MILLIS]. */
    private suspend fun awaitReadingEnd(fifo: File): Int =
        withTimeout(STEP_MILLIS) {
            var end = readingEndOf(fifo)
            while (end == null) {
                delay(POLL_MILLIS)
                end = readingEndOf(fifo)
            }
            end
        }

    @Test
    fun a_stalled_stream_is_given_up_as_its_caller_is_cancelled_and_its_thread_let_go() {
        val fifo = fifo("stalled")
        val silent = CountDownLatch(1)
        val writer = writer(fifo) { stallInto(it, silent) }
        val io = Executors.newSingleThreadExecutor()
        try {
            val freed = runBlocking { cancelledMidRead(fifo, io, silent) }
            Log.i(TAG, "a cancelled copy let its thread go $freed ms after the cancel")
            assertTrue("its thread was let go after $freed ms", freed <= RELEASE_MILLIS)
        } finally {
            silent.countDown()
            writer.join(STEP_MILLIS)
            io.shutdownNow()
        }
        assertFalse("the writer still holds its end", writer.isAlive)
    }

    /**
     * [fifo] copied on [io], the copy's one thread, and cancelled once it took what came and waits on the next read:
     * how long after the cancel its thread was free again, as a task given to it ran. The caller is handed back within
     * [RELEASE_MILLIS] and the thread let go within as long, its file gone, or it fails; [silent] is let go however it
     * ends, so a read nothing else ended ends then.
     */
    private suspend fun cancelledMidRead(
        fifo: File,
        io: ExecutorService,
        silent: CountDownLatch,
    ): Long =
        coroutineScope {
            val into = File(directory, "prepared")
            val pipeline = PhoneMedia(context, log, io = io.asCoroutineDispatcher())
            val copying = launch { pipeline.prepare(picked(fifo), true, into, BOUND) }
            try {
                withTimeout(STEP_MILLIS) { while (into.length() < FIRST_BYTES) delay(POLL_MILLIS) }
                val cancelled = SystemClock.elapsedRealtime()
                copying.cancel()
                assertNotNull("the caller was held", withTimeoutOrNull(RELEASE_MILLIS) { copying.join() })
                awaitFree(io)
                assertFalse("the copy given up left its file", into.exists())
                SystemClock.elapsedRealtime() - cancelled
            } finally {
                silent.countDown()
            }
        }
}

/** [io]'s one thread takes a task within [RELEASE_MILLIS], or it fails: a read that holds it takes none. */
private fun awaitFree(io: ExecutorService) {
    try {
        io.submit {}.get(RELEASE_MILLIS, TimeUnit.MILLISECONDS)
    } catch (held: TimeoutException) {
        throw AssertionError("the read holds its thread", held)
    }
}

/**
 * The writing end of [fifo], as another app's provider holds the pipe it handed over, on a thread of its own: opened
 * (which waits for the copy to open its end), then [write] into it, and closed however it ends; the pipe closed by its
 * reader is an IOException, logged.
 */
private fun writer(
    fifo: File,
    write: (FileOutputStream) -> Unit,
): Thread =
    thread(name = "${fifo.name}-writer") {
        try {
            FileOutputStream(fifo).use(write)
        } catch (closed: IOException) {
            Log.i(TAG, "the copy closed ${fifo.name}", closed)
        }
    }

/**
 * Zeros into [into], a chunk at a time, counted in [handed], until [size] went (the loop's one bound) or a write finds
 * the pipe closed by its reader, which [closed] then says.
 */
private fun zerosInto(
    into: FileOutputStream,
    size: Long,
    handed: AtomicLong,
    closed: AtomicBoolean,
) {
    val chunk = ByteArray(CHUNK_BYTES)
    try {
        while (handed.get() < size) {
            val count = minOf(size - handed.get(), CHUNK_BYTES.toLong()).toInt()
            into.write(chunk, 0, count)
            handed.addAndGet(count.toLong())
        }
    } catch (gone: IOException) {
        closed.set(true)
        throw gone
    }
}

/**
 * Nothing into [into] until [readable] is let go, [STEP_MILLIS] at most, then [SLOW_BYTES] of zeros, a chunk every
 * [SLOW_STEP_MILLIS], as an honest provider hands over what it makes as it makes it.
 */
private fun slowlyInto(
    into: FileOutputStream,
    readable: CountDownLatch,
) {
    readable.await(STEP_MILLIS, TimeUnit.MILLISECONDS)
    repeat(SLOW_BYTES / SLOW_CHUNK_BYTES) {
        into.write(ByteArray(SLOW_CHUNK_BYTES))
        SystemClock.sleep(SLOW_STEP_MILLIS)
    }
}

/**
 * The descriptor this process reads [fifo] through: the one `/proc/self/fd` links to it that is open for reading, the
 * copy's (the writer's is open for writing); none before the copy has opened it.
 */
private fun readingEndOf(fifo: File): Int? =
    File("/proc/self/fd")
        .list()
        .orEmpty()
        .mapNotNull { it.toIntOrNull() }
        .firstOrNull { fd -> linkOf(fd)?.endsWith("/${fifo.parentFile?.name}/${fifo.name}") == true && readsBy(fd) }

/** Where descriptor [fd] of this process leads; none once it has closed as the list was read (ENOENT). */
private fun linkOf(fd: Int): String? =
    try {
        Os.readlink("/proc/self/fd/$fd")
    } catch (gone: ErrnoException) {
        if (gone.errno != OsConstants.ENOENT) throw gone
        null
    }

/** Whether [fd] is open for reading alone, as a duplicate of it, which shares its open file, says. */
private fun readsBy(fd: Int): Boolean =
    ParcelFileDescriptor.fromFd(fd).use { dup ->
        (Os.fcntlInt(dup.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY
    }

/**
 * The open file [fd] reads through made non-blocking, as a provider that hands over pipe2(O_NONBLOCK)'s reading end
 * makes it: O_NONBLOCK is the open file's, set through a duplicate of [fd], which shares it. A read of an empty pipe
 * then hands over no bytes (EAGAIN) rather than wait.
 */
private fun nonBlocking(fd: Int) {
    ParcelFileDescriptor.fromFd(fd).use { dup ->
        val flags = Os.fcntlInt(dup.fileDescriptor, OsConstants.F_GETFL, 0)
        Os.fcntlInt(dup.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    }
}

/** [FIRST_BYTES] into [into], then nothing until [silent] is let go, [STALL_MILLIS] at most. */
private fun stallInto(
    into: FileOutputStream,
    silent: CountDownLatch,
) {
    into.write(ByteArray(FIRST_BYTES))
    silent.await(STALL_MILLIS, TimeUnit.MILLISECONDS)
}

/**
 * Either end of [fifo] still waiting to open, opened and let go at once, so no thread of a test that failed half-way
 * waits on it: a writer then finds its reader gone, a reader its writer.
 */
private fun release(fifo: File) {
    listOf(OsConstants.O_RDONLY, OsConstants.O_WRONLY).forEach { mode -> openAndClose(fifo, mode) }
}

/** [fifo] opened as [mode] says, without waiting, and closed; a writing end with no reader is none to open (ENXIO). */
private fun openAndClose(
    fifo: File,
    mode: Int,
) {
    try {
        Os.close(Os.open(fifo.path, mode or OsConstants.O_NONBLOCK, 0))
    } catch (none: ErrnoException) {
        if (none.errno != OsConstants.ENXIO) throw none
        Log.i(TAG, "no reader of ${fifo.name} waited to open")
    }
}
