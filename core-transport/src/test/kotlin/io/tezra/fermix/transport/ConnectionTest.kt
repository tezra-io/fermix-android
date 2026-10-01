package io.tezra.fermix.transport

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import java.net.ProtocolException
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One WebSocket to a daemon (PROTOCOL.md "Transport" and "Close codes"): binary messages of at most
 * 65,535 bytes both ways, a text message closed with 1003, the daemon's close code and reason handed
 * on, and every way an open fails typed.
 */
class ConnectionTest {
    private val daemon = daemonCertificate()
    private val trust = PinnedTrust(fingerprint(daemon.certificate))
    private val connector = WebSocketConnector()
    private val server: MockWebServer = tlsServer(daemon)

    @AfterEach
    fun closeServer() = server.close()

    @Test
    fun `binary messages go both ways, up to 65,535 bytes`() {
        val side = DaemonSide()
        val connection = open(side)
        val largest = ByteArray(MAX_MESSAGE_BYTES) { it.toByte() }
        assertTrue(connection.send(largest))
        assertEquals(largest.toByteString(), side.nextMessage())
        assertArrayEquals(largest, receive(connection))
        connection.close()
    }

    @Test
    fun `a message over 65,535 bytes is refused before it is sent`() {
        val side = DaemonSide()
        val connection = open(side)
        assertThrows<IllegalArgumentException> { connection.send(ByteArray(MAX_MESSAGE_BYTES + 1)) }
        assertTrue(connection.send(byteArrayOf(1)))
        assertEquals(byteArrayOf(1).toByteString(), side.nextMessage())
        connection.close()
    }

    @Test
    fun `an incoming message over 65,535 bytes is not delivered and closes with 1009`() {
        val side = DaemonSide(onOpen = { it.send(ByteArray(MAX_MESSAGE_BYTES + 1).toByteString()) })
        val connection = open(side)
        assertClosed(connection, 1009, byDaemon = false)
        assertEquals(1009, side.nextClose())
        assertNull(receiveOrNull(connection))
    }

    @Test
    fun `a text message is not delivered and closes with 1003`() {
        val side = DaemonSide(onOpen = { it.send("hello") })
        val connection = open(side)
        assertClosed(connection, 1003, byDaemon = false)
        assertEquals(1003, side.nextClose())
        assertNull(receiveOrNull(connection))
    }

    @Test
    fun `the daemon's close code and reason end the connection`() {
        val side = DaemonSide(onOpen = { it.close(4001, "connection replaced") })
        val connection = open(side)
        val closed = assertClosed(connection, 4001, byDaemon = true)
        assertEquals("connection replaced", closed.reason)
        assertNull(receiveOrNull(connection))
        assertEquals(NORMAL_CLOSURE, side.nextClose(), "the daemon's close was not answered")
    }

    @Test
    fun `closing reports its own code and reaches the daemon`() {
        val side = DaemonSide()
        val connection = open(side)
        connection.close(4000, "going away")
        val closed = assertClosed(connection, 4000, byDaemon = false)
        assertEquals("going away", closed.reason)
        assertEquals(4000, side.nextClose())
        assertFalse(connection.send(byteArrayOf(1)))
    }

    @Test
    fun `an answer other than the upgrade is Refused with its status`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val refused = assertThrows<TransportException.Refused> { openUpgraded() }
        assertEquals(404, refused.httpCode)
        assertInstanceOf<ProtocolException>(refused.cause)
    }

    @Test
    fun `a 101 that is not a WebSocket upgrade is Refused, with OkHttp's reason under it`() {
        val script = { peer: RawPeer ->
            peer.readUpgrade()
            peer.answer("101 Switching Protocols")
            peer.awaitEnd()
        }
        RawDaemon(daemon, script).use { raw ->
            val refused =
                assertThrows<TransportException.Refused> {
                    runBlocking { connector.open(raw.candidate, raw.port, trust) }
                }
            assertEquals(101, refused.httpCode)
            assertInstanceOf<ProtocolException>(refused.cause)
            assertTrue(raw.awaitScript(), "the refused open still holds its connection")
        }
    }

    @Test
    fun `a daemon that accepts compression is refused with 1002`() {
        val side = DaemonSide()
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Sec-WebSocket-Extensions", "permessage-deflate")
                .webSocketUpgrade(side)
                .build(),
        )
        val closed = assertThrows<TransportException.Closed> { openUpgraded() }
        assertEquals(1002, closed.code)
        assertFalse(closed.byDaemon)
    }

    @Test
    fun `a message whose fragments pass 65,535 bytes together is not delivered and closes with 1009`() {
        // Each fragment is within the bound; the message they make is not.
        val fragment = ByteArray(40_000)
        val script = { peer: RawPeer ->
            peer.acceptUpgrade(peer.readUpgrade())
            peer.writeFrame(fin = false, OPCODE_BINARY, fragment)
            peer.writeFrame(fin = true, OPCODE_CONTINUATION, fragment)
            peer.readCloseCode()
        }
        RawDaemon(daemon, script).use { raw ->
            val connection = runBlocking { connector.open(raw.candidate, raw.port, trust) }
            assertClosed(connection, 1009, byDaemon = false)
            assertNull(receiveOrNull(connection))
            assertEquals(1009, raw.awaitScript())
        }
    }

    @Test
    fun `a daemon that stops reading shows in the queued bytes, and past OkHttp's 16 MiB a send is refused`() {
        val done = CountDownLatch(1)
        val script = { peer: RawPeer ->
            peer.acceptUpgrade(peer.readUpgrade())
            done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }
        RawDaemon(daemon, script).use { raw ->
            val connection = runBlocking { connector.open(raw.candidate, raw.port, trust) }
            val message = ByteArray(MAX_MESSAGE_BYTES)
            val queued = (1..MAX_SENDS).takeWhile { connection.send(message) }.size
            assertTrue(queued < MAX_SENDS, "$MAX_SENDS sends were all queued")
            val held = connection.queuedBytes
            assertTrue(held > OKHTTP_QUEUE_BYTES - 2 * MAX_MESSAGE_BYTES, "$held bytes queued")
            assertFalse(connection.send(byteArrayOf(1)))
            done.countDown()
            connection.close()
        }
    }

    @Test
    fun `a candidate nothing listens on is Unreachable`() {
        val port = ServerSocket(0).use { it.localPort }
        assertThrows<TransportException.Unreachable> { runBlocking { connector.open(server.candidate(), port, trust) } }
    }

    @Test
    fun `a port outside 1 to 65,535 is refused`() {
        assertThrows<IllegalArgumentException> { runBlocking { connector.open(server.candidate(), 0, trust) } }
        assertThrows<IllegalArgumentException> { runBlocking { connector.open(server.candidate(), 65_536, trust) } }
    }

    @Test
    fun `the client is pinned, direct, HTTP 1_1 over TLS 1_3 or 1_2, and leaves retries and keepalive to the app`() {
        val client = pinnedClient(OkHttpClient(), trust)
        assertEquals(trust.trustManager, client.x509TrustManager)
        assertEquals(trust.hostnameVerifier, client.hostnameVerifier)
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertEquals(1, client.connectionSpecs.size)
        val spec = client.connectionSpecs.single()
        assertTrue(spec.isTls)
        assertEquals(listOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2), spec.tlsVersions)
        assertEquals(ConnectionSpec.RESTRICTED_TLS.cipherSuites, spec.cipherSuites)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertEquals(0, client.pingIntervalMillis)
        assertEquals(0, client.readTimeoutMillis)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(25_000, client.callTimeoutMillis)
    }

    private fun open(side: DaemonSide): Connection {
        server.enqueue(side.upgrade())
        return openUpgraded()
    }

    /** Opens the test server's WebSocket, whatever answer it has queued. */
    private fun openUpgraded(): Connection = runBlocking { connector.open(server.candidate(), server.port, trust) }

    private fun receive(connection: Connection): ByteArray = receiveOrNull(connection) ?: error("the connection ended")

    /** The next incoming message, or null once the connection's messages have ended. */
    private fun receiveOrNull(connection: Connection): ByteArray? =
        runBlocking { withTimeout(WAIT_SECONDS * 1_000) { connection.incoming.receiveCatching().getOrNull() } }

    /** Waits for [connection] to end, and checks it ended with [code], sent by the daemon or by this side. */
    private fun assertClosed(
        connection: Connection,
        code: Int,
        byDaemon: Boolean,
    ): TransportException.Closed {
        val end = runBlocking { withTimeout(WAIT_SECONDS * 1_000) { connection.closed.await() } }
        val closed = assertInstanceOf<TransportException.Closed>(end)
        assertEquals(code, closed.code)
        assertEquals(byDaemon, closed.byDaemon, "who sent the close")
        return closed
    }

    private companion object {
        /** OkHttp's outgoing queue, past which it closes the socket with 1001. */
        const val OKHTTP_QUEUE_BYTES = 16L * 1024 * 1024

        /** 64 MiB, past what the queue and the socket's buffers hold together: a send is refused well before. */
        const val MAX_SENDS = 1_024
    }
}
