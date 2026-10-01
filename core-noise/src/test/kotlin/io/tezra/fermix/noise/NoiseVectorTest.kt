package io.tezra.fermix.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The JVM half of the device gate for Noise (design section 12.6): both handshakes, their hashes and
 * SAS, the transport and the rekey, replayed from the vendored noise_vectors.json. Each expectation
 * is its own test, so a wrong byte anywhere fails the test that owns it.
 */
class NoiseVectorTest {
    @Test
    fun `the vendored file carries the ik and the ikpsk2 vector`() {
        assertEquals(listOf("ik", "ikpsk2"), loadNoiseVectors().map { it.pattern })
    }

    @Test
    fun `the file's suite names the two protocols the handshake runs`() {
        val suite = loadVectorSuite()
        val (prefix, patterns, suffix) =
            checkNotNull(Regex("""(.*)\{(.*)\}(.*)""").matchEntire(suite)) {
                "the suite $suite names no {pattern,pattern} alternatives"
            }.destructured
        val names = patterns.split(",").map { prefix + it + suffix }
        assertEquals(listOf(NoiseMode.IK.protocolName, NoiseMode.IKPSK2.protocolName), names)
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `the fixed private keys give the vector's public keys`(vector: NoiseVector) {
        assertHex(vector.initStaticPublic, SoftwareX25519Key(vector.initStaticPrivate).publicKey)
        assertHex(vector.respStaticPublic, SoftwareX25519Key(vector.respStaticPrivate).publicKey)
        assertHex(vector.initEphemeralPublic, SoftwareX25519Key(vector.initEphemeralPrivate).publicKey)
        assertHex(vector.respEphemeralPublic, SoftwareX25519Key(vector.respEphemeralPrivate).publicKey)
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `message 1 is the prelude and then the Noise message, byte for byte`(vector: NoiseVector) {
        assertHex(vector.first.wire, initiatorFor(vector).writeFirstMessage(vector.first.payload))
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `message 2 completes the handshake and yields the daemon's payload`(vector: NoiseVector) {
        assertHex(vector.second.payload, completedHandshake(vector).payload)
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `the handshake hash is the vector's`(vector: NoiseVector) {
        assertHex(vector.handshakeHash, completedHandshake(vector).session.handshakeHash())
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `the SAS is the vector's`(vector: NoiseVector) {
        assertEquals(vector.sas, completedHandshake(vector).session.sas)
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `each transport plaintext seals to the vector's ciphertext at the vector's nonce`(vector: NoiseVector) {
        val session = completedHandshake(vector).session
        vector.transport.forEach { frame ->
            assertEquals(frame.nonce, session.send.nonce)
            assertHex(frame.ciphertext, session.encrypt(frame.plaintext))
        }
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `the send direction rekeys after exactly 2 to the 20 frames and keeps its nonce`(vector: NoiseVector) {
        val rekey = vector.rekey
        val frames = vector.transport
        assertEquals("initiator_to_responder", rekey.direction)
        check(rekey.frame.nonce == frames.size.toULong()) { "the rekey case follows the transport frames" }
        val session = completedHandshake(vector).session
        assertHex(frames.first().ciphertext, session.encrypt(frames.first().plaintext))
        assertEquals(1, session.sendFrames)
        // The frames between the vector's first and its last are counted, not sealed, so the vector's
        // last frame is the 2^20th, still under the first key. The one after it is sealed under the
        // rekeyed key, at the nonce the direction kept.
        session.skipSendFrames(REKEY_AFTER_FRAMES - frames.size)
        frames.drop(1).forEach { assertHex(it.ciphertext, session.encrypt(it.plaintext)) }
        assertEquals(REKEY_AFTER_FRAMES, session.sendFrames)
        assertEquals(rekey.frame.nonce, session.send.nonce)
        assertHex(rekey.frame.ciphertext, session.encrypt(rekey.frame.plaintext))
        assertHex(rekey.key, session.send.key)
        assertEquals(rekey.frame.nonce + 1u, session.send.nonce)
        assertEquals(1, session.sendFrames)
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `the send direction rekeys again after each further 2 to the 20 frames`(vector: NoiseVector) {
        val connected = connect(vector)
        val session = connected.session
        val peer = connected.responderReceive
        val sendAndOpen = { text: String ->
            peer.decryptWithAd(ByteArray(0), session.encrypt(text.encodeToByteArray())).decodeToString()
        }
        assertEquals("frame 1", sendAndOpen("frame 1"))
        assertEquals(1, session.sendFrames)
        repeat(2) { rekeys ->
            // Frames 2 to 2^20 - 1 of this key are counted; the 2^20th is sealed under it.
            session.skipSendFrames(REKEY_AFTER_FRAMES - 2)
            assertEquals("last under key $rekeys", sendAndOpen("last under key $rekeys"))
            assertEquals(REKEY_AFTER_FRAMES, session.sendFrames)
            peer.rekey()
            assertEquals("first under key ${rekeys + 1}", sendAndOpen("first under key ${rekeys + 1}"))
            assertEquals(1, session.sendFrames)
            assertHex(peer.key, session.send.key)
        }
    }

    @Test
    fun `the rekey interval is 2 to the 20 frames`() {
        assertEquals(1_048_576, REKEY_AFTER_FRAMES)
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `the receive direction opens what the responder seals`(vector: NoiseVector) {
        val connected = connect(vector)
        vector.transport.forEach { frame ->
            val sealed = connected.session.encrypt(frame.plaintext)
            assertHex(frame.plaintext, connected.responderReceive.decryptWithAd(ByteArray(0), sealed))
        }
        val reply = "reply-from-daemon".encodeToByteArray()
        assertHex(reply, connected.session.decrypt(connected.responderSend.encryptWithAd(ByteArray(0), reply)))
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `the receive direction rekeys after exactly 2 to the 20 frames, and again after each 2 to the 20 more`(
        vector: NoiseVector,
    ) {
        val connected = connect(vector)
        val session = connected.session
        val peer = connected.responderSend
        val receive = { text: String ->
            session.decrypt(peer.encryptWithAd(ByteArray(0), text.encodeToByteArray())).decodeToString()
        }
        assertEquals("frame 1", receive("frame 1"))
        assertEquals(1, session.receiveFrames)
        repeat(2) { rekeys ->
            session.skipReceiveFrames(REKEY_AFTER_FRAMES - 2)
            assertEquals("last under key $rekeys", receive("last under key $rekeys"))
            assertEquals(REKEY_AFTER_FRAMES, session.receiveFrames)
            peer.rekey()
            assertEquals("first under key ${rekeys + 1}", receive("first under key ${rekeys + 1}"))
            assertEquals(1, session.receiveFrames)
            assertHex(peer.key, session.receive.key)
        }
    }

    companion object {
        @JvmStatic
        fun vectors(): List<NoiseVector> = loadNoiseVectors()
    }
}
