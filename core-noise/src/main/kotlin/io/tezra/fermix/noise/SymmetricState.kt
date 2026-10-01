package io.tezra.fermix.noise

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SHA-256's output, Noise's HASHLEN. */
private const val HASH_BYTES = 32

/** Noise's HKDF makes at most three outputs. */
private const val MAX_HKDF_OUTPUTS = 3

private const val HMAC_SHA256 = "HmacSHA256"

/**
 * Noise's SymmetricState over SHA-256 (rev 34 section 5.2): the chaining key, the handshake hash,
 * and the CipherState they key. The cipher is absent until the first MixKey. IK and IKpsk2 mix a key
 * before their first EncryptAndHash, so encrypting without one is a fault here, not the spec's
 * pass-through. Each chaining key and cipher key is zeroed when it is replaced, and Split, which ends
 * the handshake, zeroes the last of them. [chainingKey] and [cipher] are internal so a test can see
 * that.
 */
internal class SymmetricState(
    protocolName: String,
) {
    private var hash: ByteArray
    internal var chainingKey: ByteArray
        private set
    internal var cipher: CipherState? = null
        private set

    init {
        val name = protocolName.encodeToByteArray()
        hash = if (name.size <= HASH_BYTES) name.copyOf(HASH_BYTES) else sha256(name)
        chainingKey = hash.copyOf()
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (nextChainingKey, cipherKey) = hkdf(chainingKey, inputKeyMaterial, outputs = 2)
        replaceKeys(nextChainingKey, cipherKey)
    }

    fun mixHash(data: ByteArray) {
        hash = sha256(hash, data)
    }

    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val (nextChainingKey, hashInput, cipherKey) = hkdf(chainingKey, inputKeyMaterial, outputs = 3)
        mixHash(hashInput)
        hashInput.fill(0)
        replaceKeys(nextChainingKey, cipherKey)
    }

    fun handshakeHash(): ByteArray = hash.copyOf()

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = keyedCipher().encryptWithAd(hash, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = keyedCipher().decryptWithAd(hash, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    /**
     * The transport ciphers: the initiator sends on the first and receives on the second. Nothing
     * keyed is left behind: the chaining key would derive both again.
     */
    fun split(): Pair<CipherState, CipherState> {
        val (first, second) = hkdf(chainingKey, ByteArray(0), outputs = 2)
        val ciphers = CipherState(first) to CipherState(second)
        first.fill(0)
        second.fill(0)
        wipe()
        return ciphers
    }

    /** Zeroes the chaining key and the cipher's key; the handshake hash is no secret and stays. */
    fun wipe() {
        chainingKey.fill(0)
        cipher?.wipe()
    }

    private fun replaceKeys(
        nextChainingKey: ByteArray,
        cipherKey: ByteArray,
    ) {
        chainingKey.fill(0)
        chainingKey = nextChainingKey
        cipher?.wipe()
        cipher = CipherState(cipherKey)
        cipherKey.fill(0)
    }

    private fun keyedCipher(): CipherState =
        checkNotNull(cipher) { "IK and IKpsk2 mix a key before their first EncryptAndHash" }
}

/**
 * Noise's HKDF (rev 34 section 4.3) over HMAC-SHA256: the temporary key HMAC(ck, ikm), then each
 * output HMAC(temporary key, the previous output and its one-byte index). The caller owns the outputs;
 * the temporary key is zeroed here.
 */
internal fun hkdf(
    chainingKey: ByteArray,
    inputKeyMaterial: ByteArray,
    outputs: Int,
): List<ByteArray> {
    require(outputs in 2..MAX_HKDF_OUTPUTS) { "Noise's HKDF makes two or three outputs, not $outputs" }
    val temporaryKey = hmacSha256(chainingKey, inputKeyMaterial)
    try {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(temporaryKey, HMAC_SHA256))
        val keys = ArrayList<ByteArray>(outputs)
        var previous = ByteArray(0)
        for (index in 1..outputs) {
            // Fed in parts, so no array holds a copy of the previous output; doFinal resets the MAC.
            mac.update(previous)
            mac.update(index.toByte())
            previous = mac.doFinal()
            keys += previous
        }
        return keys
    } finally {
        temporaryKey.fill(0)
    }
}

internal fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA256)
    mac.init(SecretKeySpec(key, HMAC_SHA256))
    return mac.doFinal(data)
}

/** SHA-256 over [parts] in order, fed one by one so no array holds them joined. */
private fun sha256(vararg parts: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { digest.update(it) }
    return digest.digest()
}
