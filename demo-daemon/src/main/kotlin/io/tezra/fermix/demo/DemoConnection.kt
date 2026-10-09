package io.tezra.fermix.demo

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.security.GeneralSecurityException

/** Close codes the demo sends (PROTOCOL.md "Close codes"). */
internal const val NORMAL = 1000
internal const val PROTOCOL_ERROR = 1002
internal const val HANDSHAKE_DEADLINE = 1008
internal const val REVOKED = 4003
internal const val NOT_PAIRED = 4004

/**
 * The most frames a connection carries either way before it closes `1000`, as an hour's lifetime would: below
 * the 2^20 at which Noise rekeys a direction, which the demo's ciphers do not.
 */
internal const val MAX_FRAMES = 1_000_000L

/** How a connection's next read came out: a frame, the phone gone, nothing within the wait, or a bound reached. */
internal sealed interface Read {
    class Got(
        val frame: ClientFrame,
    ) : Read

    data object Gone : Read

    data object Quiet : Read

    class Bound(
        val code: Int,
        val reason: String,
    ) : Read
}

/**
 * One socket to one demo Fermix, from message 1 to its close: the prelude read within the handshake deadline
 * (`1008` past it), then the pairing ceremony (DemoCeremony), whose handshake is answered after the pause
 * Connecting's "Checking" shows, or a paired session (DemoSession), whose handshake is answered at once. Every
 * frame it writes goes at its next seq, sealed, as one run when it is long. Every frame it reads is held to the
 * client's rules as the daemon holds it (PROTOCOL.md "Envelope, ordering, and version negotiation"): one that does
 * not open, that the contract refuses, whose seq is not the last one's plus one, or whose `v` is not the session's
 * closes it `1002`, with no `error`. It ends at the phone's close, its own close, the idle bound or the lifetime,
 * and leaves its Fermix's connections as it ends.
 */
internal class DemoConnection(
    val home: DemoHome,
    private val link: MemoryLink,
    val parts: DemoParts,
) {
    private val end = SealedEnd(link)
    private var seq = 0uL
    private var received = 0uL
    private var open = true

    /** Whether the connection is still open: neither end has closed it. */
    val isOpen: Boolean get() = open && !link.closed.isCompleted

    /** The phone's Noise key in hex, once the handshake read it. */
    var phoneKey: String = ""
        private set

    suspend fun run() {
        try {
            val first = firstMessage() ?: return
            when (first.getOrNull(PRELUDE_BYTES - 1)) {
                PRELUDE_IKPSK2 -> DemoCeremony(this).pair(first)
                PRELUDE_IK -> paired(first)
                else -> close(PROTOCOL_ERROR, "invalid mobile prelude")
            }
        } finally {
            home.connections -= this
            if (open) close(NORMAL, "")
        }
    }

    /** Message 1 within the handshake deadline, or none: the phone left, or the deadline passed, closed `1008`. */
    private suspend fun firstMessage(): ByteArray? {
        var gone = false
        val first = withTimeoutOrNull(parts.times.handshakeDeadline) { end.nextMessage().also { gone = it == null } }
        if (first == null && !gone) close(HANDSHAKE_DEADLINE, "mobile handshake deadline")
        return first
    }

    /** A paired phone's IK handshake, refused for a key that unpaired, then its session. */
    private suspend fun paired(first: ByteArray) {
        if (!respond(first, IkResponder(home.fermix.gatewayKey))) return
        if (phoneKey in home.forgotten) return close(NOT_PAIRED, "device not paired")
        DemoSession(this).serve()
    }

    /**
     * Answers message 1 with [responder]'s message 2; false, the socket closed `1002`, for a message that does not
     * open with the key or the secret this Fermix holds.
     */
    fun respond(
        first: ByteArray,
        responder: IkResponder,
    ): Boolean =
        try {
            phoneKey = end.respond(first, responder).initiatorStatic.toHexString()
            true
        } catch (refused: GeneralSecurityException) {
            close(PROTOCOL_ERROR, "mobile protocol error: ${refused.javaClass.simpleName}")
            false
        } catch (malformed: IllegalArgumentException) {
            close(PROTOCOL_ERROR, "mobile protocol error: ${malformed.javaClass.simpleName}")
            false
        }

    /**
     * The phone's next frame within [withinMs], or why there is none. The wait is a select, never a cancelled
     * read: a frame that arrives as the wait ends is either read or left for the next read, never lost, as a lost
     * one would leave the Noise nonces apart.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun receive(withinMs: Long): Read {
        if (end.frames >= MAX_FRAMES) return Read.Bound(NORMAL, "Noise session lifetime reached")
        return select {
            link.toDaemon.onReceiveCatching { result -> result.getOrNull()?.let(::read) ?: Read.Gone }
            onTimeout(withinMs) { Read.Quiet }
        }
    }

    /**
     * [message] opened and read, or the `1002` it earns: a seal that does not open, a frame the contract refuses, a
     * seq out of order, or a `v` other than the session's. A `hello` of another version is its session's to refuse
     * with the typed error (DemoSession).
     */
    private fun read(message: ByteArray): Read =
        try {
            inOrder(end.open(message))
        } catch (refused: GeneralSecurityException) {
            refusal(refused)
        } catch (refused: ProtocolException) {
            refusal(refused)
        }

    /** [frame] at its place: the seq after the last one's, at the session's version. */
    private fun inOrder(frame: ClientFrame): Read {
        received++
        val versioned = frame.v == DAEMON_VERSION || frame.event is ClientEvent.Hello
        return if (frame.seq == received && versioned) Read.Got(frame) else refusal(null)
    }

    /** [event] at the next seq, as one frame or one run; nothing once the connection closed. */
    fun send(event: ServerEvent.Known) {
        if (!open) return
        framesOf(seq + 1uL, event).forEach { frame ->
            seq++
            end.deliverSealed(frame)
        }
    }

    /** A frame whose `t` no catalogue holds, [t], at the next seq: a newer daemon's event. */
    fun sendUnknown(t: String) {
        if (!open) return
        seq++
        end.deliverSealed(unknownFrame(seq, t))
    }

    /** [event] with [raw] as its frame's tail: a blob's chunk. */
    fun sendWithRaw(
        event: ServerEvent.Known,
        raw: ByteArray,
    ) {
        if (!open) return
        seq++
        end.deliverSealed(serverFrame(DAEMON_VERSION, seq, event, raw))
    }

    fun close(
        code: Int,
        reason: String,
    ) {
        open = false
        home.connections -= this
        link.closeByDaemon(code, reason)
    }
}

/** A frame the daemon will not take ([cause] when it threw): the connection closes `1002`, with no `error`. */
private fun refusal(cause: Exception?): Read =
    Read.Bound(PROTOCOL_ERROR, "mobile protocol error" + cause?.let { ": ${it.javaClass.simpleName}" }.orEmpty())
