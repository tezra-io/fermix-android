package io.tezra.fermix.transport

import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** `tls_fp`: the SHA-256 of the daemon's certificate, DER (PROTOCOL.md "Pairing link"). */
private const val TLS_FP_BYTES = 32

/**
 * One daemon instance's TLS trust: its certificate, and nothing else (design sections 12.2 and 12.3).
 * The [trustManager] throws unless the SHA-256 of the leaf, `chain[0]`, is [tlsFp]; it never consults
 * a certificate authority, and accepts no issuer. The [hostnameVerifier] re-checks the same digest on
 * the session's peer certificate, since the daemon's certificate names no host the phone dials. Never
 * a CertificatePinner, which pins inside a chain a CA already trusts, nor a Network Security Config
 * pin-set, which is fixed at build time. Trust in the daemon itself lives in Noise; this only keeps
 * the WebSocket on the machine the pairing link named.
 */
class PinnedTrust(
    tlsFp: ByteArray,
) {
    private val pin: ByteArray

    init {
        require(tlsFp.size == TLS_FP_BYTES) { "tls_fp is ${tlsFp.size} bytes, not $TLS_FP_BYTES" }
        pin = tlsFp.copyOf()
    }

    val trustManager: X509TrustManager =
        object : X509TrustManager {
            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) {
                val leaf = chain?.firstOrNull() ?: throw PinMismatchException("the server presented no certificate")
                if (!isPinned(leaf)) throw PinMismatchException("the server's certificate is not the one tls_fp pins")
            }

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ): Unit = throw CertificateException("this trust manager trusts the daemon's certificate, never a client's")

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

    val hostnameVerifier: HostnameVerifier = HostnameVerifier { _, session -> presentsPin(session) }

    /** A TLS context whose only trust is [trustManager]; it presents no client certificate. */
    internal val socketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").run {
            init(null, arrayOf(trustManager), null)
            socketFactory
        }

    private fun isPinned(certificate: Certificate): Boolean =
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(certificate.encoded), pin)

    /**
     * A session with no peer certificate is refused. HostnameVerifier refuses with false and nothing
     * else; OkHttp then fails the connection with SSLPeerUnverifiedException, typed a PinMismatch.
     */
    private fun presentsPin(session: SSLSession): Boolean {
        val leaf =
            try {
                session.peerCertificates.firstOrNull()
            } catch (expected: SSLPeerUnverifiedException) {
                null
            }
        return leaf != null && isPinned(leaf)
    }
}

/** The trust manager's refusal of a leaf that is not pinned, or of no leaf at all. */
internal class PinMismatchException(
    message: String,
) : CertificateException(message)
