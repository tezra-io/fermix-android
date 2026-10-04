package io.tezra.fermix.data

import androidx.datastore.core.okio.OkioSerializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.coroutines.CoroutineContext

/** How long, in the test's virtual time, a reader following the records may take to show a write that has ended. */
private const val WAIT_MILLIS = 1_000L

/** At most how many tasks a held thread runs when the test lets it. */
private const val MAX_TASKS = 100

/**
 * [inner], whose writes wait once [holding] is set, until [release]: a write held where DataStore 1.2.1 has raised
 * its version counter and not yet put the written data in its cache, as it writes the scratch file between the two
 * (DataStoreImpl.writeData). [held] says a write is waiting there.
 */
private class HeldWrites<T>(
    private val inner: OkioSerializer<T>,
) : OkioSerializer<T> by inner {
    var holding = false
    val held = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()

    fun release() {
        released.complete(Unit)
    }

    override suspend fun writeTo(
        t: T,
        sink: BufferedSink,
    ) {
        if (holding) {
            held.complete(Unit)
            released.await()
        }
        inner.writeTo(t, sink)
    }
}

/**
 * A reader's own thread that runs what is dispatched to it only when the test says ([run]), as a main thread busy
 * elsewhere, or blocked waiting for another read, does not; once [release]d, it runs everything at once.
 */
private class HeldThread : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()
    private var released = false

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !released

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queued.addLast(block)
    }

    /** Runs what is queued, and what that queues, at most [MAX_TASKS], and fails loud past them. */
    fun run() {
        repeat(MAX_TASKS) { (queued.removeFirstOrNull() ?: return).run() }
        check(queued.isEmpty()) { "more than $MAX_TASKS tasks" }
    }

    fun release() {
        released = true
        run()
    }
}

/**
 * A reader that starts while a write of the records or the settings is under way, on one thread and virtual time, so
 * nothing here depends on timing: DataStore's own `data` started then reads the file without the write's lock, gives
 * the old data, and drops the write's data as no newer (the cause, which the stores read around); the stores' readers
 * started then, once or following, get what the write wrote; and a reader or a writer whose own thread runs nothing
 * more once it has asked keeps no reader waiting, as a read's or a write's transform runs on the store's thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadsDuringWritesTest {
    @TempDir
    lateinit var directory: File

    private val first = instance(gateway = 1)
    private val second = instance(gateway = 3, host = "linux-box")
    private val third = instance(gateway = 5, host = "nas")

    @Test
    fun `DataStore's own data, collected from while a write is held, gives the old records and skips the written`() =
        runTest {
            val serializer = HeldWrites(InstancesSerializer)
            val records = atomicDataStore(File(directory, "i.json"), serializer, backgroundScope)
            val old = records.updateData { Instances(listOf(first)) }
            serializer.holding = true
            val writing = launch { records.updateData { it.copy(instances = it.instances + second) } }
            serializer.held.await()
            val seen = mutableListOf<Instances>()
            val following = launch { records.data.collect { seen.add(it) } }
            runCurrent()
            serializer.release()
            writing.join()
            val written = records.data.first()
            val next = records.updateData { it.copy(instances = it.instances + third) }
            runCurrent()
            following.cancel()
            assertEquals(listOf(first, second), written.instances)
            assertEquals(listOf(old, next), seen)
        }

    @Test
    fun `a reader of the records started while a write is held reads what it wrote, once and following`() =
        runTest {
            val serializer = HeldWrites(InstancesSerializer)
            val records = atomicDataStore(File(directory, "i.json"), serializer, backgroundScope)
            val store = InstanceStore(records, ProfileDatabases(TestContext, File(directory, "instances")))
            store.upsert(first)
            serializer.holding = true
            val writing = launch { store.upsert(second) }
            serializer.held.await()
            val once = async { store.instances.first() }
            val following = async { store.instances.first { it.size == 2 } }
            runCurrent()
            serializer.release()
            writing.join()
            assertEquals(listOf(first, second), once.await())
            assertEquals(listOf(first, second), withTimeout(WAIT_MILLIS) { following.await() })
        }

    @Test
    fun `a reader of the settings started while a write is held reads the lock it turned on, once and following`() =
        runTest {
            val serializer = HeldWrites(AppSettingsSerializer)
            val settings = atomicDataStore(File(directory, "s.json"), serializer, backgroundScope)
            val store = AppSettingsStore(settings)
            store.setLastChat(ChatRef(first.id, MAIN_PROFILE))
            serializer.holding = true
            val writing = launch { store.setAppLock(true) }
            serializer.held.await()
            val once = async { store.settings.first() }
            val following = async { store.settings.first { it.appLock } }
            runCurrent()
            serializer.release()
            writing.join()
            assertTrue(once.await().appLock)
            assertTrue(withTimeout(WAIT_MILLIS) { following.await() }.appLock)
        }

    @Test
    fun `a reader whose own thread is held keeps no other reader waiting`() =
        runTest {
            val records = atomicDataStore(File(directory, "i.json"), InstancesSerializer, backgroundScope)
            val store = InstanceStore(records, ProfileDatabases(TestContext, File(directory, "instances")))
            store.upsert(first)
            val thread = HeldThread()
            val held = async(thread) { store.instances.first() }
            try {
                // The held reader starts, reads the store's version on the store's thread, and asks for its read.
                thread.run()
                runCurrent()
                thread.run()
                // The store takes that read while the reader's thread runs nothing more.
                runCurrent()
                assertEquals(listOf(first), withTimeout(WAIT_MILLIS) { store.instances.first() })
            } finally {
                thread.release()
            }
            assertEquals(listOf(first), held.await())
        }

    @Test
    fun `a writer whose own thread is held keeps no reader waiting`() =
        runTest {
            val records = atomicDataStore(File(directory, "i.json"), InstancesSerializer, backgroundScope)
            val store = InstanceStore(records, ProfileDatabases(TestContext, File(directory, "instances")))
            store.upsert(first)
            val quiet = first.copy(notificationsEnabled = false)
            val thread = HeldThread()
            val held = async(thread) { store.update(first.id) { quiet } }
            try {
                // The held writer asks for its write, which the store takes while the writer's thread runs
                // nothing more.
                thread.run()
                runCurrent()
                assertEquals(listOf(quiet), withTimeout(WAIT_MILLIS) { store.instances.first() })
            } finally {
                thread.release()
            }
            assertTrue(held.await())
        }

    @Test
    fun `a writer of the settings whose own thread is held keeps no reader waiting`() =
        runTest {
            val settings = atomicDataStore(File(directory, "s.json"), AppSettingsSerializer, backgroundScope)
            val store = AppSettingsStore(settings)
            val chat = ChatRef(first.id, MAIN_PROFILE)
            val thread = HeldThread()
            // The app lock's switch and the navigator, each on a thread that runs nothing more once it has asked.
            val locking = async(thread) { store.setAppLock(true) }
            val leaving = async(thread) { store.setLastChat(chat) }
            try {
                thread.run()
                runCurrent()
                val read = withTimeout(WAIT_MILLIS) { store.settings.first() }
                assertEquals(AppSettings(appLock = true, lastChat = chat), read)
            } finally {
                thread.release()
            }
            locking.await()
            leaving.await()
        }
}
