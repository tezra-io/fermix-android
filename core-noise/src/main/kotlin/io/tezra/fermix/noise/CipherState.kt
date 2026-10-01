package io.tezra.fermix.noise

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The ChaChaPoly tag Noise appends to every ciphertext. */
internal const val TAG_BYTES = 16

/**
 * The one name for ChaCha20-Poly1305 that both platforms serve. Design section 6.1 names
 * ChaCha20/Poly1305/NoPadding, which SunJCE does not know; Conscrypt registers this name as an alias
 * of that one (Conscrypt #653, 2019), so the JVM tests and the phone run the same code.
 */
private const val CHACHA20_POLY1305 = "ChaCha20-Poly1305"

/** ChaChaPoly's 12-byte nonce: 4 zero bytes, then Noise's 8-byte counter. */
private const val NONCE_BYTES = 12

/** Noise reserves the last nonce for REKEY; a direction that reaches it is spent. */
private const val MAX_NONCE: ULong = ULong.MAX_VALUE

/**
 * Noise's CipherState for ChaChaPoly (rev 34 sections 5.1, 11.3 and 12.3), always keyed: its key k
 * and nonce n, EncryptWithAd, DecryptWithAd and Rekey. n = 2^64 - 1 is never used: the call made at
 * it is refused, and n never wraps. So the last frame a direction carries is the one at 2^64 - 2.
 * [nonce] is an argument so a test can stand a cipher at its last nonce. The cipher owns its copy of
 * the key, and zeroes it when Rekey replaces it or [wipe] ends it.
 */
internal class CipherState(
    key: ByteArray,
    nonce: ULong = 0u,
) {
    var key: ByteArray = key.copyOf()
        private set
    var nonce: ULong = nonce
        private set

    init {
        requireKeyLength("cipher key", key)
    }

    fun encryptWithAd(
        ad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        requireNonceLeft()
        val ciphertext = chaChaPoly(Cipher.ENCRYPT_MODE, key, nonce, ad).doFinal(plaintext)
        nonce++
        return ciphertext
    }

    /** A tag that does not verify leaves the nonce where it was. */
    fun decryptWithAd(
        ad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        requireNonceLeft()
        val cipher = chaChaPoly(Cipher.DECRYPT_MODE, key, nonce, ad)
        val plaintext =
            try {
                cipher.doFinal(ciphertext)
            } catch (failure: AEADBadTagException) {
                throw NoiseException.AuthenticationFailed(failure)
            }
        nonce++
        return plaintext
    }

    /** k becomes the first 32 bytes of ENCRYPT(k, 2^64 - 1, empty, 32 zero bytes); n is kept. */
    fun rekey() {
        val sealed = chaChaPoly(Cipher.ENCRYPT_MODE, key, MAX_NONCE, ByteArray(0)).doFinal(ByteArray(KEY_BYTES))
        // The old key is zeroed, so a later compromise cannot reach back past the rekey (section 11.3).
        key.fill(0)
        key = sealed.copyOf(KEY_BYTES)
        sealed.fill(0)
    }

    /** Zeroes the key once its owner is done with the cipher. */
    fun wipe() {
        key.fill(0)
    }

    private fun requireNonceLeft() {
        if (nonce == MAX_NONCE) throw NoiseException.NonceExhausted()
    }
}

/**
 * A ChaChaPoly cipher set up for one operation. A new one every call: SunJCE refuses to set a cipher
 * up again with the key and nonce it last encrypted under, and so does Conscrypt, by its AOSP source
 * (android15-release OpenSSLAeadCipher, "When using AEAD key and IV must not be re-used"), which
 * design section 6.1 lists as unverified.
 */
private fun chaChaPoly(
    mode: Int,
    key: ByteArray,
    nonce: ULong,
    ad: ByteArray,
): Cipher {
    val iv =
        ByteBuffer
            .allocate(NONCE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0)
            .putLong(nonce.toLong())
            .array()
    val cipher = Cipher.getInstance(CHACHA20_POLY1305)
    cipher.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(iv))
    cipher.updateAAD(ad)
    return cipher
}
