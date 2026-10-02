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
import java.security.spec.XECPrivateKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// The daemon's half of Noise IK and IKpsk2 over the JDK's own primitives, so a test stands where the
// daemon does. core-noise's responder lives in its own tests and uses its internal classes, so this one
// is written again from the Noise spec; PairingVectorTest holds it to the vendored vectors byte for byte.

/** The SubjectPublicKeyInfo of an X25519 key, before its 32 raw bytes. */
private const val SPKI_PREFIX = "302a300506032b656e032100"
private const val SPKI_BYTES = SPKI_PREFIX.length / 2
private const val KEY_BYTES = 32
private const val TAG_BYTES = 16
private const val NONCE_BYTES = 12
private const val IK_NAME = "Noise_IK_25519_ChaChaPoly_SHA256"
private const val IKPSK2_NAME = "Noise_IKpsk2_25519_ChaChaPoly_SHA256"
private const val PROLOGUE_TEXT = "fermix-mobile-v1"
private const val SAS_LABEL = "fermix-mobile-sas-v1"
private const val SAS_MODULUS = 1_000_000L
private const val SAS_DIGITS = 6

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

        /** A fixed key, a vector's: its raw 32-byte private scalar and the public key it yields. */
        fun of(
            privateKey: ByteArray,
            publicKey: ByteArray,
        ): SoftwareKey {
            val factory = KeyFactory.getInstance("XDH")
            val private = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, privateKey))
            return SoftwareKey(KeyPair(publicKeyOf(publicKey), private))
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
 * What a responder made of message 1: message 2, its receive and send ciphers after Split, the handshake
 * hash the SAS is derived from, and the phone's static key, which message 1 carried.
 */
internal class Responded(
    val second: ByteArray,
    val receive: TransportCipher,
    val send: TransportCipher,
    val handshakeHash: ByteArray,
    val initiatorStatic: ByteArray,
)

/**
 * The responder's side of `<- s ... -> e, es, s, ss  <- e, ee, se`, and with [psk] of IKpsk2's, which
 * mixes each `e` into the key too and ends message 2 with `psk`: it reads the phone's message 1, prelude
 * included, writes message 2 on [ephemeral], and splits into its two ciphers.
 */
internal class IkResponder(
    private val static: SoftwareKey,
    private val psk: ByteArray? = null,
    private val ephemeral: SoftwareKey = SoftwareKey.generate(),
) {
    private val prelude = "FXM1".encodeToByteArray() + byteArrayOf(if (psk == null) 1 else 2)
    private var hash = initialHash(if (psk == null) IK_NAME else IKPSK2_NAME)
    private var chainingKey = hash.copyOf()
    private var key = ByteArray(0)
    private var nonce = 0L

    init {
        mixHash(PROLOGUE_TEXT.encodeToByteArray() + prelude)
        mixHash(static.publicKey)
    }

    /** Message 2, carrying [payload], for [first]; and the ciphers, the hash and the phone's key. */
    fun respond(
        first: ByteArray,
        payload: ByteArray = ByteArray(0),
    ): Responded {
        check(first.copyOf(prelude.size).contentEquals(prelude)) { "message 1 lacks the prelude ${prelude.last()}" }
        val staticAt = prelude.size + KEY_BYTES
        val payloadAt = staticAt + KEY_BYTES + TAG_BYTES
        val initiatorEphemeral = first.copyOfRange(prelude.size, staticAt)
        mixEphemeral(initiatorEphemeral)
        mixKey(static.agree(initiatorEphemeral))
        val initiatorStatic = decryptAndHash(first.copyOfRange(staticAt, payloadAt))
        mixKey(static.agree(initiatorStatic))
        decryptAndHash(first.copyOfRange(payloadAt, first.size))
        mixEphemeral(ephemeral.publicKey)
        mixKey(ephemeral.agree(initiatorEphemeral))
        mixKey(ephemeral.agree(initiatorStatic))
        psk?.let(::mixKeyAndHash)
        val second = ephemeral.publicKey + encryptAndHash(payload)
        val (receive, send) = hkdf(chainingKey, ByteArray(0))
        return Responded(second, TransportCipher(receive), TransportCipher(send), hash.copyOf(), initiatorStatic)
    }

    /** An `e` token: hashed, and in a PSK handshake mixed into the key as well. */
    private fun mixEphemeral(publicKey: ByteArray) {
        mixHash(publicKey)
        if (psk != null) mixKey(publicKey)
    }

    private fun mixHash(data: ByteArray) {
        hash = sha256(hash + data)
    }

    private fun mixKey(inputKeyMaterial: ByteArray) {
        val (nextChainingKey, nextKey) = hkdf(chainingKey, inputKeyMaterial)
        chainingKey = nextChainingKey
        key = nextKey
        nonce = 0
    }

    private fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val temporaryKey = hmac(chainingKey, inputKeyMaterial)
        chainingKey = hmac(temporaryKey, byteArrayOf(1))
        val temporaryHash = hmac(temporaryKey, chainingKey + byteArrayOf(2))
        mixHash(temporaryHash)
        key = hmac(temporaryKey, temporaryHash + byteArrayOf(3))
        nonce = 0
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray =
        chaChaPoly(Cipher.ENCRYPT_MODE, key, nonce++, hash, plaintext).also { mixHash(it) }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray =
        chaChaPoly(Cipher.DECRYPT_MODE, key, nonce++, hash, ciphertext).also { mixHash(ciphertext) }
}

/** A protocol name of 32 bytes is the hash's initial value as it is, and a longer one is hashed. */
private fun initialHash(protocolName: String): ByteArray {
    val name = protocolName.encodeToByteArray()
    return if (name.size <= KEY_BYTES) name.copyOf(KEY_BYTES) else sha256(name)
}

/**
 * The SAS as PROTOCOL.md derives it: HMAC-SHA256 of the handshake hash over `fermix-mobile-sas-v1`, its
 * first four bytes as a big-endian unsigned integer, modulo a million, zero-padded to six digits.
 */
internal fun sasOf(handshakeHash: ByteArray): String {
    val digest = hmac(handshakeHash, SAS_LABEL.encodeToByteArray())
    val value =
        ByteBuffer
            .wrap(digest, 0, Int.SIZE_BYTES)
            .int
            .toUInt()
            .toLong()
    return (value % SAS_MODULUS).toString().padStart(SAS_DIGITS, '0')
}

private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

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
