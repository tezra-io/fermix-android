package io.tezra.fermix.session

import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.Connection
import io.tezra.fermix.transport.PinnedTrust
import io.tezra.fermix.transport.RouteOpener
import io.tezra.fermix.transport.RouteStream
import io.tezra.fermix.transport.TransportException
import io.tezra.fermix.transport.WebSocketConnector
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * One open link to a daemon as a session uses it: binary messages both ways and how it ended,
 * core-transport's WebSocket Connection or iroh RouteStream in production. Messages the daemon sent before it
 * closed are read first; closing from this side drops those not read.
 */
interface Link {
    val incoming: ReceiveChannel<ByteArray>
    val closed: Deferred<TransportException>

    /** False when the link is closing and [closed] says why. */
    fun send(message: ByteArray): Boolean

    /** Bytes sent and not yet written to the socket, which an upload paces itself by (Uploads). */
    val queuedBytes: Long

    fun close(
        code: Int,
        reason: String,
    )

    /**
     * What opening the link noted for the Diagnostics: the iroh route's endpoint bound, a relay that failed before
     * another answered. A WebSocket notes nothing.
     */
    val notes: List<String> get() = emptyList()
}

/** Opens a link to one candidate, or fails with the candidate's TransportException. */
fun interface Dialer {
    suspend fun dial(candidate: Candidate): Link
}

/**
 * The production dialer of a host: `wss://<candidate>:<port>/ws` pinned to the instance's `tls_fp`
 * (core-transport). A route is no host: [InstanceDialer] dials it over the route's carrier.
 */
class WebSocketDialer(
    private val connector: WebSocketConnector,
    private val port: Int,
    private val trust: PinnedTrust,
) : Dialer {
    override suspend fun dial(candidate: Candidate): Link {
        require(candidate is Candidate.Host) { "a WebSocket dials a host, not ${candidate.scope}" }
        return ConnectionLink(connector.open(candidate, port, trust))
    }
}

/** The dialer of a daemon's iroh route: a new stream through the phone's one iroh endpoint ([RouteOpener]). */
class RouteDialer(
    private val opener: RouteOpener,
) : Dialer {
    override suspend fun dial(candidate: Candidate): Link {
        require(candidate is Candidate.Route) { "the route's carrier dials a route, not a host" }
        return RouteLink(opener.open(candidate))
    }
}

/**
 * An instance's dialer (M62 stage 6): each host over [hosts], the instance's pinned WebSocket, and its route over
 * [route]. The race in front of it is the one race of design section 5.1, the route last (candidateOrder).
 */
class InstanceDialer(
    private val hosts: Dialer,
    private val route: Dialer,
) : Dialer {
    override suspend fun dial(candidate: Candidate): Link =
        when (candidate) {
            is Candidate.Host -> hosts.dial(candidate)
            is Candidate.Route -> route.dial(candidate)
        }
}

private class ConnectionLink(
    private val connection: Connection,
) : Link {
    override val incoming: ReceiveChannel<ByteArray> get() = connection.incoming
    override val closed: Deferred<TransportException> get() = connection.closed

    override fun send(message: ByteArray): Boolean = connection.send(message)

    override val queuedBytes: Long get() = connection.queuedBytes

    override fun close(
        code: Int,
        reason: String,
    ) = connection.close(code, reason)
}

private class RouteLink(
    private val stream: RouteStream,
) : Link {
    override val incoming: ReceiveChannel<ByteArray> get() = stream.incoming
    override val closed: Deferred<TransportException> get() = stream.closed

    override fun send(message: ByteArray): Boolean = stream.send(message)

    override val queuedBytes: Long get() = stream.queuedBytes

    override fun close(
        code: Int,
        reason: String,
    ) = stream.close(code, reason)

    override val notes: List<String> get() = stream.notes
}
