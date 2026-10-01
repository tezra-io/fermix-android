package io.tezra.fermix.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.security.KeyPairGenerator
import javax.crypto.KeyAgreement
import kotlin.experimental.xor

/** The handshake's order, its bounds and its refusals, beyond what the vectors pin. */
class InitiatorHandshakeTest {
    private val ik = vector("ik")

    @ParameterizedTest
    @MethodSource("io.tezra.fermix.noise.NoiseVectorTest#vectors")
    fun `the prelude is on the wire once, at the start of message 1`(vector: NoiseVector) {
        val mode: Byte = if (vector.pattern == "ik") 0x01 else 0x02
        val prelude = "FXM1".encodeToByteArray() + mode
        val handshake = initiatorFor(vector)
        val first = handshake.writeFirstMessage(vector.first.payload)
        val session = handshake.readSecondMessage(vector.second.wire).session
        val sent = listOf(first) + vector.transport.map { session.encrypt(it.plaintext) }
        assertHex(prelude, first.copyOfRange(0, prelude.size))
        assertEquals(1, sent.sumOf { occurrences(prelude, it) })
    }

    @Test
    fun `a step out of order is refused`() {
        val handshake = initiatorFor(ik)
        assertThrows<NoiseException.OutOfOrder> { handshake.readSecondMessage(ik.second.wire) }
        handshake.writeFirstMessage(ik.first.payload)
        assertThrows<NoiseException.OutOfOrder> { handshake.writeFirstMessage(ik.first.payload) }
        handshake.readSecondMessage(ik.second.wire)
        assertThrows<NoiseException.OutOfOrder> { handshake.readSecondMessage(ik.second.wire) }
        assertThrows<NoiseException.OutOfOrder> { handshake.writeFirstMessage(ik.first.payload) }
    }

    @Test
    fun `a message 2 that fails authentication ends the handshake`() {
        val handshake = initiatorFor(ik)
        handshake.writeFirstMessage(ik.first.payload)
        val tampered =
            ik.second.wire
                .copyOf()
                .also { it[it.lastIndex] = it[it.lastIndex] xor 1 }
        assertThrows<NoiseException.AuthenticationFailed> { handshake.readSecondMessage(tampered) }
        assertThrows<NoiseException.UsedAfterFailure> { handshake.readSecondMessage(ik.second.wire) }
    }

    @Test
    fun `a responder key or a PSK of the wrong length is refused`() {
        val static = SoftwareX25519Key(ik.initStaticPrivate)
        val ephemeral = SoftwareX25519Key(ik.initEphemeralPrivate).keyPair()
        val shortKey =
            assertThrows<NoiseException.BadKeyLength> { InitiatorHandshake.ik(static, ByteArray(31), ephemeral) }
        assertEquals(31, shortKey.size)
        val longPsk =
            assertThrows<NoiseException.BadKeyLength> {
                InitiatorHandshake.ikpsk2(static, ik.respStaticPublic, ByteArray(33), ephemeral)
            }
        assertEquals(33, longPsk.size)
    }

    @Test
    fun `a static key whose public key is not 32 bytes is refused before any message`() {
        val wrongStatic =
            object : StaticKey {
                override val publicKey = ByteArray(33)

                override fun agree(peerPublicKey: ByteArray): ByteArray = error("a refused key is never asked to agree")
            }
        val ephemeral = SoftwareX25519Key(ik.initEphemeralPrivate).keyPair()
        val forIk =
            assertThrows<NoiseException.BadKeyLength> {
                InitiatorHandshake.ik(wrongStatic, ik.respStaticPublic, ephemeral)
            }
        assertEquals(33, forIk.size)
        val forIkpsk2 =
            assertThrows<NoiseException.BadKeyLength> {
                InitiatorHandshake.ikpsk2(wrongStatic, ik.respStaticPublic, ByteArray(32), ephemeral)
            }
        assertEquals(33, forIkpsk2.size)
    }

    @Test
    fun `message 1, its prelude included, fits one 65,535-byte WebSocket message and no more`() {
        val handshake = initiatorFor(ik)
        val refusal = assertThrows<NoiseException.MessageTooLarge> { handshake.writeFirstMessage(ByteArray(65_435)) }
        assertEquals(65_434, refusal.max)
        // The 5 prelude bytes, e 32, s 48, then the payload and its tag 16: one WebSocket message.
        assertEquals(65_535, handshake.writeFirstMessage(ByteArray(65_434)).size)
    }

    @ParameterizedTest
    @MethodSource("io.tezra.fermix.noise.NoiseVectorTest#vectors")
    fun `the daemon's empty payloads give a 101-byte message 1 and a 48-byte message 2 that completes`(
        vector: NoiseVector,
    ) {
        val handshake = initiatorFor(vector)
        val responder = responderFor(vector)
        val first = handshake.writeFirstMessage(ByteArray(0))
        assertEquals(101, first.size)
        assertEquals(0, responder.readFirst(first).size)
        val second = responder.writeSecond(ByteArray(0))
        assertEquals(48, second.size)
        val result = handshake.readSecondMessage(second)
        assertEquals(0, result.payload.size)
        val (receive, _) = responder.split()
        val sealed = result.session.encrypt(vector.first.payload)
        assertHex(vector.first.payload, receive.decryptWithAd(ByteArray(0), sealed))
    }

    @ParameterizedTest
    @MethodSource("io.tezra.fermix.noise.NoiseVectorTest#vectors")
    fun `a low-order responder ephemeral in message 2 is refused as one and ends the handshake`(vector: NoiseVector) {
        // u = 0 and u = 1, two of X25519's low-order points, each followed by a tag's worth of bytes.
        listOf(0, 1).forEach { u ->
            val handshake = initiatorFor(vector)
            handshake.writeFirstMessage(vector.first.payload)
            val lowOrder = ByteArray(KEY_BYTES + TAG_BYTES).also { it[0] = u.toByte() }
            assertThrows<NoiseException.LowOrderKey> { handshake.readSecondMessage(lowOrder) }
            assertThrows<NoiseException.UsedAfterFailure> { handshake.readSecondMessage(vector.second.wire) }
        }
    }

    @Test
    fun `a message 2 shorter than an ephemeral and a tag, or past the Noise bound, is refused`() {
        val handshake = initiatorFor(ik)
        handshake.writeFirstMessage(ik.first.payload)
        assertEquals(
            48,
            assertThrows<NoiseException.MessageTooShort> { handshake.readSecondMessage(ByteArray(47)) }.min,
        )
        assertEquals(
            65_535,
            assertThrows<NoiseException.MessageTooLarge> { handshake.readSecondMessage(ByteArray(65_536)) }.max,
        )
        assertHex(ik.second.payload, handshake.readSecondMessage(ik.second.wire).payload)
    }

    @Test
    fun `a peer key of the wrong length is refused before the agreement is initialised`() {
        // On AndroidKeyStore, init opens a KeyMint operation that a refusal after it would leave open.
        // XDH's init refuses an EC key, so only a length check made before init reaches BadKeyLength.
        val refusedByInit = KeyPairGenerator.getInstance("EC").generateKeyPair().private
        val refusal =
            assertThrows<NoiseException.BadKeyLength> {
                x25519(KeyAgreement.getInstance(XDH), refusedByInit, ByteArray(31))
            }
        assertEquals(31, refusal.size)
    }

    @Test
    fun `an all-zero or short shared secret is refused`() {
        assertThrows<NoiseException.LowOrderKey> { requireSharedSecret(ByteArray(32)) }
        assertEquals(31, assertThrows<NoiseException.BadKeyLength> { requireSharedSecret(ByteArray(31)) }.size)
    }

    @Test
    fun `the public factory's own fresh ephemeral completes a handshake`() {
        val responder = responderFor(ik)
        val handshake = InitiatorHandshake.ik(SoftwareX25519Key(ik.initStaticPrivate), ik.respStaticPublic)
        val first = handshake.writeFirstMessage(ik.first.payload)
        assertHex(ik.first.payload, responder.readFirst(first))
        val session = handshake.readSecondMessage(responder.writeSecond(ik.second.payload)).session
        val (receive, _) = responder.split()
        assertHex(ik.first.payload, receive.decryptWithAd(ByteArray(0), session.encrypt(ik.first.payload)))
    }

    private fun occurrences(
        needle: ByteArray,
        haystack: ByteArray,
    ): Int =
        (0..haystack.size - needle.size).count { at ->
            haystack.copyOfRange(at, at + needle.size).contentEquals(needle)
        }
}
