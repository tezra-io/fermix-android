package io.tezra.fermix.noise

import java.security.KeyPair
import java.security.PrivateKey
import javax.crypto.KeyAgreement
import javax.security.auth.DestroyFailedException

/** The first four bytes of the clear prelude. */
private const val PRELUDE_MAGIC = "FXM1"

/** The prelude: its magic, then the mode byte. */
private const val PRELUDE_BYTES = PRELUDE_MAGIC.length + 1

/** The authenticated prologue is this ASCII label and then the prelude. */
private const val PROLOGUE_LABEL = "fermix-mobile-v1"

/**
 * The largest message 1 payload. The prelude and message 1 travel as one WebSocket message, which
 * the daemon closes past 65,535 bytes (PROTOCOL.md "Transport", the "One WebSocket message" row), so
 * the payload gets what that leaves after the prelude, e, the encrypted s, and the payload's tag.
 */
private const val MAX_FIRST_PAYLOAD_BYTES =
    MAX_MESSAGE_BYTES - PRELUDE_BYTES - KEY_BYTES - (KEY_BYTES + TAG_BYTES) - TAG_BYTES

/** The smallest message 2: the responder's e, then the tag of an empty payload. */
private const val MIN_SECOND_MESSAGE_BYTES = KEY_BYTES + TAG_BYTES

/**
 * The two handshakes the phone runs (PROTOCOL.md "Noise modes and pairing"): the mode byte of the
 * prelude, and the Noise protocol name. Design section 7 changes no Noise shape for protocol v2, so
 * the prologue label stays "fermix-mobile-v1".
 */
internal enum class NoiseMode(
    private val modeByte: Byte,
    val protocolName: String,
    val hasPsk: Boolean,
) {
    IK(1, "Noise_IK_25519_ChaChaPoly_SHA256", false),
    IKPSK2(2, "Noise_IKpsk2_25519_ChaChaPoly_SHA256", true),
    ;

    /** The five clear bytes before message 1, and nowhere else on the wire. */
    fun prelude(): ByteArray = PRELUDE_MAGIC.encodeToByteArray() + modeByte

    fun prologue(): ByteArray = PROLOGUE_LABEL.encodeToByteArray() + prelude()
}

/**
 * A finished handshake: the payload of the daemon's message 2, and the session for every frame after.
 * The protocol v1 daemon always sends an empty payload; the vectors carry one, so it is passed on as
 * it came.
 */
class HandshakeResult internal constructor(
    val payload: ByteArray,
    val session: NoiseSession,
)

/**
 * The phone's side of Noise_IK and Noise_IKpsk2 over X25519, ChaChaPoly and SHA-256; the phone is
 * always the initiator. It writes message 1, prelude first, then reads message 2:
 *
 *     IK      <- s  ...  -> e, es, s, ss   <- e, ee, se
 *     IKpsk2  <- s  ...  -> e, es, s, ss   <- e, ee, se, psk
 *
 * Each step runs once and in order. A step refused for its input's size changes nothing; any other
 * refusal ends the handshake. A handshake that ends, done, failed or closed, zeroes its chaining key,
 * its cipher key and its copy of the pairing secret (design section 12.4), destroys its ephemeral
 * private key and lets go of the ephemeral pair; one given up between its messages is closed. On a
 * phone, Conscrypt's destroy() zeroes that key; on the JVM, SunEC's key has no destroy(), so there its
 * bytes last until it is collected. One handshake is driven from one thread.
 */
class InitiatorHandshake private constructor(
    private val mode: NoiseMode,
    private val staticKey: StaticKey,
    responderStatic: ByteArray,
    ephemeral: KeyPair,
    psk: ByteArray?,
) : AutoCloseable {
    // Both keys are checked before the PSK is copied, so a refused key leaves no copy behind.
    private val staticPublic = staticKey.publicKey.also { requireKeyLength("static public key", it) }
    private val ephemeralPublic = rawX25519PublicKey(ephemeral.public)
    private val responderStatic = responderStatic.copyOf()

    // Null once the handshake has ended: wipe() lets go of the pair, so nothing here keeps its key reachable.
    private var ephemeral: KeyPair? = ephemeral

    // Internal, with the symmetric state, so a test can see them zeroed.
    internal val psk: ByteArray? = psk?.copyOf()
    internal val symmetric = SymmetricState(mode.protocolName)
    private var step = Step.WRITE_FIRST

    init {
        symmetric.mixHash(mode.prologue())
        symmetric.mixHash(this.responderStatic)
    }

    /**
     * Message 1: the clear prelude, then e, es, s, ss and [payload]. The protocol v1 daemon accepts
     * only an empty payload, and in IKpsk2 counts any other against the pairing lockout; PROTOCOL.md
     * does not say so, and the vectors carry one, so any payload within the bound is sealed.
     */
    fun writeFirstMessage(payload: ByteArray): ByteArray {
        requireStep(Step.WRITE_FIRST)
        if (payload.size > MAX_FIRST_PAYLOAD_BYTES) {
            throw NoiseException.MessageTooLarge(payload.size, MAX_FIRST_PAYLOAD_BYTES)
        }
        // What any refusal below leaves behind; the step advances only once the message is whole.
        step = Step.FAILED
        try {
            mixEphemeral(ephemeralPublic)
            mixAgreement(ephemeralAgreement(responderStatic))
            val encryptedStatic = symmetric.encryptAndHash(staticPublic)
            mixAgreement(staticKey.agree(responderStatic))
            val encryptedPayload = symmetric.encryptAndHash(payload)
            step = Step.READ_SECOND
            return mode.prelude() + ephemeralPublic + encryptedStatic + encryptedPayload
        } finally {
            if (step == Step.FAILED) wipe()
        }
    }

    /** Message 2: e, ee, se, then psk for IKpsk2, and its payload; Split gives the session. */
    fun readSecondMessage(message: ByteArray): HandshakeResult {
        requireStep(Step.READ_SECOND)
        requireMessageSize(message, MIN_SECOND_MESSAGE_BYTES)
        // As in writeFirstMessage: failed until the session exists.
        step = Step.FAILED
        try {
            val responderEphemeral = message.copyOf(KEY_BYTES)
            mixEphemeral(responderEphemeral)
            mixAgreement(ephemeralAgreement(responderEphemeral))
            mixAgreement(staticKey.agree(responderEphemeral))
            // psk2: after the last DH of message 2.
            if (psk != null) symmetric.mixKeyAndHash(psk)
            val payload = symmetric.decryptAndHash(message.copyOfRange(KEY_BYTES, message.size))
            val (send, receive) = symmetric.split()
            step = Step.DONE
            return HandshakeResult(payload, NoiseSession(send, receive, symmetric.handshakeHash()))
        } finally {
            // Done or failed, the handshake never needs a secret again.
            wipe()
        }
    }

    /** Ends the handshake where it stands and zeroes what it holds; every later step is refused. */
    override fun close() {
        wipe()
        step = Step.CLOSED
    }

    // In a PSK handshake e is mixed as a key too (rev 34 section 9.2).
    private fun mixEphemeral(publicKey: ByteArray) {
        symmetric.mixHash(publicKey)
        if (mode.hasPsk) symmetric.mixKey(publicKey)
    }

    private fun mixAgreement(secret: ByteArray) {
        try {
            symmetric.mixKey(requireSharedSecret(secret))
        } finally {
            secret.fill(0)
        }
    }

    // Every step that agrees runs before wipe(), and the step it leaves refuses any later one.
    private fun ephemeralAgreement(peerPublicKey: ByteArray): ByteArray {
        val privateKey = checkNotNull(ephemeral) { "the handshake has ended and holds no ephemeral" }.private
        return x25519(KeyAgreement.getInstance(XDH), privateKey, peerPublicKey)
    }

    // close() after done or failed wipes again; the pair is already gone, so its key is destroyed once.
    private fun wipe() {
        psk?.fill(0)
        symmetric.wipe()
        val spent = ephemeral ?: return
        ephemeral = null
        destroyEphemeral(spent.private)
    }

    /**
     * Destroys the spent ephemeral private key. On a phone it is Conscrypt's, which holds an XDH private
     * key as raw bytes on the heap, and destroy() zeroes them. On the JVM it is SunEC's, whose
     * XECPrivateKey does not implement destroy(), so the Destroyable default throws
     * DestroyFailedException. That refusal means only that this provider keeps no destroyable copy,
     * and is taken as such: the handshake has let go of the pair, and the key's bytes last until it is
     * collected. Any other exception propagates.
     */
    private fun destroyEphemeral(privateKey: PrivateKey) {
        try {
            privateKey.destroy()
        } catch (expected: DestroyFailedException) {
            // This provider keeps no destroyable copy (SunEC on the JVM); see the KDoc above.
        }
    }

    private fun requireStep(expected: Step) {
        if (step == Step.FAILED) throw NoiseException.UsedAfterFailure()
        if (step != expected) throw NoiseException.OutOfOrder("the handshake is ${step.label}, not ${expected.label}")
    }

    private enum class Step(
        val label: String,
    ) {
        WRITE_FIRST("writing message 1"),
        READ_SECOND("reading message 2"),
        DONE("done"),
        FAILED("failed"),
        CLOSED("closed"),
    }

    companion object {
        /** A paired device's session: IK to the daemon's static key, gateway_pk, on a fresh ephemeral. */
        fun ik(
            staticKey: StaticKey,
            responderStatic: ByteArray,
        ): InitiatorHandshake = ik(staticKey, responderStatic, newEphemeralKeyPair())

        /**
         * A pairing: IKpsk2 to gateway_pk, with the pairing link's one-time secret as the PSK, on a fresh
         * ephemeral. The handshake keeps its own copy of [psk]; the caller zeroes its own.
         */
        fun ikpsk2(
            staticKey: StaticKey,
            responderStatic: ByteArray,
            psk: ByteArray,
        ): InitiatorHandshake = ikpsk2(staticKey, responderStatic, psk, newEphemeralKeyPair())

        /**
         * IK on a given ephemeral, which the vector tests fix. Internal: an ephemeral used twice with
         * one responder key seals two message 1 payloads under one key and nonce.
         */
        internal fun ik(
            staticKey: StaticKey,
            responderStatic: ByteArray,
            ephemeral: KeyPair,
        ): InitiatorHandshake {
            requireKeyLength("responder static key", responderStatic)
            return InitiatorHandshake(NoiseMode.IK, staticKey, responderStatic, ephemeral, null)
        }

        /** IKpsk2 on a given ephemeral, internal for the reason [ik]'s is. */
        internal fun ikpsk2(
            staticKey: StaticKey,
            responderStatic: ByteArray,
            psk: ByteArray,
            ephemeral: KeyPair,
        ): InitiatorHandshake {
            requireKeyLength("responder static key", responderStatic)
            requireKeyLength("PSK", psk)
            return InitiatorHandshake(NoiseMode.IKPSK2, staticKey, responderStatic, ephemeral, psk)
        }
    }
}
