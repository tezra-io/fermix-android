package io.tezra.fermix.noise

import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/** An X25519 key, public or private, and an X25519 output: 32 bytes. */
internal const val KEY_BYTES = 32

/** X25519's JCA name on Conscrypt, AndroidKeyStore and SunEC alike. */
internal const val XDH = "XDH"

/** The SubjectPublicKeyInfo of an id-X25519 (1.3.101.110) key, before its 32 raw bytes (design section 6.1). */
private const val SPKI_PREFIX = "302a300506032b656e032100"

/**
 * A fresh ephemeral key pair from the platform's software XDH: Conscrypt on a phone (design section
 * 6.1), SunEC on the JVM. It is not initialised with parameters, because Conscrypt refuses every
 * parameter spec and both providers make X25519 by default; the handshake checks the key's encoding
 * when it is built.
 */
internal fun newEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance(XDH).generateKeyPair()

/** A peer's raw public key as a JCA key, through its SubjectPublicKeyInfo (design section 6.1). */
internal fun x25519PublicKey(raw: ByteArray): PublicKey {
    requireKeyLength("peer public key", raw)
    return KeyFactory.getInstance(XDH).generatePublic(X509EncodedKeySpec(SPKI_PREFIX.hexToByteArray() + raw))
}

/** The raw 32 bytes of a JCA X25519 public key: the last 32 bytes of its SubjectPublicKeyInfo. */
internal fun rawX25519PublicKey(key: PublicKey): ByteArray {
    val prefix = SPKI_PREFIX.hexToByteArray()
    val encoded = key.encoded
    check(encoded.size == prefix.size + KEY_BYTES && encoded.copyOf(prefix.size).contentEquals(prefix)) {
        "a ${key.algorithm} key encoded as ${key.format} is not an X25519 SubjectPublicKeyInfo"
    }
    return encoded.copyOfRange(prefix.size, encoded.size)
}

/**
 * X25519 of [privateKey] with a peer's raw public key, on the provider [agreement] belongs to. SunEC
 * and Conscrypt run X25519 in doPhase and refuse a low-order peer point there with an
 * InvalidKeyException ("Point has small order", "Error running X25519"). The peer key reaching doPhase
 * is always a well-formed X25519 key, so that refusal is the low-order one, typed here as such with
 * the provider's exception as its cause. A refusal of [privateKey] in init is not the peer's doing and
 * passes through as it is. The peer key is checked before init: on AndroidKeyStore, init opens a
 * KeyMint operation that only generateSecret or finalisation closes.
 */
internal fun x25519(
    agreement: KeyAgreement,
    privateKey: PrivateKey,
    peerPublicKey: ByteArray,
): ByteArray {
    val peer = x25519PublicKey(peerPublicKey)
    agreement.init(privateKey)
    try {
        agreement.doPhase(peer, true)
    } catch (refusal: InvalidKeyException) {
        throw NoiseException.LowOrderKey(refusal)
    }
    return agreement.generateSecret()
}

/**
 * An X25519 output the handshake may mix: 32 bytes, and not all zeros, which a low-order peer point
 * gives. SunEC and Conscrypt refuse such a point in doPhase; whether the Keystore does is unverified
 * (design section 6.1), so the handshake checks every output, whichever provider made it. The zero
 * test reads every byte, so its time does not depend on the secret.
 */
internal fun requireSharedSecret(secret: ByteArray): ByteArray {
    requireKeyLength("X25519 output", secret)
    val anyBit = secret.fold(0) { bits, byte -> bits or byte.toInt() }
    if (anyBit == 0) throw NoiseException.LowOrderKey()
    return secret
}

/** Refuses a key that is not 32 bytes. */
internal fun requireKeyLength(
    name: String,
    key: ByteArray,
) {
    if (key.size != KEY_BYTES) throw NoiseException.BadKeyLength(name, key.size)
}
