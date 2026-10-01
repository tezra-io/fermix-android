package io.tezra.fermix.noise

import java.nio.ByteBuffer

/**
 * A Noise message's bound (PROTOCOL.md "Stack and bounds"), and one WebSocket message's too, past
 * which the daemon closes with 1009 (PROTOCOL.md "Transport", the "One WebSocket message" row). A
 * transport message and message 2 are each a whole WebSocket message; message 1 shares its WebSocket
 * message with the 5-byte prelude, so its Noise part is held 5 bytes under this.
 */
internal const val MAX_MESSAGE_BYTES = 65_535

/** A mobile plaintext frame's bound: a Noise message less its tag. */
internal const val MAX_PLAINTEXT_BYTES = MAX_MESSAGE_BYTES - TAG_BYTES

/** Each direction rekeys after exactly this many frames, 2^20, as the daemon does. */
internal const val REKEY_AFTER_FRAMES = 1 shl 20

private const val SAS_LABEL = "fermix-mobile-sas-v1"
private const val SAS_MODULUS = 1_000_000u
private const val SAS_DIGITS = 6

/**
 * One Noise session after Split: frames go out on the send cipher and come in on the receive
 * cipher, each direction's nonce starting at 0. Each direction counts its frames and, before the
 * frame after the 2^20th, rekeys (rev 34 section 11.3) and keeps its nonce; nothing rekeys on time.
 * A refusal from either cipher, a tag that fails or a spent nonce, ends the session (rev 34 section
 * 5.1), and so does [close]: an ended session zeroes both keys and refuses every later call. One
 * session is driven from one thread.
 *
 * The frame count and the rekey it triggers live here, with the keys and nonces they act on,
 * although design section 12.2 and onboarding section 5 list "rekey every 2^20 frames" under
 * core-session: a session that could be driven past its rekey from outside would be one whose
 * keys outlive their bound. core-session owns what is time-based, the hourly close.
 */
class NoiseSession internal constructor(
    internal val send: CipherState,
    internal val receive: CipherState,
    handshakeHash: ByteArray,
) : AutoCloseable {
    private val hash = handshakeHash.copyOf()
    private var state = State.OPEN

    // The frames each direction carried since its last rekey. Internal, so a test can stand one at
    // the rekey boundary instead of sealing 2^20 frames.
    internal var sendFrames = 0
    internal var receiveFrames = 0

    /** The six-digit code both sides show while pairing (PROTOCOL.md "Noise modes and pairing"). */
    val sas: String = shortAuthenticationString(hash)

    fun handshakeHash(): ByteArray = hash.copyOf()

    /** Seals one mobile plaintext frame, at most 65,519 bytes, into one Noise message. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        requireOpen()
        if (plaintext.size > MAX_PLAINTEXT_BYTES) {
            throw NoiseException.MessageTooLarge(plaintext.size, MAX_PLAINTEXT_BYTES)
        }
        if (sendFrames >= REKEY_AFTER_FRAMES) {
            send.rekey()
            sendFrames = 0
        }
        val message = endingOnRefusal { send.encryptWithAd(ByteArray(0), plaintext) }
        sendFrames++
        return message
    }

    /** Opens one Noise message, at most 65,535 bytes; a tag that fails, or a spent nonce, ends the session. */
    fun decrypt(message: ByteArray): ByteArray {
        requireOpen()
        requireMessageSize(message, TAG_BYTES)
        if (receiveFrames >= REKEY_AFTER_FRAMES) {
            receive.rekey()
            receiveFrames = 0
        }
        val plaintext = endingOnRefusal { receive.decryptWithAd(ByteArray(0), message) }
        receiveFrames++
        return plaintext
    }

    /** Ends the session where it stands and zeroes both keys; every later call is refused. */
    override fun close() {
        wipe()
        state = State.CLOSED
    }

    // A cipher refuses only a tag that fails or a spent nonce, and either ends the session.
    private inline fun <T> endingOnRefusal(operation: () -> T): T =
        try {
            operation()
        } catch (refusal: NoiseException) {
            wipe()
            state = State.FAILED
            throw refusal
        }

    private fun wipe() {
        send.wipe()
        receive.wipe()
    }

    private fun requireOpen() {
        if (state == State.FAILED) throw NoiseException.UsedAfterFailure()
        if (state == State.CLOSED) throw NoiseException.OutOfOrder("the session is closed")
    }

    private enum class State { OPEN, FAILED, CLOSED }
}

/** Refuses a Noise message past 65,535 bytes or shorter than [min]. */
internal fun requireMessageSize(
    message: ByteArray,
    min: Int,
) {
    if (message.size > MAX_MESSAGE_BYTES) throw NoiseException.MessageTooLarge(message.size, MAX_MESSAGE_BYTES)
    if (message.size < min) throw NoiseException.MessageTooShort(message.size, min)
}

/** HMAC-SHA256 of the label under the handshake hash; its first four bytes as a big-endian u32, mod 10^6. */
internal fun shortAuthenticationString(handshakeHash: ByteArray): String {
    val digest = hmacSha256(handshakeHash, SAS_LABEL.encodeToByteArray())
    val value = ByteBuffer.wrap(digest).int.toUInt()
    return (value % SAS_MODULUS).toString().padStart(SAS_DIGITS, '0')
}
