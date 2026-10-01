package io.tezra.fermix.transport

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import java.net.ConnectException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * Trust is the leaf's SHA-256 against `tls_fp` and nothing else (design sections 12.2 and 12.3): the
 * trust manager throws on any other leaf, the verifier re-checks the same digest, and no certificate
 * authority grants anything.
 */
class PinnedTrustTest {
    private val daemon = daemonCertificate()
    private val other = daemonCertificate()
    private val pinned = PinnedTrust(fingerprint(daemon.certificate))
    private val connector = WebSocketConnector()

    @Test
    fun `a pin that is not 32 bytes is refused`() {
        assertThrows<IllegalArgumentException> { PinnedTrust(ByteArray(31)) }
        assertThrows<IllegalArgumentException> { PinnedTrust(ByteArray(33)) }
    }

    @Test
    fun `the trust manager accepts a chain whose leaf is the pinned certificate`() {
        pinned.trustManager.checkServerTrusted(arrayOf(daemon.certificate), AUTH_TYPE)
    }

    @Test
    fun `the trust manager throws on another leaf, an empty chain and no chain`() {
        val trustManager = pinned.trustManager
        assertThrows<CertificateException> { trustManager.checkServerTrusted(arrayOf(other.certificate), AUTH_TYPE) }
        assertThrows<CertificateException> { trustManager.checkServerTrusted(arrayOf(), AUTH_TYPE) }
        assertThrows<CertificateException> { trustManager.checkServerTrusted(null, AUTH_TYPE) }
    }

    @Test
    fun `the leaf alone is checked, so a chain whose issuer is the pinned certificate is refused`() {
        val authority = certificateAuthority()
        val leaf = signedBy(authority)
        val chain = arrayOf(leaf.certificate, authority.certificate)
        val pinnedAuthority = PinnedTrust(fingerprint(authority.certificate))
        assertThrows<CertificateException> { pinnedAuthority.trustManager.checkServerTrusted(chain, AUTH_TYPE) }
        PinnedTrust(fingerprint(leaf.certificate)).trustManager.checkServerTrusted(chain, AUTH_TYPE)
    }

    @Test
    fun `the trust manager trusts no client and accepts no issuer`() {
        val trustManager = pinned.trustManager
        assertThrows<CertificateException> { trustManager.checkClientTrusted(arrayOf(daemon.certificate), AUTH_TYPE) }
        assertEquals(0, trustManager.acceptedIssuers.size)
    }

    @Test
    fun `the pinned certificate connects and upgrades`() {
        tlsServer(daemon).use { server ->
            val side = DaemonSide()
            server.enqueue(side.upgrade())
            val connection = runBlocking { connector.open(server.candidate(), server.port, pinned) }
            assertEquals("/ws", server.takeRequest().url.encodedPath)
            connection.close()
        }
    }

    @Test
    fun `another certificate fails inside checkServerTrusted as PinMismatch`() {
        tlsServer(other).use { server ->
            val failure =
                assertThrows<TransportException.PinMismatch> {
                    runBlocking { connector.open(server.candidate(), server.port, pinned) }
                }
            assertTrue(causes(failure).any { it is PinMismatchException }, "the refusal came from checkServerTrusted")
        }
    }

    @Test
    fun `a certificate signed by a pinned authority is refused, so no authority path exists`() {
        val authority = certificateAuthority()
        val leaf = signedBy(authority)
        tlsServer(leaf, authority.certificate).use { server ->
            val trust = PinnedTrust(fingerprint(authority.certificate))
            val failure =
                assertThrows<TransportException.PinMismatch> {
                    runBlocking { connector.open(server.candidate(), server.port, trust) }
                }
            assertTrue(causes(failure).any { it is PinMismatchException }, "the refusal came from checkServerTrusted")
        }
    }

    @Test
    fun `an authority the platform trusts grants nothing, and the trust manager itself refuses its leaf`() {
        val authority = certificateAuthority()
        val leaf = signedBy(authority)
        val chain = arrayOf(leaf.certificate, authority.certificate)
        withDefaultTrustIn(authority) {
            tlsServer(leaf, authority.certificate).use { server ->
                assertTrustedByDefault(server)
                assertThrows<PinMismatchException> { pinned.trustManager.checkServerTrusted(chain, AUTH_TYPE) }
                val failure =
                    assertThrows<TransportException.PinMismatch> {
                        runBlocking { connector.open(server.candidate(), server.port, pinned) }
                    }
                val fromTrustManager = causes(failure).any { it is PinMismatchException }
                assertTrue(fromTrustManager, "the refusal came from the verifier alone")
            }
        }
    }

    @Test
    fun `the whole digest is compared, and it is the certificate's, not its key's`() {
        val session = sessionPresenting(daemon)
        val keyDigest = MessageDigest.getInstance("SHA-256").digest(daemon.certificate.publicKey.encoded)
        val nearMisses = listOf(0, 15, 31).map { pinMissingBy(it) } + listOf(keyDigest)
        val chain = arrayOf(daemon.certificate)
        nearMisses.map { PinnedTrust(it) }.forEach { trust ->
            assertThrows<PinMismatchException> { trust.trustManager.checkServerTrusted(chain, AUTH_TYPE) }
            assertFalse(trust.hostnameVerifier.verify("localhost", session))
        }
    }

    @Test
    fun `a pin refusal on one of a host's addresses is a PinMismatch whichever route failed first`() {
        // OkHttp throws the first route's failure and adds every later one to it as suppressed.
        val refusal = SSLHandshakeException("handshake failed").apply { initCause(PinMismatchException("not pinned")) }
        val firstRoute = ConnectException("refused").apply { addSuppressed(refusal) }
        assertInstanceOf<TransportException.PinMismatch>(failureOf(firstRoute, response = null))
        assertInstanceOf<TransportException.Unreachable>(failureOf(ConnectException("refused"), response = null))
    }

    @Test
    fun `the verifier's refusal is a PinMismatch, on the route that failed first or under it`() {
        // OkHttp throws SSLPeerUnverifiedException when the verifier returns false.
        val unverified = SSLPeerUnverifiedException("Hostname localhost not verified")
        assertInstanceOf<TransportException.PinMismatch>(failureOf(unverified, response = null))
        val firstRoute = ConnectException("refused").apply { addSuppressed(unverified) }
        assertInstanceOf<TransportException.PinMismatch>(failureOf(firstRoute, response = null))
    }

    @Test
    fun `the verifier passes the pinned leaf and rejects any other`() {
        val session = sessionPresenting(daemon)
        assertTrue(pinned.hostnameVerifier.verify("localhost", session))
        assertFalse(PinnedTrust(fingerprint(other.certificate)).hostnameVerifier.verify("localhost", session))
    }

    @Test
    fun `the verifier rejects a session with no peer certificate`() {
        val anonymous =
            object : SSLSession by sessionPresenting(daemon) {
                override fun getPeerCertificates(): Array<Certificate> =
                    throw SSLPeerUnverifiedException("no peer certificate")
            }
        assertFalse(pinned.hostnameVerifier.verify("localhost", anonymous))
    }

    /** A real TLS session with a server presenting [held], taken from a plain HTTPS request. */
    private fun sessionPresenting(held: HeldCertificate): SSLSession =
        tlsServer(held).use { server ->
            server.enqueue(MockResponse.Builder().build())
            var captured: SSLSession? = null
            val trust = PinnedTrust(fingerprint(held.certificate))
            val client =
                OkHttpClient
                    .Builder()
                    .sslSocketFactory(trust.socketFactory, trust.trustManager)
                    .hostnameVerifier { host, session ->
                        captured = session
                        trust.hostnameVerifier.verify(host, session)
                    }.build()
            client.newCall(Request.Builder().url(server.url("/healthz")).build()).execute().close()
            captured ?: error("the handshake gave no session")
        }

    /** The daemon's pin with one bit of its byte [index] flipped. */
    private fun pinMissingBy(index: Int): ByteArray =
        fingerprint(daemon.certificate).also { it[index] = (it[index].toInt() xor 1).toByte() }

    /**
     * Runs [block] with the JVM's default trust store holding [authority] alone, then puts back the
     * store it had. The JDK reads its default store again whenever these properties change.
     */
    private fun withDefaultTrustIn(
        authority: HeldCertificate,
        block: () -> Unit,
    ) {
        val file = Files.createTempFile("default-trust", ".p12")
        val previous = TRUST_STORE_PROPERTIES.associateWith { System.getProperty(it) }
        try {
            writeTrustStore(file, authority)
            System.setProperty("javax.net.ssl.trustStore", file.toString())
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
            System.setProperty("javax.net.ssl.trustStorePassword", STORE_PASSWORD)
            block()
        } finally {
            previous.forEach { (name, value) -> restoreProperty(name, value) }
            Files.delete(file)
        }
    }

    private fun writeTrustStore(
        file: Path,
        authority: HeldCertificate,
    ) {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setCertificateEntry("authority", authority.certificate)
        Files.newOutputStream(file).use { store.store(it, STORE_PASSWORD.toCharArray()) }
    }

    private fun restoreProperty(
        name: String,
        value: String?,
    ) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }

    /** The control: OkHttp's default client, on the JVM's default trust, accepts [server]. */
    private fun assertTrustedByDefault(server: MockWebServer) {
        server.enqueue(MockResponse.Builder().build())
        val client = OkHttpClient()
        try {
            client.newCall(Request.Builder().url(server.url("/healthz")).build()).execute().use {
                assertEquals(200, it.code, "the default trust refused the authority's leaf")
            }
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private fun certificateAuthority(): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName("Some authority")
            .certificateAuthority(0)
            .ecdsa256()
            .build()

    private fun signedBy(authority: HeldCertificate): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName("Fermix Mobile")
            .addSubjectAlternativeName("localhost")
            .signedBy(authority)
            .ecdsa256()
            .build()

    /** [failure] and every failure under it, as a cause or suppressed: one route's refusal may sit under another's. */
    private fun causes(
        failure: Throwable,
        depth: Int = 0,
    ): List<Throwable> =
        if (depth == MAX_DEPTH) {
            emptyList()
        } else {
            listOf(failure) + (listOfNotNull(failure.cause) + failure.suppressed).flatMap { causes(it, depth + 1) }
        }

    private companion object {
        const val MAX_DEPTH = 8

        /** The key exchange a TLS 1.2 handshake with the daemon's P-256 certificate names. */
        const val AUTH_TYPE = "ECDHE_ECDSA"

        const val STORE_PASSWORD = "changeit"

        val TRUST_STORE_PROPERTIES =
            listOf("javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType", "javax.net.ssl.trustStorePassword")
    }
}
