package io.tezra.fermix.session

import io.tezra.fermix.noise.NoiseException
import io.tezra.fermix.noise.NoiseSession
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Decoded
import io.tezra.fermix.protocol.EventPartAssembler
import io.tezra.fermix.protocol.MAX_EVENT_PARTS
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.decodeServerEvent
import io.tezra.fermix.protocol.encodeClientEvent

/** The protocol version this app speaks (design section 7, D1); the daemon's window must hold it. */
internal const val SESSION_VERSION = 2

/**
 * A frame or an event the session refuses on rules that are the session's own rather than the codec's:
 * a seq out of order, a frame of another version, an event out of its place. Like a refusal of the
 * codec's, it closes the connection with `1002` (PROTOCOL.md "Errors").
 */
internal class SessionProtocolError(
    message: String,
) : Exception(message)

/** What one read of a connection gives: a whole event, or how the connection ended. */
internal sealed interface Input {
    class Frame(
        val decoded: Decoded<ServerEvent>,
    ) : Input

    class End(
        val ending: Ending,
    ) : Input
}

/**
 * One connection's frames after the handshake: each event encoded by core-protocol, sealed by the
 * Noise session and sent as one WebSocket message, and each message received opened, decoded, its
 * seq checked to be exactly the last one's plus one, and an `event_part` run joined. Both directions
 * count `seq` from 1 on every connection (PROTOCOL.md "Envelope, ordering, and version negotiation").
 * Driven from one coroutine at a time, as the Noise session requires.
 */
internal class SecureChannel(
    private val link: Link,
    private val noise: NoiseSession,
) {
    private var sentSeq = 0uL
    private var receivedSeq = 0uL
    private val assembler = EventPartAssembler()

    /** Bytes the link holds unwritten (Link.queuedBytes). */
    val queuedBytes: Long get() = link.queuedBytes

    /**
     * Sends [event] at the next seq, with [raw] as its tail: a protocol v2 `pair_request` carries its
     * attestation chain there, and an `attach_chunk` its bytes. A link that is closing takes nothing; its
     * reader then sees the link end and says why, so nothing here waits on the answer.
     */
    fun send(
        event: ClientEvent,
        raw: ByteArray = ByteArray(0),
    ) {
        sentSeq++
        link.send(noise.encrypt(encodeClientEvent(SESSION_VERSION, sentSeq, event, raw)))
    }

    /**
     * The next whole event, a run's logical event once its last part is in; or the link's end, or the
     * refusal of a frame the codec, the Noise session or the seq order would not take, which ends the
     * connection with `1002`.
     */
    suspend fun receive(): Input =
        try {
            whole()?.let { Input.Frame(it) } ?: Input.End(Ending.Transport(link.closed.await()))
        } catch (refused: ProtocolException) {
            Input.End(Ending.ProtocolError(refused.toString()))
        } catch (refused: NoiseException) {
            Input.End(Ending.ProtocolError(refused.toString()))
        } catch (refused: SessionProtocolError) {
            Input.End(Ending.ProtocolError(refused.toString()))
        }

    private suspend fun whole(): Decoded<ServerEvent>? {
        repeat(MAX_EVENT_PARTS) {
            val message = link.incoming.receiveCatching().getOrNull() ?: return null
            val frame = decodeServerEvent(noise.decrypt(message))
            val due = receivedSeq + 1u
            if (frame.seq != due) throw SessionProtocolError("seq ${frame.seq} arrived where $due was due")
            receivedSeq = frame.seq
            assembler.accept(frame)?.let { whole -> return whole }
        }
        // The assembler refuses a run longer than its bound, so a whole event is in by now.
        throw SessionProtocolError("no whole event in $MAX_EVENT_PARTS frames")
    }
}
