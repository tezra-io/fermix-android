package io.tezra.fermix.demo

import io.tezra.fermix.session.Link
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Both ends of one socket in memory: the phone's end is the [Link] a session or a pairing reads and writes, and
 * the daemon's end is [toDaemon] and [deliver]. Like core-transport's Connection, a daemon's close ends the
 * phone's incoming messages after those it already holds, and the phone's own close drops the ones it has not
 * read. Nothing of it opens a socket.
 */
class MemoryLink : Link {
    /** What the phone sent, in order, for the daemon to read. */
    val toDaemon = Channel<ByteArray>(Channel.UNLIMITED)
    private val toPhone = Channel<ByteArray>(Channel.UNLIMITED)
    private val ended = CompletableDeferred<TransportException>()

    /** The close this side sent, once it sent one. */
    val phoneClose = CompletableDeferred<TransportException.Closed>()

    /** What the socket holds unwritten, as a test sets it: a daemon that reads slowly. */
    @Volatile
    var queued = 0L

    override val queuedBytes: Long get() = queued

    override val incoming: ReceiveChannel<ByteArray> get() = toPhone
    override val closed: Deferred<TransportException> get() = ended

    override fun send(message: ByteArray): Boolean = !ended.isCompleted && toDaemon.trySend(message).isSuccess

    override fun close(
        code: Int,
        reason: String,
    ) {
        val close = TransportException.Closed(code, reason, byDaemon = false)
        phoneClose.complete(close)
        ended.complete(close)
        toPhone.cancel()
        toDaemon.close()
    }

    /** A message from the daemon to the phone; one past the link's end goes nowhere. */
    fun deliver(message: ByteArray) {
        toPhone.trySend(message)
    }

    fun closeByDaemon(
        code: Int,
        reason: String,
    ) {
        ended.complete(TransportException.Closed(code, reason, byDaemon = true))
        toPhone.close()
        toDaemon.close()
    }

    /** The socket fails with no close frame, as a dropped network leaves it. */
    fun fail(cause: Exception) {
        ended.complete(TransportException.Unreachable(cause))
        toPhone.close()
        toDaemon.close()
    }
}
