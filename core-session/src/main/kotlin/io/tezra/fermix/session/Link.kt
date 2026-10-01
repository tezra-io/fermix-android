package io.tezra.fermix.session

import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.Connection
import io.tezra.fermix.transport.PinnedTrust
import io.tezra.fermix.transport.TransportException
import io.tezra.fermix.transport.WebSocketConnector
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * One open socket to a daemon as a session uses it: binary messages both ways and how it ended,
 * core-transport's Connection in production. Messages the daemon sent before it closed are read
 * first; closing from this side drops those not read.
 */
interface Link {
    val incoming: ReceiveChannel<ByteArray>
    val closed: Deferred<TransportException>

    /** False when the link is closing and [closed] says why. */
    fun send(message: ByteArray): Boolean

    fun close(
        code: Int,
        reason: String,
    )
}

/** Opens a link to one candidate, or fails with the candidate's TransportException. */
fun interface Dialer {
    suspend fun dial(candidate: Candidate): Link
}

/** The production dialer: `wss://<candidate>:<port>/ws` pinned to the instance's `tls_fp` (core-transport). */
class WebSocketDialer(
    private val connector: WebSocketConnector,
    private val port: Int,
    private val trust: PinnedTrust,
) : Dialer {
    override suspend fun dial(candidate: Candidate): Link = ConnectionLink(connector.open(candidate, port, trust))
}

private class ConnectionLink(
    private val connection: Connection,
) : Link {
    override val incoming: ReceiveChannel<ByteArray> get() = connection.incoming
    override val closed: Deferred<TransportException> get() = connection.closed

    override fun send(message: ByteArray): Boolean = connection.send(message)

    override fun close(
        code: Int,
        reason: String,
    ) = connection.close(code, reason)
}
