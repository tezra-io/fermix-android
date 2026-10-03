package io.tezra.fermix.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.CoroutineContext

private val THUMB = "cd".repeat(32)
private val BYTES = byteArrayOf(1, 2, 3, 4)

/** What in the chat's own sources and build script could reach a network, as written. */
private val NETWORK_WORDS =
    listOf(
        "okhttp",
        "java.net.URL",
        "HttpURLConnection",
        "HttpsURLConnection",
        "java.net.Socket",
        "openConnection(",
        "toURL(",
        "openStream(",
        "DownloadManager",
        "WebView",
    )

/**
 * What the chat's compiled classes could reach a network with, by the binary names a class file refers to them
 * by: however the source spells it, a call to one is in the class's constant pool. "java/net/URL" holds its
 * connections too.
 */
private val NETWORK_CLASSES =
    listOf(
        "java/net/URL",
        "java/net/Socket",
        "java/net/http/",
        "java/nio/channels/SocketChannel",
        "javax/net/",
        "okhttp3/",
        "android/webkit/",
        "android/app/DownloadManager",
        "android/net/http/",
        "org/chromium/net/",
    )

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

/** Every class file at [location], a classes directory or a jar, by its name, with its bytes. */
private fun classFiles(location: File): List<Pair<String, ByteArray>> {
    if (location.isDirectory) {
        return location
            .walkTopDown()
            .filter { it.extension == "class" }
            .map { it.name to it.readBytes() }
            .toList()
    }
    return ZipFile(location).use { jar ->
        jar
            .entries()
            .asSequence()
            .filter { it.name.endsWith(".class") }
            .map { entry -> entry.name to jar.getInputStream(entry).use { it.readBytes() } }
            .toList()
    }
}

/**
 * The chat's media and answers (design sections 8.4, 13.5 and 13.7): a preview's thumbnail comes from the media
 * cache or else through the session alone, its scratch file gone either way; the chat's own code holds no other
 * way to the network, so the phone never loads a third-party URL; an approval's answer goes through the
 * session, which holds its route; and a search hit's jump loads the page that holds it first, or waits for a
 * newer row's catch-up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a thumbnail comes through the session, is cached, and leaves no scratch file`() =
        runTest {
            val store = FakeChatStore()
            val session = FakeChatSession(store).apply { blobs = mapOf(THUMB to BYTES) }
            val scratches = mutableListOf<File>()
            val log = FakeLog()
            val scratch = { File.createTempFile("fetch", null, dir).also { scratches += it } }
            val thumbnails = ChatThumbnails(MutableStateFlow(session), store, scratch, log.log)
            assertArrayEquals(BYTES, thumbnails.thumbnail(THUMB))
            assertArrayEquals(BYTES, thumbnails.thumbnail(THUMB))
            assertEquals(listOf(THUMB), session.fetched.value, "the cached one was fetched again")
            assertTrue(scratches.none { it.exists() }, "a scratch file was left")
        }

    @Test
    fun `a ref the media cache cannot name gets no thumbnail, nothing fetched, and the log says so`() =
        runTest {
            val store = FakeChatStore()
            val upper = "CD".repeat(32)
            val session = FakeChatSession(store).apply { blobs = mapOf(upper to BYTES) }
            val log = FakeLog()
            val thumbnails =
                ChatThumbnails(MutableStateFlow(session), store, { File.createTempFile("fetch", null, dir) }, log.log)
            assertNull(thumbnails.thumbnail(upper))
            assertNull(thumbnails.thumbnail("not-a-digest"))
            assertTrue(session.fetched.value.isEmpty(), "a ref the cache cannot name was fetched")
            assertEquals(2, log.lines.value.size)
        }

    @Test
    fun `a thumbnail cancelled as its scratch file is made leaves no file`() =
        runTest {
            val store = FakeChatStore()
            val session = FakeChatSession(store).apply { blobs = mapOf(THUMB to BYTES) }
            val scratches = mutableListOf<File>()
            var asking: Job? = null
            // The fetch's first block on its io dispatcher makes the scratch file.
            val io = AfterFirstBlock(StandardTestDispatcher(testScheduler)) { checkNotNull(asking).cancel() }
            val scratch = { File.createTempFile("fetch", null, dir).also { scratches += it } }
            val thumbnails = ChatThumbnails(MutableStateFlow(session), store, scratch, FakeLog().log, io)
            asking = launch { thumbnails.thumbnail(THUMB) }
            checkNotNull(asking).join()
            assertEquals(1, scratches.size, "no scratch file was made")
            assertTrue(scratches.none { it.exists() }, "the scratch file made as its caller was cancelled was left")
            assertTrue(session.fetched.value.isEmpty())
        }

    @Test
    fun `offline or gone, there is no thumbnail and no file, and the log says why`() =
        runTest {
            val store = FakeChatStore()
            val session = FakeChatSession(store).apply { connected.value = false }
            val scratches = mutableListOf<File>()
            val log = FakeLog()
            val thumbnails =
                ChatThumbnails(MutableStateFlow(session), store, {
                    File.createTempFile("fetch", null, dir).also {
                        scratches +=
                            it
                    }
                }, log.log)
            assertNull(thumbnails.thumbnail(THUMB))
            session.connected.value = true
            assertNull(thumbnails.thumbnail(THUMB))
            assertTrue(scratches.none { it.exists() })
            assertEquals(
                listOf(
                    "Thumbnail $THUMB was not fetched: Offline",
                    "Thumbnail $THUMB was not fetched: Refused(code=media_gone)",
                ),
                log.lines.value,
            )
        }

    @Test
    fun `the chat's own code reaches no network but through its session`() {
        val sources =
            File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList() + File("build.gradle.kts")
        assertTrue(sources.size > 1, "the scan found no sources")
        val reaching = sources.filter { file -> NETWORK_WORDS.any { it in file.readText() } }.map { it.name }
        assertEquals(emptyList<String>(), reaching)
    }

    @Test
    fun `the chat's compiled code refers to no class that reaches a network, however its source spells it`() {
        val classes =
            checkNotNull(
                ChatViewModel::class.java.protectionDomain
                    ?.codeSource
                    ?.location,
            ) { "no classes" }
        val location = File(classes.toURI())
        val compiled = classFiles(location)
        assertTrue(compiled.any { it.first.endsWith("ChatViewModel.class") }, "the scan found no classes at $location")
        val reaching =
            compiled
                .filter { (_, bytes) -> NETWORK_CLASSES.any { String(bytes, Charsets.ISO_8859_1).contains(it) } }
                .map { it.first }
        assertEquals(emptyList<String>(), reaching)
    }

    @Test
    fun `an answer goes through the session, which holds the card's route`() =
        runTest {
            val session = FakeChatSession(FakeChatStore())
            val log = FakeLog()
            ChatApprovals(MutableStateFlow(session), backgroundScope, log.log).answer("ap-1", approve = false)
            runCurrent()
            assertEquals(listOf("ap-1" to false), session.answers.value)
            assertTrue(session.sent.value.isEmpty(), "the chat sent the route itself")
            assertTrue(log.lines.value.isEmpty())
        }

    @Test
    fun `a jump to a cached row grows the list to hold it`() =
        runTest {
            val store = FakeChatStore((1..90).map { agentRow(it, "row $it") })
            val limit = MutableStateFlow(PAGE_ROWS)
            val jumps =
                ChatJumps(store, MutableStateFlow(FakeChatSession(store)), limit, backgroundScope, FakeLog().log)
            jumps.to(10uL)
            runCurrent()
            assertEquals(Jump(10uL, 1), jumps.jump.value)
            assertEquals(81 + PAGE_ROWS / 2, limit.value)
        }

    @Test
    fun `a jump to a row the cache lacks loads its page first`() =
        runTest {
            val store = FakeChatStore(listOf(agentRow(50, "newest")))
            val session = FakeChatSession(store)
            val jumps =
                ChatJumps(store, MutableStateFlow(session), MutableStateFlow(PAGE_ROWS), backgroundScope, FakeLog().log)
            jumps.to(12uL)
            runCurrent()
            assertEquals(listOf(50uL), session.older.value)
            assertNull(jumps.jump.value)
            store.rows.update { (it + agentRow(12, "older")).sortedByDescending { row -> row.serverSeq } }
            advanceTimeBy(300)
            runCurrent()
            assertEquals(Jump(12uL, 1), jumps.jump.value)
            assertFalse(store.rows.value.isEmpty())
        }

    @Test
    fun `a jump to a row newer than the cache waits for the catch-up and pulls no older page`() =
        runTest {
            val store = FakeChatStore((900..1000).map { agentRow(it, "row $it") })
            val session = FakeChatSession(store)
            val log = FakeLog()
            val jumps =
                ChatJumps(store, MutableStateFlow(session), MutableStateFlow(PAGE_ROWS), backgroundScope, log.log)
            jumps.to(1005uL)
            advanceTimeBy(1_000)
            store.rows.update { (it + agentRow(1005, "row 1005")).sortedByDescending { row -> row.serverSeq } }
            advanceTimeBy(300)
            runCurrent()
            assertEquals(Jump(1005uL, 1), jumps.jump.value)
            assertTrue(log.lines.value.isEmpty())
            jumps.to(1010uL)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(emptyList<ULong>(), session.older.value, "an older page was pulled for a newer row")
            assertEquals(listOf("Row 1010 could not be loaded to jump to"), log.lines.value)
        }

    @Test
    fun `a jump far below the cache pulls every page between, so no row is skipped`() =
        runTest {
            val store = FakeChatStore((900..1000).map { agentRow(it, "row $it") })
            val session = FakeChatSession(store)
            // The daemon's older page: the 50 rows before the cursor.
            session.onOlder = { before ->
                val page = (before.toInt() - 50 until before.toInt()).map { agentRow(it, "row $it") }
                store.rows.update { (it + page).sortedByDescending { row -> row.serverSeq } }
            }
            val limit = MutableStateFlow(PAGE_ROWS)
            val jumps = ChatJumps(store, MutableStateFlow(session), limit, backgroundScope, FakeLog().log)
            jumps.to(100uL)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals((900 downTo 150 step 50).map { it.toULong() }, session.older.value)
            assertEquals(Jump(100uL, 1), jumps.jump.value)
            assertEquals(
                (100..1000).map { it.toULong() },
                store.rows.value
                    .map { it.serverSeq }
                    .sorted(),
            )
        }
}
