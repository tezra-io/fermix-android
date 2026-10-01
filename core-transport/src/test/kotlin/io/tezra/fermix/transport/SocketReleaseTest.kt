package io.tezra.fermix.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** How often a test looks again at a thread it waits on. */
private const val POLL_MS = 10L

/**
 * Every way a socket ends lets go of what it held: an open that was cancelled or refused closes its
 * connection, and closing a connection whose messages were left unread lets its reader thread go.
 */
class SocketReleaseTest {
    private val daemon = daemonCertificate()
    private val trust = PinnedTrust(fingerprint(daemon.certificate))
    private val connector = WebSocketConnector()

    @Test
    fun `cancelling an open that waits for the upgrade closes its connection`() {
        val requested = CountDownLatch(1)
        val script = { peer: RawPeer ->
            peer.readUpgrade()
            requested.countDown()
            peer.awaitEnd()
        }
        RawDaemon(daemon, script).use { raw ->
            runBlocking {
                val open = async(start = CoroutineStart.UNDISPATCHED) { connector.open(raw.candidate, raw.port, trust) }
                assertTrue(requested.await(WAIT_SECONDS, TimeUnit.SECONDS), "the upgrade request never came")
                open.cancel()
                assertThrows<CancellationException> { open.await() }
            }
            assertTrue(raw.awaitScript(), "the cancelled open still holds its connection")
        }
    }

    @Test
    fun `a refused open closes its connection`() {
        val script = { peer: RawPeer ->
            peer.readUpgrade()
            peer.answer("404 Not Found")
            peer.awaitEnd()
        }
        RawDaemon(daemon, script).use { raw ->
            val refused =
                assertThrows<TransportException.Refused> {
                    runBlocking { connector.open(raw.candidate, raw.port, trust) }
                }
            assertEquals(404, refused.httpCode)
            assertTrue(raw.awaitScript(), "the refused open still holds its connection")
        }
    }

    @Test
    fun `closing with messages unread lets the reader thread go`() {
        val events = SocketEvents()
        val message = byteArrayOf(1).toByteString()
        // One message more than the backlog holds, so the reader waits for room.
        val reader = thread(name = "reader") { repeat(INCOMING_BACKLOG + 1) { events.onMessage(QuietSocket, message) } }
        awaitWaiting(reader)
        Connection(QuietSocket, events).close()
        reader.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse(reader.isAlive, "the reader still waits for room in a closed connection's backlog")
    }

    /** Returns once [thread] waits, failing past [WAIT_SECONDS]. */
    private fun awaitWaiting(thread: Thread) {
        val waiting = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
        val polls = TimeUnit.SECONDS.toMillis(WAIT_SECONDS) / POLL_MS
        val parked = (1..polls).any { (thread.state in waiting).also { Thread.sleep(POLL_MS) } }
        assertTrue(parked, "the reader never waited for room")
    }

    /** A socket that takes everything and sends nothing: the connection's side, for a reader driven by hand. */
    private object QuietSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("https://localhost/ws").build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = true

        override fun send(bytes: ByteString): Boolean = true

        override fun close(
            code: Int,
            reason: String?,
        ): Boolean = true

        override fun cancel() = Unit
    }
}
