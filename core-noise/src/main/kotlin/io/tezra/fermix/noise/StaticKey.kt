package io.tezra.fermix.noise

/**
 * The phone's static X25519 key as an operation, not as key material: on a phone the private half
 * lives in the TEE and only agreements come out of it (design section 6.1). IK uses it twice per
 * handshake, for ss and se.
 */
interface StaticKey {
    /** The raw 32-byte public key, which message 1 carries encrypted. */
    val publicKey: ByteArray

    /**
     * X25519 with a peer's raw 32-byte public key; the 32-byte shared secret, in a new array the caller
     * owns: the handshake zeroes it once mixed.
     */
    fun agree(peerPublicKey: ByteArray): ByteArray
}
