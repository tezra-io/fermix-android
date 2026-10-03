package io.tezra.fermix.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The chat row a database's creation writes. */
private val FRESH_CHAT = ChatState(draft = null, agentName = null, previews = true)

/**
 * An instance's readers against its removal (ProfileDatabases): Room ends no flow as its database closes, so
 * a removal ends the readers first and deletes the files only once each has ended, and nothing opens a
 * removed instance's files again until a pairing brings its daemon back. The readers and Room run on a
 * dispatcher of their own, on a scheduler the test advances by hand; the records' writes and the removal run
 * on the test's. An exception that reaches a reader's scope is recorded: the app would crash on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadersAndRemovalTest {
    @TempDir
    lateinit var directory: File

    private val root: File get() = File(directory, "instances")

    private val record = instance(gateway = 1)

    private val readerTime = TestCoroutineScheduler()
    private val queries = StandardTestDispatcher(readerTime)
    private val reported = mutableListOf<Throwable>()
    private val readers =
        CoroutineScope(queries + SupervisorJob() + CoroutineExceptionHandler { _, failure -> reported += failure })

    @AfterEach
    fun readersEnd() = readers.cancel()

    private fun TestScope.open(): Pair<InstanceStore, ProfileDatabases> {
        val databases = ProfileDatabases(TestContext, root, queries)
        val records = instanceDataStore(File(directory, "instances.json"), backgroundScope)
        return InstanceStore(records, databases, StandardTestDispatcher(testScheduler)) to databases
    }

    /** Reads [id]'s chat as the Chats list and the Instance screen do, each value into [values]. */
    private fun read(
        databases: ProfileDatabases,
        id: String,
        values: MutableList<ChatState>,
    ): Job = readers.launch { databases.observe(id, MAIN_PROFILE) { it.chat().state() }.collect { values += it } }

    /**
     * [database]'s chat, read as [read] reads it, but a cancelled reading ends only once [ending] completes and
     * one last query has run, into [lastRead]: a query still running as its reader is cancelled.
     */
    private fun lingering(
        database: ProfileDatabase,
        ending: CompletableDeferred<Unit>,
        lastRead: MutableList<ChatState>,
    ): Flow<ChatState> =
        flow {
            try {
                emitAll(database.chat().state())
            } finally {
                withContext(NonCancellable) {
                    ending.await()
                    lastRead += database.chat().state().first()
                }
            }
        }

    @Test
    fun `Room ends no flow as its database closes, as one reading hangs on and one whose query comes after fails`() =
        runTest {
            val databases = ProfileDatabases(TestContext, root, queries)
            val reading = databases.open(idOf(key(1)), MAIN_PROFILE)
            val values = mutableListOf<ChatState>()
            val live = readers.launch { reading.chat().state().collect { values += it } }
            readerTime.advanceUntilIdle()
            reading.close()
            readerTime.advanceUntilIdle()
            assertTrue(live.isActive, "the flow ended with its database")
            assertEquals(listOf(FRESH_CHAT), values)
            assertEquals(emptyList<Throwable>(), reported)

            // A suspend call after the close is cancelled, which cancels its caller with no exception reported. A
            // launch, as an async's failure would never reach the handler.
            val write = readers.launch { reading.chat().setDraft("too late") }
            val ended = CompletableDeferred<Throwable?>()
            write.invokeOnCompletion { ended.complete(it) }
            readerTime.advanceUntilIdle()
            assertTrue(write.isCancelled, "the write after the close did not end its caller")
            assertInstanceOf(CancellationException::class.java, ended.getCompleted())
            assertEquals(emptyList<Throwable>(), reported)

            val unread = databases.open(idOf(key(3)), MAIN_PROFILE)
            val late = readers.launch { unread.chat().state().collect { error("a closed database gave $it") } }
            unread.close()
            readerTime.advanceUntilIdle()
            assertTrue(late.isCancelled)
            val failure = reported.single()
            assertInstanceOf(IllegalStateException::class.java, failure)
            assertEquals("Database is closed", failure.message)
        }

    @Test
    fun `a reader holding an instance's database ends as the instance is removed, and the files go only then`() =
        runTest {
            val (store, databases) = open()
            store.upsert(record)
            val values = mutableListOf<ChatState>()
            val reader = read(databases, record.id, values)
            readerTime.advanceUntilIdle()
            assertEquals(listOf(FRESH_CHAT), values)

            val removal = launch { store.remove(record.id) }
            runCurrent()
            assertFalse(removal.isCompleted, "the removal went on while a reader held the database")
            assertTrue(File(root, record.id).isDirectory, "the files went while a reader held them")
            readerTime.advanceUntilIdle()
            assertTrue(reader.isCompleted && !reader.isCancelled, "the reader did not end by itself")
            runCurrent()
            assertTrue(removal.isCompleted, "the removal did not go on once its reader ended")
            assertFalse(File(root, record.id).exists(), "the removed instance's files are still there")
            assertEquals(emptyList<Throwable>(), reported)
            assertEquals(listOf(FRESH_CHAT), values)
        }

    @Test
    fun `a reading the removal cancels holds it until its last query has ended, and only then do the files go`() =
        runTest {
            val (store, databases) = open()
            store.upsert(record)
            val ending = CompletableDeferred<Unit>()
            val lastRead = mutableListOf<ChatState>()
            val reader =
                readers.launch {
                    databases.observe(record.id, MAIN_PROFILE) { lingering(it, ending, lastRead) }.collect()
                }
            readerTime.advanceUntilIdle()

            val removal = launch { store.remove(record.id) }
            runCurrent()
            readerTime.advanceUntilIdle()
            runCurrent()
            assertFalse(removal.isCompleted, "the removal went on while the reading had not ended")
            assertTrue(File(root, record.id).isDirectory, "the files went while the reading had not ended")
            ending.complete(Unit)
            readerTime.advanceUntilIdle()
            runCurrent()
            assertTrue(reader.isCompleted && !reader.isCancelled, "the reader did not end by itself")
            assertTrue(removal.isCompleted, "the removal did not go on once the reading ended")
            assertFalse(File(root, record.id).exists(), "the removed instance's files are still there")
            assertEquals(emptyList<Throwable>(), reported)
            assertEquals(listOf(FRESH_CHAT), lastRead)
        }

    @Test
    fun `a reader whose first query is let go just as the removal starts ends with nothing reported, files there`() =
        runTest {
            val (store, databases) = open()
            store.upsert(record)
            val go = CompletableDeferred<Unit>()
            val reader =
                readers.launch {
                    databases
                        .observe(record.id, MAIN_PROFILE) { database ->
                            flow {
                                go.await()
                                emitAll(database.chat().state())
                            }
                        }.collect()
                }
            readerTime.advanceUntilIdle()

            // Room's first query is due on the readers' dispatcher as the removal starts on the test's.
            go.complete(Unit)
            val removal = launch { store.remove(record.id) }
            runCurrent()
            assertFalse(removal.isCompleted, "the removal went on while the reader's query was due")
            assertTrue(File(root, record.id).isDirectory, "the files went while the reader's query was due")
            readerTime.advanceUntilIdle()
            assertEquals(emptyList<Throwable>(), reported)
            assertTrue(reader.isCompleted && !reader.isCancelled, "the reader did not end by itself")
            runCurrent()
            assertTrue(removal.isCompleted, "the removal did not go on once its reader ended")
            assertFalse(File(root, record.id).exists(), "the removed instance's files are still there")
        }

    @Test
    fun `a reader that has not read yet as its instance is removed ends with no value, and makes no file`() =
        runTest {
            val (store, databases) = open()
            store.upsert(record)
            val values = mutableListOf<ChatState>()
            val reader = read(databases, record.id, values)
            store.remove(record.id)
            readerTime.advanceUntilIdle()
            assertTrue(reader.isCompleted && !reader.isCancelled, "the reader did not end by itself")
            assertEquals(emptyList<ChatState>(), values)
            assertFalse(File(root, record.id).exists(), "a reader made the removed instance's files again")
            assertEquals(emptyList<Throwable>(), reported)
        }

    @Test
    fun `a reader holding a dropped instance's database ends as the launch check drops it, and the files go then`() =
        runTest {
            val (store, databases) = open()
            store.upsert(record)
            val values = mutableListOf<ChatState>()
            val reader = read(databases, record.id, values)
            readerTime.advanceUntilIdle()

            // The restore left no key under the record's alias.
            val check = async { launchCheck(store) { false } }
            runCurrent()
            assertFalse(check.isCompleted, "the drop went on while a reader held the database")
            assertTrue(File(root, record.id).isDirectory, "the files went while a reader held them")
            readerTime.advanceUntilIdle()
            assertTrue(reader.isCompleted && !reader.isCancelled, "the reader did not end by itself")
            runCurrent()
            assertEquals(listOf(record), check.await())
            assertFalse(File(root, record.id).exists(), "the dropped instance's files are still there")
            assertEquals(emptyList<Throwable>(), reported)
            assertEquals(listOf(FRESH_CHAT), values)
        }

    @Test
    fun `a use of the database holds the removal until it returns, and a pairing of the daemon meanwhile is refused`() =
        runTest {
            val (store, databases) = open()
            store.upsert(record)
            val written = CompletableDeferred<Unit>()
            val use =
                readers.async {
                    databases.withDatabase(record.id, MAIN_PROFILE) { database ->
                        written.await()
                        database.chat().setDraft("half a thought")
                        database.chat().state().first()
                    }
                }
            readerTime.advanceUntilIdle()

            val removal = launch { store.remove(record.id) }
            runCurrent()
            assertFalse(removal.isCompleted, "the removal went on while a use held the database")
            assertTrue(File(root, record.id).isDirectory, "the files went while a use held them")
            assertThrows<IllegalStateException> { databases.admit(record.id) }
            written.complete(Unit)
            readerTime.advanceUntilIdle()
            runCurrent()
            // A write on a closed database would cancel the use (Room's close above), not fail it.
            assertTrue(use.isCompleted && !use.isCancelled, "the use did not return")
            assertEquals("half a thought", use.getCompleted().draft)
            assertTrue(removal.isCompleted, "the removal did not go on once the use returned")
            assertFalse(File(root, record.id).exists(), "the removed instance's files are still there")
            assertEquals(emptyList<Throwable>(), reported)
        }

    @Test
    fun `an instance removed in this process is refused, typed, with nothing made, until a pairing brings it back`() =
        runTest {
            val (store, databases) = open()
            store.upsert(record)
            databases.open(record.id, MAIN_PROFILE)
            store.remove(record.id)
            val refused = assertThrows<InstanceGone> { databases.open(record.id, MAIN_PROFILE) }
            assertEquals(record.id, refused.instanceId)
            assertFalse(File(root, record.id).exists(), "the refused open made the removed instance's files again")

            // The same daemon paired again: the pairing's write lets its files be made anew.
            store.upsert(record)
            databases.open(record.id, MAIN_PROFILE)
            assertTrue(File(root, record.id).isDirectory)
            store.remove(record.id)
        }

    @Test
    fun `a reader that never lets go fails the removal loud after the wait, and the files stay for the next launch`() =
        runTest {
            val (store, databases) = open()
            store.upsert(record)
            val reader = read(databases, record.id, mutableListOf())
            readerTime.advanceUntilIdle()

            // The reader's dispatcher never runs again while the removal waits.
            val removal = async { runCatching { store.remove(record.id) }.exceptionOrNull() }
            advanceTimeBy(RELEASE_WAIT_MILLIS - 1)
            runCurrent()
            assertFalse(removal.isCompleted, "the removal gave up before its wait ran out")
            advanceTimeBy(2)
            val failure = removal.await()
            assertInstanceOf(IllegalStateException::class.java, failure)
            assertTrue(failure?.message.orEmpty().contains(record.id), "${failure?.message}")
            assertTrue(File(root, record.id).isDirectory, "the files went while a reader held them")
            assertEquals(emptyList<Instance>(), store.instances.first())
            assertThrows<InstanceGone> { databases.open(record.id, MAIN_PROFILE) }

            readerTime.advanceUntilIdle()
            assertTrue(reader.isCompleted && !reader.isCancelled, "the reader did not end once its dispatcher ran")
            assertEquals(emptyList<Throwable>(), reported)
            // The next try, as the next launch's check would make, finds no reader and deletes the files.
            databases.delete(record.id)
            assertFalse(File(root, record.id).exists(), "the files stayed once the stuck reader had ended")
        }
}
