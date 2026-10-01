package io.tezra.fermix.session

import io.tezra.fermix.noise.StaticKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// The daemon's half of Noise IK over the JDK's own primitives, so a test stands where the daemon does.
// core-noise's responder lives in its own tests and uses its internal classes, so this one is written
// again from the Noise spec; core-noise's vector tests are what pin the handshake byte for byte.

/** The SubjectPublicKeyInfo of an X25519 key, before its 32 raw bytes. */
private const val SPKI_PREFIX = "302a300506032b656e032100"
private const val SPKI_BYTES = SPKI_PREFIX.length / 2
private const val KEY_BYTES = 32
private const val TAG_BYTES = 16
private const val NONCE_BYTES = 12
private const val PROTOCOL_NAME = "Noise_IK_25519_ChaChaPoly_SHA256"
private val PRELUDE = "FXM1".encodeToByteArray() + byteArrayOf(1)
private val PROLOGUE = "fermix-mobile-v1".encodeToByteArray() + PRELUDE

/** The empty associated data of every transport message. */
private val NO_DATA = ByteArray(0)

/** A software X25519 key: the phone's static in these tests, and the daemon's static and ephemerals. */
internal class SoftwareKey private constructor(
    private val pair: KeyPair,
) : StaticKey {
    override val publicKey: ByteArray = pair.public.encoded.copyOfRange(SPKI_BYTES, SPKI_BYTES + KEY_BYTES)

    override fun agree(peerPublicKey: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("XDH")
        agreement.init(pair.private)
        agreement.doPhase(publicKeyOf(peerPublicKey), true)
        return agreement.generateSecret()
    }

    companion object {
        fun generate(): SoftwareKey {
            val generator = KeyPairGenerator.getInstance("XDH")
            generator.initialize(NamedParameterSpec.X25519)
            return SoftwareKey(generator.generateKeyPair())
        }
    }
}

private fun publicKeyOf(raw: ByteArray): PublicKey =
    KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(SPKI_PREFIX.hexToByteArray() + raw))

/** One direction of a transport: ChaChaPoly with Noise's counter nonce and no associated data. */
internal class TransportCipher(
    private val key: ByteArray,
) {
    private var nonce = 0L

    fun encrypt(plaintext: ByteArray): ByteArray = chaChaPoly(Cipher.ENCRYPT_MODE, key, nonce++, NO_DATA, plaintext)

    fun decrypt(message: ByteArray): ByteArray = chaChaPoly(Cipher.DECRYPT_MODE, key, nonce++, NO_DATA, message)
}

/**
 * The responder's side of `<- s ... -> e, es, s, ss  <- e, ee, se`: it reads the phone's message 1,
 * prelude included, writes message 2 with an empty payload, and splits into its two ciphers.
 */
internal class IkResponder(
    private val static: SoftwareKey,
) {
    private var hash = PROTOCOL_NAME.encodeToByteArray()
    private var chainingKey = hash.copyOf()
    private var key = ByteArray(0)
    private var nonce = 0L

    init {
        check(hash.size == KEY_BYTES) { "the protocol name is the hash's initial value only at 32 bytes" }
        mixHash(PROLOGUE)
        mixHash(static.publicKey)
    }

    /** Message 2 for [first], and the responder's receive and send ciphers after Split. */
    fun respond(first: ByteArray): Triple<ByteArray, TransportCipher, TransportCipher> {
        check(first.copyOf(PRELUDE.size).contentEquals(PRELUDE)) { "message 1 lacks the IK prelude" }
        val staticAt = PRELUDE.size + KEY_BYTES
        val payloadAt = staticAt + KEY_BYTES + TAG_BYTES
        val initiatorEphemeral = first.copyOfRange(PRELUDE.size, staticAt)
        mixHash(initiatorEphemeral)
        mixKey(static.agree(initiatorEphemeral))
        val initiatorStatic = decryptAndHash(first.copyOfRange(staticAt, payloadAt))
        mixKey(static.agree(initiatorStatic))
        decryptAndHash(first.copyOfRange(payloadAt, first.size))
        val ephemeral = SoftwareKey.generate()
        mixHash(ephemeral.publicKey)
        mixKey(ephemeral.agree(initiatorEphemeral))
        mixKey(ephemeral.agree(initiatorStatic))
        val second = ephemeral.publicKey + encryptAndHash(ByteArray(0))
        val (receive, send) = hkdf(chainingKey, ByteArray(0))
        return Triple(second, TransportCipher(receive), TransportCipher(send))
    }

    private fun mixHash(data: ByteArray) {
        hash =
            MessageDigest.getInstance("SHA-256").run {
                update(hash)
                digest(data)
            }
    }

    private fun mixKey(inputKeyMaterial: ByteArray) {
        val (nextChainingKey, nextKey) = hkdf(chainingKey, inputKeyMaterial)
        chainingKey = nextChainingKey
        key = nextKey
        nonce = 0
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray =
        chaChaPoly(Cipher.ENCRYPT_MODE, key, nonce++, hash, plaintext).also { mixHash(it) }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray =
        chaChaPoly(Cipher.DECRYPT_MODE, key, nonce++, hash, ciphertext).also { mixHash(ciphertext) }
}

/** Noise's HKDF over HMAC-SHA256: its first two outputs. */
private fun hkdf(
    chainingKey: ByteArray,
    inputKeyMaterial: ByteArray,
): Pair<ByteArray, ByteArray> {
    val temporaryKey = hmac(chainingKey, inputKeyMaterial)
    val first = hmac(temporaryKey, byteArrayOf(1))
    return first to hmac(temporaryKey, first + byteArrayOf(2))
}

private fun hmac(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun chaChaPoly(
    mode: Int,
    key: ByteArray,
    nonce: Long,
    ad: ByteArray,
    input: ByteArray,
): ByteArray {
    val iv =
        ByteBuffer
            .allocate(NONCE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0)
            .putLong(nonce)
            .array()
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(iv))
    cipher.updateAAD(ad)
    return cipher.doFinal(input)
}
