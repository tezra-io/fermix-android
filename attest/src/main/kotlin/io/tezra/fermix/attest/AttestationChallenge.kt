package io.tezra.fermix.attest

import java.security.MessageDigest

/** What the challenge hashes before the secret (design section 6.2, check 4). */
private const val CHALLENGE_LABEL = "fermix-mobile-attest-v1"

/** A pairing link's secret: the one-time 32-byte PSK. */
private const val SECRET_BYTES = 32

/**
 * The attestation challenge of a pairing attempt, `SHA-256("fermix-mobile-attest-v1" ‖ qr_secret)`, set on
 * the key when it is generated, so the attestation is bound to this pairing window and cannot be minted
 * before it (design section 6.2, check 4). The daemon computes the same from the secret it issued.
 */
object AttestationChallenge {
    /** The challenge for [secret], in a new array; [secret] is read, never kept. */
    fun of(secret: ByteArray): ByteArray {
        require(secret.size == SECRET_BYTES) { "a pairing secret is $SECRET_BYTES bytes, not ${secret.size}" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CHALLENGE_LABEL.encodeToByteArray())
        return digest.digest(secret)
    }
}
