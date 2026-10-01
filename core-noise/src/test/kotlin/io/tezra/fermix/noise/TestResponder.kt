package io.tezra.fermix.noise

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The daemon's half of IK and IKpsk2, so a test can send the initiator what a responder would. The
 * vectors pin only the initiator-to-responder transport, so this is what proves the receive
 * direction. Its handshake runs on this module's SymmetricState, and its message 2 must equal the
 * vector's before anything it sends is trusted. Its transport keys do not come from that
 * SymmetricState: it tracks the chaining key itself with Noise's HKDF written again below, so a
 * fault in the module's Split cannot hand both ends the same wrong key.
 */
internal class TestResponder(
    private val mode: NoiseMode,
    private val static: SoftwareX25519Key,
    private val ephemeral: SoftwareX25519Key,
    private val psk: ByteArray?,
) {
    private val symmetric = SymmetricState(mode.protocolName)
    private var chainingKey = referenceInitialChainingKey(mode.protocolName)
    private var initiatorEphemeral = ByteArray(0)
    private var initiatorStatic = ByteArray(0)

    init {
        symmetric.mixHash(mode.prologue())
        symmetric.mixHash(static.publicKey)
    }

    /** Reads message 1, prelude included, and returns its payload. */
    fun readFirst(wire: ByteArray): ByteArray {
        val prelude = mode.prelude()
        check(wire.copyOfRange(0, prelude.size).contentEquals(prelude)) { "message 1 lacks the $mode prelude" }
        val staticAt = prelude.size + KEY_BYTES
        val payloadAt = staticAt + KEY_BYTES + TAG_BYTES
        initiatorEphemeral = wire.copyOfRange(prelude.size, staticAt)
        mixEphemeral(initiatorEphemeral)
        mixKey(static.agree(initiatorEphemeral))
        initiatorStatic = symmetric.decryptAndHash(wire.copyOfRange(staticAt, payloadAt))
        mixKey(static.agree(initiatorStatic))
        return symmetric.decryptAndHash(wire.copyOfRange(payloadAt, wire.size))
    }

    /** Writes message 2, which carries no prelude. */
    fun writeSecond(payload: ByteArray): ByteArray {
        mixEphemeral(ephemeral.publicKey)
        mixKey(ephemeral.agree(initiatorEphemeral))
        mixKey(ephemeral.agree(initiatorStatic))
        if (mode.hasPsk) mixKeyAndHash(checkNotNull(psk) { "IKpsk2 needs a PSK" })
        return ephemeral.publicKey + symmetric.encryptAndHash(payload)
    }

    /** The responder's transport ciphers: it receives on Split's first key and sends on its second. */
    fun split(): Pair<CipherState, CipherState> {
        val (first, second) = referenceHkdf(chainingKey, ByteArray(0))
        return CipherState(first) to CipherState(second)
    }

    private fun mixEphemeral(publicKey: ByteArray) {
        symmetric.mixHash(publicKey)
        if (mode.hasPsk) mixKey(publicKey)
    }

    private fun mixKey(inputKeyMaterial: ByteArray) {
        symmetric.mixKey(inputKeyMaterial)
        chainingKey = referenceHkdf(chainingKey, inputKeyMaterial)[0]
    }

    private fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        symmetric.mixKeyAndHash(inputKeyMaterial)
        chainingKey = referenceHkdf(chainingKey, inputKeyMaterial)[0]
    }
}

/** h and ck at the start (rev 34 section 5.2): the protocol name, zero-padded, or its SHA-256 if longer. */
private fun referenceInitialChainingKey(protocolName: String): ByteArray {
    val name = protocolName.encodeToByteArray()
    return if (name.size <= KEY_BYTES) name.copyOf(KEY_BYTES) else MessageDigest.getInstance("SHA-256").digest(name)
}

/**
 * Noise's HKDF (rev 34 section 4.3) written again from the spec, with none of the module's code: its
 * first two outputs, which are all MixKey and Split take, and the first of MixKeyAndHash's three.
 */
private fun referenceHkdf(
    chainingKey: ByteArray,
    inputKeyMaterial: ByteArray,
): List<ByteArray> {
    val temporaryKey = referenceHmac(chainingKey, inputKeyMaterial)
    val first = referenceHmac(temporaryKey, byteArrayOf(1))
    val second = referenceHmac(temporaryKey, first + byteArrayOf(2))
    return listOf(first, second)
}

private fun referenceHmac(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}
