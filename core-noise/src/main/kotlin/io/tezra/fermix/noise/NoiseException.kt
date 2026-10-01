package io.tezra.fermix.noise

/**
 * Every refusal of what the peer or the caller hands the Noise layer, each its own type, none
 * recovered from inside the layer. A size refusal comes before any state changes. Anything else that
 * a handshake throws ends it, and an authentication failure or a spent nonce ends a session. A fault
 * of the phone's own keys or providers, a Keystore error for one, is not a refusal and passes through
 * as the platform's exception; it ends a handshake all the same.
 */
sealed class NoiseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** A peer key, a PSK or an X25519 output that is not 32 bytes. */
    class BadKeyLength(
        val name: String,
        val size: Int,
    ) : NoiseException("$name is $size bytes, not $KEY_BYTES")

    /**
     * The peer's key is a low-order point, which contributes nothing: the provider refused it, which
     * is then the cause, or X25519 gave all zeros.
     */
    class LowOrderKey(
        cause: Throwable? = null,
    ) : NoiseException("the peer's X25519 key is a low-order point", cause)

    /** A payload, plaintext or message past its bound. */
    class MessageTooLarge(
        val size: Int,
        val max: Int,
    ) : NoiseException("$size bytes is past the bound of $max")

    /** A message too short to hold what its place in the protocol requires. */
    class MessageTooShort(
        val size: Int,
        val min: Int,
    ) : NoiseException("$size bytes is short of the $min required")

    /** A tag that did not verify. */
    class AuthenticationFailed(
        cause: Throwable,
    ) : NoiseException("the message failed authentication", cause)

    /**
     * A call made at nonce 2^64 - 1, which Noise reserves: it is never used, and a direction never
     * wraps. The call at 2^64 - 2 was the direction's last.
     */
    class NonceExhausted : NoiseException("the nonce reached 2^64 - 1, which Noise reserves")

    /**
     * A handshake step called before its turn, after the handshake finished, or after it was closed;
     * or a session called after it was closed.
     */
    class OutOfOrder(
        detail: String,
    ) : NoiseException(detail)

    /** A handshake or session called again after a refusal ended it. */
    class UsedAfterFailure : NoiseException("an earlier failure ended this handshake or session")
}
