package io.tezra.fermix.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A remote stream's frame length at most: it counts the type byte and the payload (PROTOCOL.md "Remote reach"). */
const val MAX_ROUTE_FRAME_LENGTH = 65_536

/** How long a close has to send what is queued, its close frame and the finish before the sending half is reset. */
const val ROUTE_CLOSE_WAIT_MS = 5_000L

private const val LENGTH_BYTES = 4
private const val TYPE_BYTES = 1
private const val MESSAGE_FRAME = 1
private const val CLOSE_FRAME = 2
private const val CODE_BYTES = 2
private const val MAX_CLOSE_REASON_BYTES = 123

/** The smallest length a frame has: its type and at least one byte of payload. */
private const val MIN_FRAME_LENGTH = 2

/** The daemon's code for a frame that breaks the stream's rules, and its reason (PROTOCOL.md "Close codes"). */
private const val PROTOCOL_ERROR = 1002
private const val PROTOCOL_ERROR_REASON = "mobile protocol error"

/** This side's code for a frame past the bound, which ends the stream with no close frame. */
private const val MESSAGE_TOO_BIG = 1009

/**
 * The two halves of one bidirectional stream, as [RouteStream] uses them: iroh's in production, a test's pipe on
 * the JVM. A read or a write that can no longer complete throws; [reset] and [stopReading] reach a half that is
 * still open, and say false for one that had already ended.
 */
interface RouteHalves {
    /** Exactly [size] bytes, or a throw once the stream ends or fails first. */
    suspend fun readExact(size: Int): ByteArray

    suspend fun writeAll(bytes: ByteArray)

    /** Ends the sending half after what was written. */
    suspend fun finish()

    /**
     * Once the sending half is finished, waits until the far end has taken all of it, or has stopped it: true when it
     * stopped it, as the helper does once the daemon has closed, and a finish then frees nothing until a reset does.
     */
    suspend fun stopped(): Boolean

    /** Abandons the sending half: what is not yet sent is dropped. */
    suspend fun reset(): Boolean

    /** Tells the far end to stop sending: what still arrives is dropped. */
    suspend fun stopReading(): Boolean

    /** Lets go of the stream's native handles, once nothing reads or writes them. */
    fun release()
}

/**
 * One remote session's stream to a daemon, carrying what one WebSocket carries (PROTOCOL.md "Remote reach"):
 * `uint32be length | type | payload` frames, a type-1 frame one Noise message of 1 to 65,535 bytes, a type-2
 * frame a close, `uint16be` code then a UTF-8 reason of at most 123 bytes. The length is judged before anything
 * past it is read or any room made for it: past 65,536 the stream ends with no close frame, under 2 it is closed
 * `1002`, as is a frame of another type, a close shorter than its code or one whose reason is not UTF-8. It has
 * the surface of core-transport's WebSocket [Connection], and whoever holds it closes it, however it ended. Its
 * reader, its writer and its ending run in [scope]; once both halves are done it lets go of their handles.
 * [notes] are what opening it noted for the Diagnostics.
 */
class RouteStream(
    private val halves: RouteHalves,
    scope: CoroutineScope,
    val notes: List<String> = emptyList(),
) : AutoCloseable {
    private val messages = Channel<ByteArray>(INCOMING_BACKLOG)
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private val queued = AtomicLong(0)
    private val ended = CompletableDeferred<TransportException>()
    private val resetSending = AtomicBoolean(false)
    private val reading = scope.launch { read() }
    private val writing = scope.launch { write() }
    private val life = scope.launch { live() }

    /**
     * Each message the daemon sends, in order. A close from the daemon, a refused frame or a stream that ended
     * ends it with no error, after the messages it already holds; this side's close drops the messages not read.
     */
    val incoming: ReceiveChannel<ByteArray> get() = messages

    /**
     * Completes once, with how the stream ended: [TransportException.Closed] with the daemon's code and reason, or
     * this side's when it closed first or refused a frame, its detail saying which frame and why; or
     * [TransportException.Unreachable] when the stream or its connection ended with no close frame.
     */
    val closed: Deferred<TransportException> get() = ended

    /** Bytes [send] accepted that the writer has not yet handed to the stream. */
    val queuedBytes: Long get() = queued.get()

    /** Queues one message as a type-1 frame; false once the stream is ending, and [closed] says why. */
    fun send(message: ByteArray): Boolean {
        require(message.size in 1..MAX_MESSAGE_BYTES) {
            "a ${message.size}-byte message is outside the stream's 1 to $MAX_MESSAGE_BYTES"
        }
        if (ended.isCompleted) return false
        val frame = frameOf(MESSAGE_FRAME, message)
        queued.addAndGet(frame.size.toLong())
        val sent = outgoing.trySend(frame).isSuccess
        if (!sent) queued.addAndGet(-frame.size.toLong())
        return sent
    }

    /**
     * Sends a type-2 frame with [code] and [reason] after what is queued, unless the stream had already ended,
     * then finishes the sending half, resetting it if the daemon stopped it or the finish cannot complete within
     * [ROUTE_CLOSE_WAIT_MS]; the receiving half is stopped and the messages not read are dropped.
     */
    fun close(
        code: Int,
        reason: String,
    ) {
        if (ended.complete(TransportException.Closed(code, reason, byDaemon = false))) {
            outgoing.trySend(closeFrameOf(code, reason))
        }
        outgoing.close()
        messages.cancel()
        reading.cancel()
    }

    override fun close() = close(NORMAL_CLOSURE, "")

    /** Returns once both halves have ended and their handles are let go. */
    suspend fun awaitReleased() = life.join()

    private suspend fun read() {
        try {
            var open = true
            while (open) open = take(halves.nextFrame())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            // The stream or its connection ended with no close frame: what a read is expected to meet.
            ended.complete(TransportException.Unreachable(expectedFailure))
        } finally {
            messages.close()
            outgoing.close()
        }
    }

    /** Hands one frame on; false once the stream is over. */
    private suspend fun take(frame: RouteFrame): Boolean =
        when (frame) {
            is RouteFrame.Message -> {
                messages.send(frame.payload)
                true
            }

            is RouteFrame.Close -> {
                ended.complete(TransportException.Closed(frame.code, frame.reason, byDaemon = true))
                false
            }

            is RouteFrame.Refused -> {
                refuse(frame.detail)
            }

            is RouteFrame.TooLong -> {
                abandon(frame.detail)
                false
            }
        }

    /** A frame that breaks the stream's rules: closed `1002`, the detail saying which. */
    private fun refuse(detail: String): Boolean {
        val refusal = TransportException.Closed(PROTOCOL_ERROR, PROTOCOL_ERROR_REASON, byDaemon = false, detail)
        if (ended.complete(refusal)) outgoing.trySend(closeFrameOf(PROTOCOL_ERROR, PROTOCOL_ERROR_REASON))
        return false
    }

    /** A frame past the bound: the stream ends with no close frame, the sending half reset. */
    private fun abandon(detail: String) {
        ended.complete(TransportException.Closed(MESSAGE_TOO_BIG, "frame too large", byDaemon = false, detail))
        resetSending.set(true)
        outgoing.cancel()
        writing.cancel()
    }

    private suspend fun write() {
        try {
            for (frame in outgoing) {
                halves.writeAll(frame)
                queued.addAndGet(-frame.size.toLong())
            }
            if (!resetSending.get()) finishSending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            // The far end stopped the stream, or its connection failed.
            ended.complete(TransportException.Unreachable(expectedFailure))
            reading.cancel()
        }
    }

    /**
     * Finishes the sending half and waits for the daemon to take it. A half the daemon stopped, as the helper stops
     * the phone's once the daemon has closed, is reset as well: a finish there frees none of the connection's four
     * stream slots (ENGINE_RAIL section 13).
     */
    private suspend fun finishSending() {
        halves.finish()
        if (halves.stopped()) resetSending.set(true)
    }

    /**
     * The stream's end: once the reader is done, the writer has [ROUTE_CLOSE_WAIT_MS] to send what is queued and
     * finish, or the sending half is reset, as it is when the daemon stopped it; then the receiving half is stopped
     * and both are let go.
     */
    private suspend fun live() {
        try {
            reading.join()
            val written = withTimeoutOrNull(ROUTE_CLOSE_WAIT_MS) { writing.join() } != null
            if (!written) resetSending.set(true)
        } finally {
            withContext(NonCancellable) {
                reading.cancel()
                writing.cancel()
                reading.join()
                writing.join()
                if (resetSending.get()) halves.reset()
                halves.stopReading()
                halves.release()
            }
        }
    }
}

/** One frame as the reader takes it, each judged before anything past what decides it is read. */
private sealed interface RouteFrame {
    class Message(
        val payload: ByteArray,
    ) : RouteFrame

    class Close(
        val code: Int,
        val reason: String,
    ) : RouteFrame

    /** A frame that breaks the stream's rules, closed `1002`. */
    class Refused(
        val detail: String,
    ) : RouteFrame

    /** A length past the bound, which ends the stream with no close frame. */
    class TooLong(
        val detail: String,
    ) : RouteFrame
}

/**
 * The next frame: its 4-byte length first, judged before any room is made for what follows it, then its type,
 * then its payload; a close's payload only once its length is within its bound.
 */
private suspend fun RouteHalves.nextFrame(): RouteFrame {
    val length =
        ByteBuffer
            .wrap(readExact(LENGTH_BYTES))
            .int
            .toUInt()
            .toLong()
    return when {
        length > MAX_ROUTE_FRAME_LENGTH -> {
            RouteFrame.TooLong("a frame of $length bytes, past $MAX_ROUTE_FRAME_LENGTH, ended with no close frame")
        }

        length < MIN_FRAME_LENGTH -> {
            RouteFrame.Refused("a frame of length $length, under its type and one byte")
        }

        else -> {
            typedFrame((length - TYPE_BYTES).toInt())
        }
    }
}

/** A frame whose length is within its bounds: its type, then [payloadBytes] of a message or a close. */
private suspend fun RouteHalves.typedFrame(payloadBytes: Int): RouteFrame =
    when (val type = readExact(TYPE_BYTES)[0].toInt()) {
        MESSAGE_FRAME -> RouteFrame.Message(readExact(payloadBytes))
        CLOSE_FRAME -> closeFrame(payloadBytes)
        else -> RouteFrame.Refused("a frame of type $type")
    }

/** A close's code and reason: refused when it is shorter than its code, its reason past 123 bytes or not UTF-8. */
private suspend fun RouteHalves.closeFrame(payloadBytes: Int): RouteFrame {
    if (payloadBytes !in CODE_BYTES..CODE_BYTES + MAX_CLOSE_REASON_BYTES) {
        return RouteFrame.Refused("a close of $payloadBytes bytes, outside its code and a reason of 0 to 123 bytes")
    }
    val payload = ByteBuffer.wrap(readExact(payloadBytes))
    val code = payload.short.toUShort().toInt()
    return utf8(payload)?.let { RouteFrame.Close(code, it) } ?: RouteFrame.Refused("a close whose reason is not UTF-8")
}

/** A frame: its length, which counts the type byte, the type, and the payload. */
private fun frameOf(
    type: Int,
    payload: ByteArray,
): ByteArray =
    ByteBuffer
        .allocate(LENGTH_BYTES + TYPE_BYTES + payload.size)
        .putInt(TYPE_BYTES + payload.size)
        .put(type.toByte())
        .put(payload)
        .array()

/** A close frame: the code as a uint16be and the reason, cut to 123 bytes at a character's edge. */
private fun closeFrameOf(
    code: Int,
    reason: String,
): ByteArray {
    require(code in 0..UShort.MAX_VALUE.toInt()) { "close code $code is not a uint16" }
    var text = reason
    while (text.encodeToByteArray().size > MAX_CLOSE_REASON_BYTES) {
        text = text.dropLast(if (text.last().isLowSurrogate() && text.length > 1) 2 else 1)
    }
    val bytes = text.encodeToByteArray()
    return frameOf(
        CLOSE_FRAME,
        ByteBuffer
            .allocate(CODE_BYTES + bytes.size)
            .putShort(code.toShort())
            .put(bytes)
            .array(),
    )
}

/** The rest of [buffer] as strict UTF-8, or null when it is not. */
private fun utf8(buffer: ByteBuffer): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(buffer)
            .toString()
    } catch (expectedRefusal: CharacterCodingException) {
        // Not UTF-8: the caller closes the stream 1002, which says so.
        null
    }
