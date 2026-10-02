package io.tezra.fermix.attest

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.NamedParameterSpec
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * What the phone checks of its attestation chain before any socket opens (design section 6.2, check 1,
 * and section 7's `pair_request.attestation` row): one to six certificates, 16 KiB at most, each one
 * DER SEQUENCE, and a leaf whose SubjectPublicKeyInfo is id-X25519 with the device key's 32 bytes.
 */
class ChainTest {
    private val key = x25519PublicKey()
    private val intermediate = certificate(ecSpki())

    @Test
    fun `a leaf of the device key and its issuers pass`() {
        assertDoesNotThrow { Chain.validateShape(listOf(leafOf(key), intermediate, intermediate), key) }
        assertDoesNotThrow { Chain.validateShape(listOf(leafOf(key)), key) }
    }

    @Test
    fun `the assembled leaf is a certificate the JDK reads as X dot 509, carrying the X25519 key`() {
        val leaf = leafOf(key)
        val read = CertificateFactory.getInstance("X.509").generateCertificate(leaf.inputStream()) as X509Certificate
        assertEquals("XDH", read.publicKey.algorithm)
        assertArrayEquals(x25519Spki(key), read.publicKey.encoded)
        assertEquals(3, read.version)
    }

    @Test
    fun `the SubjectPublicKeyInfo is found in every certificate the JDK trusts`() {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val roots =
            factory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .single()
                .acceptedIssuers
        assertTrue(roots.size > 10, "the JDK trusts ${roots.size} roots")
        roots.forEach { root ->
            assertArrayEquals(
                root.publicKey.encoded,
                subjectPublicKeyInfo(root.encoded, 0),
                root.subjectX500Principal.name,
            )
        }
    }

    @Test
    fun `a chain of none or of more than six certificates is refused`() {
        assertEquals(
            0,
            assertThrows<ChainShapeException.CertificateCount> { Chain.validateShape(emptyList(), key) }.count,
        )
        val seven = listOf(leafOf(key)) + List(6) { intermediate }
        assertEquals(7, assertThrows<ChainShapeException.CertificateCount> { Chain.validateShape(seven, key) }.count)
        assertDoesNotThrow { Chain.validateShape(seven.dropLast(1), key) }
    }

    @Test
    fun `a chain past 16 KiB is refused, and one of exactly 16 KiB passes`() {
        val leaf = leafOf(key)
        val spki = ecSpki()
        // Past a few hundred bytes every length takes two bytes, so a certificate grows as its extension does.
        val overhead = certificate(spki, extensionBytes = 1_000).size - 1_000
        val rest = 16_384 - leaf.size - intermediate.size - overhead
        val exact = listOf(leaf, intermediate, certificate(spki, extensionBytes = rest))
        assertEquals(16_384, exact.sumOf { it.size })
        assertDoesNotThrow { Chain.validateShape(exact, key) }
        val over = listOf(leaf, intermediate, certificate(spki, extensionBytes = rest + 1))
        assertEquals(16_385L, assertThrows<ChainShapeException.TooLarge> { Chain.validateShape(over, key) }.bytes)
    }

    @Test
    fun `a certificate that is not one DER SEQUENCE is refused by its place in the chain`() {
        val leaf = leafOf(key)
        val broken =
            listOf(
                ByteArray(0),
                byteArrayOf(0x30),
                intermediate + byteArrayOf(0),
                intermediate.copyOf(intermediate.size - 1),
                byteArrayOf(0x31) + intermediate.copyOfRange(1, intermediate.size),
                // Indefinite length, which BER allows and DER does not.
                byteArrayOf(0x30, 0x80.toByte(), 0, 0),
                // A length in two bytes where one would do.
                byteArrayOf(0x30, 0x82.toByte(), 0, 2, 5, 0),
            )
        broken.forEach { certificate ->
            val refusal =
                assertThrows<ChainShapeException.NotDer> { Chain.validateShape(listOf(leaf, certificate), key) }
            assertEquals(1, refusal.index)
        }
    }

    @Test
    fun `a leaf whose SubjectPublicKeyInfo is not the device key is refused`() {
        val other = x25519PublicKey()
        assertThrows<ChainShapeException.LeafKeyMismatch> { Chain.validateShape(listOf(leafOf(other)), key) }
        assertThrows<ChainShapeException.LeafKeyMismatch> { Chain.validateShape(listOf(intermediate), key) }
        // The device key in the second certificate proves nothing: the leaf is the one attested.
        assertThrows<ChainShapeException.LeafKeyMismatch> {
            Chain.validateShape(
                listOf(intermediate, leafOf(key)),
                key,
            )
        }
    }

    @Test
    fun `a leaf without the fields before its key is refused as no DER certificate`() {
        val bare = der(0x30, der(0x30, x25519Spki(key)), der(0x30), der(0x03, byteArrayOf(0)))
        assertEquals(0, assertThrows<ChainShapeException.NotDer> { Chain.validateShape(listOf(bare), key) }.index)
    }

    @Test
    fun `a device key that is not 32 bytes is a caller's error`() {
        assertThrows<IllegalArgumentException> { Chain.validateShape(listOf(leafOf(key)), ByteArray(31)) }
    }

    private fun x25519PublicKey(): ByteArray {
        val generator = KeyPairGenerator.getInstance("XDH")
        generator.initialize(NamedParameterSpec.X25519)
        val encoded = generator.generateKeyPair().public.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    private fun ecSpki(): ByteArray =
        KeyPairGenerator
            .getInstance("EC")
            .generateKeyPair()
            .public.encoded
}
