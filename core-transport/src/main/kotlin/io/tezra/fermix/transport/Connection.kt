package io.tezra.fermix.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.trySendBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * One WebSocket message's payload at most, past which the daemon closes with 1009 (PROTOCOL.md
 * "Transport"): a Noise message's bound. OkHttp sets no bound of its own on what it receives, so both
 * directions are held to this here. OkHttp hands on a message only once every fragment of it has
 * arrived, and buffers them all before then, so a larger incoming message is refused once it is whole,
 * after it has taken its size in memory.
 */
const val MAX_MESSAGE_BYTES = 65_535

/** A close with nothing wrong: the app's own end of a session, or a race's surplus winner. */
internal const val NORMAL_CLOSURE = 1000

/** The daemon's code for a text message, which it does not take either. */
private const val UNSUPPORTED_DATA = 1003

/** The daemon's code for a message past [MAX_MESSAGE_BYTES]. */
private const val MESSAGE_TOO_BIG = 1009

/** The daemon's code for a protocol error; here, a server that turned on the compression the contract keeps off. */
private const val PROTOCOL_ERROR = 1002

/**
 * Messages held for a slow reader before the socket's reader thread waits for room: 64 messages, at
 * most 4 MiB. The wait holds back the socket, so a reader that falls behind slows the daemon down
 * instead of growing the heap.
 */
internal const val INCOMING_BACKLOG = 64

/**
 * One open WebSocket to a daemon. It carries binary messages only, each at most [MAX_MESSAGE_BYTES],
 * in both directions, and hands on how it ended. It knows nothing of what the messages hold. Whoever
 * holds it closes it, however it ended: that is what lets go of a reader thread still waiting for room
 * in [incoming].
 */
class Connection internal constructor(
    private val socket: WebSocket,
    private val events: SocketEvents,
) : AutoCloseable {
    /**
     * Each binary message the daemon sends, in order. When the daemon or the socket ends the connection
     * it ends with no error, after the messages it already holds; closing from this side cancels it and
     * drops the messages not yet read.
     */
    val incoming: ReceiveChannel<ByteArray> get() = events.incoming

    /**
     * Completes once, with how the connection ended: [TransportException.Closed] with the daemon's
     * code and reason, or with this side's when it closed first, or refused a message, and
     * [TransportException.Closed.byDaemon] says which; or [TransportException.Unreachable] when the
     * socket failed with no close frame. The daemon's codes are handed on as they come, protocol v2's
     * 4004 "device not paired" included (design section 7, the "Close codes" row); core-session maps
     * them.
     */
    val closed: Deferred<TransportException> get() = events.ended

    /**
     * Queues one binary message; a message past [MAX_MESSAGE_BYTES] is refused before anything is sent.
     * False when nothing was queued: the connection is closing or closed, and [closed] says why; or this
     * message would have taken [queuedBytes] past OkHttp's 16 MiB, and OkHttp has closed the socket with
     * 1001, which [closed] reports once that close ends. A sender paces itself by [queuedBytes].
     */
    fun send(message: ByteArray): Boolean {
        require(message.size <= MAX_MESSAGE_BYTES) {
            "a ${message.size}-byte message is past the WebSocket bound of $MAX_MESSAGE_BYTES"
        }
        return socket.send(message.toByteString())
    }

    /** Bytes queued and not yet written to the socket: a daemon that reads slowly shows here first. */
    val queuedBytes: Long get() = socket.queueSize()

    /**
     * Sends a close frame with [code] and [reason], after any message already queued. This side reads
     * no more: the messages not yet read are dropped, which lets go of a reader thread waiting for room.
     */
    fun close(
        code: Int,
        reason: String,
    ) {
        socket.close(code, reason)
        events.end(TransportException.Closed(code, reason, byDaemon = false))
        events.incoming.cancel()
    }

    override fun close() = close(NORMAL_CLOSURE, "")
}

/**
 * OkHttp's callbacks for one socket, turned into the open, the messages and the end. OkHttp calls them
 * on its own threads; each state here is safe to complete or close from any thread, and only the first
 * end counts. OkHttp's onClosing and onClosed both carry the code and reason of the daemon's close
 * frame, whichever side closed first.
 */
internal class SocketEvents : WebSocketListener() {
    val opened = CompletableDeferred<Unit>()
    val incoming = Channel<ByteArray>(INCOMING_BACKLOG)
    val ended = CompletableDeferred<TransportException>()

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        // OkHttp always offers permessage-deflate and refuses a request that sets the header itself,
        // so compression is held off here: a server that accepts any extension is not the daemon.
        if (response.header("Sec-WebSocket-Extensions") != null) {
            refuse(webSocket, PROTOCOL_ERROR, "compression is off")
            return
        }
        opened.complete(Unit)
    }

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        if (bytes.size > MAX_MESSAGE_BYTES) {
            refuse(webSocket, MESSAGE_TOO_BIG, "message too large")
            return
        }
        // A closed channel means the connection has ended, and [ended] already says how. A full one
        // makes this, the socket's reader thread, wait for room, until this side closes and cancels it.
        incoming.trySendBlocking(bytes.toByteArray())
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) = refuse(webSocket, UNSUPPORTED_DATA, "binary messages only")

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        webSocket.close(NORMAL_CLOSURE, null)
        end(TransportException.Closed(code, reason, byDaemon = true))
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) = end(TransportException.Closed(code, reason, byDaemon = true))

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) = end(failureOf(t, response))

    /** Records how the connection ended, once: it fails an open still pending and ends [incoming]. */
    fun end(reason: TransportException) {
        ended.complete(reason)
        opened.completeExceptionally(reason)
        incoming.close()
    }

    private fun refuse(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        webSocket.close(code, reason)
        end(TransportException.Closed(code, reason, byDaemon = false))
    }
}
