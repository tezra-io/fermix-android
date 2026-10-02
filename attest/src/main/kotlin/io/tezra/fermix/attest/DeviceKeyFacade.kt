package io.tezra.fermix.attest

import io.tezra.fermix.noise.StaticKey

/**
 * The phone's device keys as the pairing ceremony uses them, one per alias, each alias a device key's
 * ([DEVICE_KEY_ALIAS_PREFIX] and on): [DeviceKeys] over AndroidKeyStore on the phone, and a software key in
 * core-session's JVM tests. Each call is a blocking Keystore operation; the caller runs it off the main thread.
 */
interface DeviceKeyFacade {
    /**
     * Generates the X25519 agree-only key [alias], which must be new, attested with [challenge]
     * (AttestationChallenge), and returns its chain. A key generated before a failure is deleted.
     */
    fun generate(
        alias: String,
        challenge: ByteArray,
    ): AttestedKey

    /** Deletes [alias]'s key; one the Keystore does not hold is no error. */
    fun delete(alias: String)

    fun exists(alias: String): Boolean

    /** [alias]'s key as the operation the Noise handshake runs; a missing key fails loud. */
    fun staticKey(alias: String): StaticKey
}

/**
 * A key just generated under [alias], and its attestation chain, leaf first, as the DER of each
 * certificate: what `pair_request` carries as `attestation.cert_lengths` and its raw tail (design section
 * 7). Held as copies, and handed out as copies.
 */
class AttestedKey(
    val alias: String,
    chain: List<ByteArray>,
) {
    private val certificates = chain.map { it.copyOf() }

    init {
        require(alias.isNotBlank()) { "an attested key has an alias" }
        require(certificates.isNotEmpty()) { "an attested key has a chain" }
    }

    /** Each certificate's length, leaf first: `attestation.cert_lengths`. */
    val certLengths: List<Int> = certificates.map { it.size }

    /** The chain, leaf first, each certificate in a new array. */
    fun chain(): List<ByteArray> = certificates.map { it.copyOf() }

    /** The chain's DER back to back, leaf first: `pair_request`'s raw tail. */
    fun tail(): ByteArray = certificates.fold(ByteArray(0)) { joined, certificate -> joined + certificate }
}
