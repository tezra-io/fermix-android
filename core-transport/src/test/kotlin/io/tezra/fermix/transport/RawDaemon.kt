package io.tezra.fermix.transport

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** RFC 6455's GUID, which a server appends to the client's key to accept its upgrade. */
private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/** The most lines an upgrade request is read for. */
private const val MAX_REQUEST_LINES = 64

/** The most reads a wait for the client's end takes; past them the client still holds the connection. */
private const val MAX_READS = 1_024

private const val FIN = 0x80
private const val OPCODE_BITS = 0x0f
private const val LENGTH_BITS = 0x7f
private const val LENGTH_16 = 126
private const val MASK_BYTES = 4L
private const val OPCODE_CLOSE = 0x8
internal const val OPCODE_CONTINUATION = 0x0
internal const val OPCODE_BINARY = 0x2

/**
 * A TLS server presenting [held] that speaks the WebSocket by hand, for what mockwebserver3 cannot do:
 * hold back the upgrade, split a message into fragments, or stop reading. It serves one connection
 * with [script] on a thread of its own, every read bounded by [WAIT_SECONDS]; the caller closes it.
 */
internal class RawDaemon<R>(
    held: HeldCertificate,
    private val script: (RawPeer) -> R,
) : AutoCloseable {
    private val server: ServerSocket =
        HandshakeCertificates
            .Builder()
            .heldCertificate(held)
            .build()
            .sslContext()
            .serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val outcome = CompletableFuture<R>()
    private val serving = thread(name = "raw daemon") { serveOne() }

    val candidate =
        Candidate(checkNotNull(server.inetAddress.hostAddress), Candidate.Scope.LAN, Candidate.Kind.IP)
    val port: Int = server.localPort

    /** What the script returned, once it has; what it threw, if it did. */
    fun awaitScript(): R = outcome.get(WAIT_SECONDS, TimeUnit.SECONDS)

    override fun close() {
        server.close()
        serving.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
    }

    private fun serveOne() {
        try {
            server.accept().use { socket ->
                socket.soTimeout = TimeUnit.SECONDS.toMillis(WAIT_SECONDS).toInt()
                outcome.complete(script(RawPeer(socket)))
            }
        } catch (failure: Throwable) {
            outcome.completeExceptionally(failure)
        }
    }
}

/** The daemon's side of one raw connection: what a [RawDaemon] script reads and writes. */
internal class RawPeer(
    socket: Socket,
) {
    private val source = socket.source().buffer()
    private val sink = socket.sink().buffer()

    /** Reads the upgrade request, and returns its Sec-WebSocket-Key. */
    fun readUpgrade(): String {
        val lines =
            generateSequence { source.readUtf8LineStrict() }
                .take(MAX_REQUEST_LINES)
                .takeWhile { it.isNotEmpty() }
                .toList()
        val key = lines.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
        return key?.substringAfter(':')?.trim() ?: error("the upgrade request has no Sec-WebSocket-Key")
    }

    /** Accepts the upgrade whose request carried [key], with no extension. */
    fun acceptUpgrade(key: String) {
        val accept = (key + WEBSOCKET_GUID).encodeUtf8().sha1().base64()
        sink
            .writeUtf8("HTTP/1.1 101 Switching Protocols\r\n")
            .writeUtf8("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            .writeUtf8("Sec-WebSocket-Accept: $accept\r\n\r\n")
            .flush()
    }

    /** Answers [status] where the upgrade was due. */
    fun answer(status: String) {
        sink.writeUtf8("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n").flush()
    }

    /** Writes one frame of 126 to 65,535 payload bytes, unmasked, as a server does. */
    fun writeFrame(
        fin: Boolean,
        opcode: Int,
        payload: ByteArray,
    ) {
        require(payload.size in LENGTH_16..MAX_MESSAGE_BYTES) { "a ${payload.size}-byte frame takes a 16-bit length" }
        sink
            .writeByte(if (fin) FIN or opcode else opcode)
            .writeByte(LENGTH_16)
            .writeShort(payload.size)
            .write(payload)
            .flush()
    }

    /** Reads the client's close frame, masked as a client's is, and returns its code. */
    fun readCloseCode(): Int {
        val opcode = source.readByte().toInt() and OPCODE_BITS
        check(opcode == OPCODE_CLOSE) { "a frame of opcode $opcode where a close was due" }
        val length = source.readByte().toInt() and LENGTH_BITS
        val mask = source.readByteArray(MASK_BYTES)
        val payload = source.readByteArray(length.toLong())
        val high = (payload[0].toInt() xor mask[0].toInt()) and 0xff
        val low = (payload[1].toInt() xor mask[1].toInt()) and 0xff
        return (high shl Byte.SIZE_BITS) or low
    }

    /**
     * Reads to the end of the stream: true once the client has let the connection go, by a close or a
     * reset; false while it still holds it after [WAIT_SECONDS], or kept writing past [MAX_READS] reads.
     */
    fun awaitEnd(): Boolean =
        try {
            (1..MAX_READS).any { source.exhausted().also { source.buffer.clear() } }
        } catch (failed: IOException) {
            failed !is SocketTimeoutException
        }
}
