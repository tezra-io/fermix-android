package io.tezra.fermix.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.security.KeyPair

/** A vector by its pattern. */
internal fun vector(pattern: String): NoiseVector =
    loadNoiseVectors().singleOrNull { it.pattern == pattern } ?: error("no $pattern vector in the vendored file")

/** The handshake mode a vector's pattern names. */
internal fun NoiseVector.mode(): NoiseMode =
    when (pattern) {
        "ik" -> NoiseMode.IK
        "ikpsk2" -> NoiseMode.IKPSK2
        else -> error("no handshake mode for pattern $pattern")
    }

/** The phone's side of a vector's handshake: the vector's fixed static key and ephemeral, unless given others. */
internal fun initiatorFor(
    vector: NoiseVector,
    static: StaticKey = SoftwareX25519Key(vector.initStaticPrivate),
    ephemeral: KeyPair = SoftwareX25519Key(vector.initEphemeralPrivate).keyPair(),
): InitiatorHandshake =
    when (vector.mode()) {
        NoiseMode.IK -> {
            InitiatorHandshake.ik(static, vector.respStaticPublic, ephemeral)
        }

        NoiseMode.IKPSK2 -> {
            InitiatorHandshake.ikpsk2(static, vector.respStaticPublic, checkNotNull(vector.psk), ephemeral)
        }
    }

/** The daemon's side of a vector's handshake, on the vector's fixed keys. */
internal fun responderFor(vector: NoiseVector): TestResponder =
    TestResponder(
        vector.mode(),
        SoftwareX25519Key(vector.respStaticPrivate),
        SoftwareX25519Key(vector.respEphemeralPrivate),
        vector.psk,
    )

/** A vector's handshake run to the end against the vector's own message 2. */
internal fun completedHandshake(vector: NoiseVector): HandshakeResult {
    val handshake = initiatorFor(vector)
    handshake.writeFirstMessage(vector.first.payload)
    return handshake.readSecondMessage(vector.second.wire)
}

/** Both ends of a session: the phone's, and the test responder's two transport ciphers. */
internal class Connected(
    val session: NoiseSession,
    val responderReceive: CipherState,
    val responderSend: CipherState,
)

/** A vector's handshake between the initiator and the test responder, checked against the vector. */
internal fun connect(vector: NoiseVector): Connected {
    val handshake = initiatorFor(vector)
    val responder = responderFor(vector)
    responder.readFirst(handshake.writeFirstMessage(vector.first.payload))
    val second = responder.writeSecond(vector.second.payload)
    check(second.contentEquals(vector.second.wire)) { "the test responder's message 2 is not the vector's" }
    val session = handshake.readSecondMessage(second).session
    val (receive, send) = responder.split()
    return Connected(session, receive, send)
}

/**
 * Counts [frames] more sent frames without sealing them, as that many real frames would, so a test
 * reaches the rekey boundary without sealing 2^20 frames. It only adds to the session's count, so the
 * count's start, its step and its reset after a rekey stay the session's own.
 */
internal fun NoiseSession.skipSendFrames(frames: Int) {
    sendFrames += frames
}

/** As [skipSendFrames], for the frames received. */
internal fun NoiseSession.skipReceiveFrames(frames: Int) {
    receiveFrames += frames
}

/** Byte arrays compared as hex, so a failure shows where they differ. */
internal fun assertHex(
    expected: ByteArray,
    actual: ByteArray,
) = assertEquals(expected.toHexString(), actual.toHexString())

internal fun ByteArray.isZero(): Boolean = all { it == 0.toByte() }

internal fun assertZero(
    what: String,
    bytes: ByteArray,
) = assertTrue(bytes.isZero(), "$what is not zeroed: ${bytes.toHexString()}")
