package io.tezra.fermix.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.security.KeyPair
import java.security.interfaces.XECPrivateKey
import java.security.spec.AlgorithmParameterSpec
import java.util.Optional
import javax.security.auth.DestroyFailedException
import kotlin.experimental.xor

/**
 * The secrets this module owns are zeroed once spent: a replaced key, the handshake's state when it
 * ends however it ends, and each X25519 output once mixed; and a handshake that ends destroys its
 * ephemeral private key. The copies JCA makes for itself are out of reach and not covered.
 * NoiseSessionTest covers the transport keys an ended session zeroes.
 */
class SecretWipeTest {
    private val ikpsk2 = vector("ikpsk2")

    @Test
    fun `Rekey zeroes the key it replaces`() {
        val cipher = CipherState(ByteArray(32) { 7 })
        val old = cipher.key
        cipher.rekey()
        assertZero("the key before the rekey", old)
    }

    @Test
    fun `MixKey and MixKeyAndHash zero the chaining key and the cipher key they replace`() {
        val symmetric = SymmetricState(NoiseMode.IKPSK2.protocolName)
        val initialChainingKey = symmetric.chainingKey
        symmetric.mixKey(ByteArray(32) { 1 })
        assertZero("the initial chaining key", initialChainingKey)
        val chainingKey = symmetric.chainingKey
        val cipherKey = checkNotNull(symmetric.cipher).key
        symmetric.mixKeyAndHash(ByteArray(32) { 2 })
        assertZero("the chaining key MixKeyAndHash replaced", chainingKey)
        assertZero("the cipher key MixKeyAndHash replaced", cipherKey)
    }

    @Test
    fun `Split zeroes the chaining key and the handshake's cipher key`() {
        val symmetric = SymmetricState(NoiseMode.IK.protocolName)
        symmetric.mixKey(ByteArray(32) { 1 })
        val chainingKey = symmetric.chainingKey
        val cipherKey = checkNotNull(symmetric.cipher).key
        symmetric.split()
        assertZero("the chaining key after Split", chainingKey)
        assertZero("the handshake cipher key after Split", cipherKey)
    }

    @ParameterizedTest
    @MethodSource("io.tezra.fermix.noise.NoiseVectorTest#vectors")
    fun `a finished handshake keeps no secret, and each X25519 output is zeroed once mixed`(vector: NoiseVector) {
        val static = RecordingStaticKey(SoftwareX25519Key(vector.initStaticPrivate))
        val handshake = initiatorFor(vector, static)
        assertHex(vector.first.wire, handshake.writeFirstMessage(vector.first.payload))
        handshake.readSecondMessage(vector.second.wire)
        assertTrue(handshake.keepsNoSecret(), "a finished handshake still holds a secret")
        // ss in message 1 and se in message 2; the vectors' wire shows each was mixed before it was zeroed.
        assertEquals(2, static.outputs.size)
        static.outputs.forEach { assertZero("an X25519 output of the static key", it) }
    }

    @Test
    fun `a handshake whose message 2 fails keeps no secret, the PSK included`() {
        val handshake = initiatorFor(ikpsk2)
        handshake.writeFirstMessage(ikpsk2.first.payload)
        val tampered =
            ikpsk2.second.wire
                .copyOf()
                .also { it[it.lastIndex] = it[it.lastIndex] xor 1 }
        assertThrows<NoiseException.AuthenticationFailed> { handshake.readSecondMessage(tampered) }
        assertTrue(handshake.keepsNoSecret(), "a handshake that failed in message 2 still holds a secret")
    }

    @Test
    fun `a handshake refused at a low-order ephemeral, before the PSK is mixed, keeps no secret`() {
        val handshake = initiatorFor(ikpsk2)
        handshake.writeFirstMessage(ikpsk2.first.payload)
        assertThrows<NoiseException.LowOrderKey> { handshake.readSecondMessage(ByteArray(KEY_BYTES + TAG_BYTES)) }
        assertTrue(handshake.keepsNoSecret(), "a handshake refused before its psk token still holds a secret")
    }

    @Test
    fun `a handshake whose message 1 fails keeps no secret`() {
        val zeroAgreement =
            object : StaticKey {
                override val publicKey = SoftwareX25519Key(ikpsk2.initStaticPrivate).publicKey

                override fun agree(peerPublicKey: ByteArray) = ByteArray(KEY_BYTES)
            }
        val handshake = initiatorFor(ikpsk2, zeroAgreement)
        assertThrows<NoiseException.LowOrderKey> { handshake.writeFirstMessage(ikpsk2.first.payload) }
        assertTrue(handshake.keepsNoSecret(), "a handshake that failed in message 1 still holds a secret")
    }

    @Test
    fun `a handshake closed between its messages keeps no secret and refuses message 2`() {
        val handshake = initiatorFor(ikpsk2)
        handshake.writeFirstMessage(ikpsk2.first.payload)
        handshake.close()
        assertTrue(handshake.keepsNoSecret(), "a closed handshake still holds a secret")
        assertThrows<NoiseException.OutOfOrder> { handshake.readSecondMessage(ikpsk2.second.wire) }
    }

    @Test
    fun `a handshake refused for a message 2's size keeps its secrets and can still complete`() {
        val handshake = initiatorFor(ikpsk2)
        handshake.writeFirstMessage(ikpsk2.first.payload)
        assertThrows<NoiseException.MessageTooShort> { handshake.readSecondMessage(ByteArray(47)) }
        assertHex(ikpsk2.second.payload, handshake.readSecondMessage(ikpsk2.second.wire).payload)
    }

    @Test
    fun `a finished handshake destroys its ephemeral private key once, and a later close finds none`() {
        val ephemeral = DestroyRecordingKey(ikpsk2.initEphemeralPrivate)
        val handshake = initiatorFor(ikpsk2, ephemeral = ephemeral.keyPair())
        handshake.writeFirstMessage(ikpsk2.first.payload)
        assertFalse(ephemeral.isDestroyed, "the ephemeral was destroyed before message 2 needed it")
        handshake.readSecondMessage(ikpsk2.second.wire)
        assertTrue(ephemeral.isDestroyed, "a finished handshake left its ephemeral private key undestroyed")
        handshake.close()
        assertEquals(1, ephemeral.destroyCalls)
    }

    @Test
    fun `a handshake whose message 1 fails destroys its ephemeral private key`() {
        val ephemeral = DestroyRecordingKey(ikpsk2.initEphemeralPrivate)
        val static = SoftwareX25519Key(ikpsk2.initStaticPrivate)
        // u = 0, a low-order responder key: es is refused, inside message 1.
        val handshake = InitiatorHandshake.ik(static, ByteArray(KEY_BYTES), ephemeral.keyPair())
        assertThrows<NoiseException.LowOrderKey> { handshake.writeFirstMessage(ikpsk2.first.payload) }
        assertTrue(ephemeral.isDestroyed, "a handshake that failed in message 1 left its ephemeral undestroyed")
    }

    @Test
    fun `a handshake whose message 2 fails destroys its ephemeral private key`() {
        val ephemeral = DestroyRecordingKey(ikpsk2.initEphemeralPrivate)
        val handshake = initiatorFor(ikpsk2, ephemeral = ephemeral.keyPair())
        handshake.writeFirstMessage(ikpsk2.first.payload)
        val tampered =
            ikpsk2.second.wire
                .copyOf()
                .also { it[it.lastIndex] = it[it.lastIndex] xor 1 }
        assertThrows<NoiseException.AuthenticationFailed> { handshake.readSecondMessage(tampered) }
        assertTrue(ephemeral.isDestroyed, "a handshake that failed in message 2 left its ephemeral undestroyed")
    }

    @Test
    fun `a handshake closed between its messages destroys its ephemeral private key`() {
        val ephemeral = DestroyRecordingKey(ikpsk2.initEphemeralPrivate)
        val handshake = initiatorFor(ikpsk2, ephemeral = ephemeral.keyPair())
        handshake.writeFirstMessage(ikpsk2.first.payload)
        handshake.close()
        assertTrue(ephemeral.isDestroyed, "a closed handshake left its ephemeral private key undestroyed")
    }

    @Test
    fun `SunEC's X25519 private keys refuse destroy, so the vector handshakes end on that refusal`() {
        // The control for the path the vector tests take: were SunEC to destroy its keys, they would not.
        val vectorKey = SoftwareX25519Key(ikpsk2.initEphemeralPrivate).keyPair().private
        assertThrows<DestroyFailedException> { vectorKey.destroy() }
        assertThrows<DestroyFailedException> { newEphemeralKeyPair().private.destroy() }
    }
}

/**
 * An X25519 private key that counts its destroy() calls and is destroyed after the first, around a
 * SunEC key, which has no destroy() of its own. It stands in for a key that can be destroyed, as
 * Conscrypt's is on a phone.
 */
private class DestroyRecordingKey(
    privateBytes: ByteArray,
) : XECPrivateKey {
    private val pair = SoftwareX25519Key(privateBytes).keyPair()
    private val key = pair.private as XECPrivateKey

    var destroyCalls = 0
        private set

    /** The pair the handshake takes, this key its private half. */
    fun keyPair(): KeyPair = KeyPair(pair.public, this)

    override fun getScalar(): Optional<ByteArray> = key.scalar

    override fun getParams(): AlgorithmParameterSpec = key.params

    override fun getAlgorithm(): String = key.algorithm

    override fun getFormat(): String = key.format

    override fun getEncoded(): ByteArray = key.encoded

    override fun destroy() {
        destroyCalls += 1
    }

    override fun isDestroyed(): Boolean = destroyCalls > 0
}

/** A static key that keeps a reference to every X25519 output it hands out, to see them zeroed. */
private class RecordingStaticKey(
    private val key: SoftwareX25519Key,
) : StaticKey {
    val outputs = mutableListOf<ByteArray>()

    override val publicKey: ByteArray
        get() = key.publicKey

    override fun agree(peerPublicKey: ByteArray): ByteArray = key.agree(peerPublicKey).also { outputs += it }
}

/** The handshake's PSK copy, chaining key and cipher key are all zeros, or absent. */
private fun InitiatorHandshake.keepsNoSecret(): Boolean =
    (psk?.isZero() ?: true) && symmetric.chainingKey.isZero() && (symmetric.cipher?.key?.isZero() ?: true)
