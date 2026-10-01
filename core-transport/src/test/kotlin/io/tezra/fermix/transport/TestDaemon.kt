package io.tezra.fermix.transport

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** How long a test waits for the other side of a socket before it fails. */
internal const val WAIT_SECONDS = 10L

/** A self-signed P-256 certificate, like the daemon's own (`Mobile.Identity`: "/CN=Fermix Mobile"). */
internal fun daemonCertificate(): HeldCertificate =
    HeldCertificate
        .Builder()
        .commonName("Fermix Mobile")
        .ecdsa256()
        .build()

/** What the pairing link's `tls_fp` holds for [certificate]: the SHA-256 of its DER. */
internal fun fingerprint(certificate: X509Certificate): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

/** A server that presents [held], followed by [intermediates], over TLS; the caller closes it. */
internal fun tlsServer(
    held: HeldCertificate,
    vararg intermediates: X509Certificate,
): MockWebServer {
    val certificates = HandshakeCertificates.Builder().heldCertificate(held, *intermediates).build()
    return MockWebServer().apply {
        useHttps(certificates.sslSocketFactory())
        start()
    }
}

/** The candidate a test server listens on. */
internal fun MockWebServer.candidate(): Candidate = Candidate(hostName, Candidate.Scope.LAN, Candidate.Kind.NAME)

/**
 * The daemon's side of a test WebSocket. It echoes every binary message, answers a close with 1000,
 * and records both; [onOpen] acts first, once the upgrade is done.
 */
internal class DaemonSide(
    private val onOpen: (WebSocket) -> Unit = {},
) : WebSocketListener() {
    private val messages = LinkedBlockingQueue<ByteString>()
    private val closes = LinkedBlockingQueue<Int>()

    fun upgrade(): MockResponse = MockResponse.Builder().webSocketUpgrade(this).build()

    fun nextMessage(): ByteString =
        messages.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: error("no message reached the daemon")

    fun nextClose(): Int = closes.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: error("no close reached the daemon")

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) = onOpen(webSocket)

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        messages.add(bytes)
        webSocket.send(bytes)
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        closes.add(code)
        webSocket.close(NORMAL_CLOSURE, null)
    }
}
