package io.tezra.fermix.push

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305's name on both platforms: SunJCE's on the JVM, and on a phone Conscrypt's alias of
 * ChaCha20/Poly1305/NoPadding, as core-noise's CipherState uses it.
 */
private const val CHACHA20_POLY1305 = "ChaCha20-Poly1305"

private const val KEY_BYTES = 32

/**
 * A push's AEAD (design section 10): ChaCha20-Poly1305 under the push key, the 12-byte nonce `n`, empty
 * associated data and the 16-byte tag at the end of `c`.
 */
object PushCipher {
    /**
     * The plaintext of [sealed] under [key] and [nonce], or none when its tag does not verify: the push is
     * another instance's, or not the daemon's at all. A trial expects that answer from every key but one, so
     * it is the outcome, not a fault.
     */
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        sealed: ByteArray,
    ): ByteArray? {
        require(key.size == KEY_BYTES) { "a push key is $KEY_BYTES bytes, not ${key.size}" }
        require(nonce.size == NONCE_BYTES) { "a push nonce is $NONCE_BYTES bytes, not ${nonce.size}" }
        require(sealed.size >= TAG_BYTES) { "a sealed push holds its $TAG_BYTES-byte tag" }
        val cipher = Cipher.getInstance(CHACHA20_POLY1305)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return try {
            cipher.doFinal(sealed)
        } catch (expectedOfAnotherKey: AEADBadTagException) {
            null
        }
    }
}
