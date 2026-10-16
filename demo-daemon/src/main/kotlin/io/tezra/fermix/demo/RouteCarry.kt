package io.tezra.fermix.demo

import io.tezra.fermix.session.Link
import io.tezra.fermix.transport.RouteStream
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A close with nothing wrong, as the daemon's side of a stream ends one the phone already ended. */
private const val NORMAL_CLOSURE = 1000

/**
 * Carries a session between [stream], the daemon's end of one remote stream, and [link], the demo's end of a
 * session as a dial returns it, as fermix-iroh carries one between a stream and the daemon's socket handler
 * (PROTOCOL.md "Remote reach"): what the phone sends over the stream reaches the demo, what the demo sends goes back
 * as frames, the demo's close goes out as a close frame, and the phone's close or the stream's end closes the link.
 * The tests' route to the demo, in memory on the JVM and over two real iroh endpoints on a device. It returns once
 * both directions have ended.
 */
suspend fun carryRoute(
    stream: RouteStream,
    link: Link,
) = coroutineScope {
    launch {
        for (message in stream.incoming) {
            if (!link.send(message)) break
        }
        val ended = stream.closed.await()
        if (!link.closed.isCompleted) {
            val close = ended as? TransportException.Closed
            link.close(close?.code ?: NORMAL_CLOSURE, close?.reason.orEmpty())
        }
    }
    for (message in link.incoming) {
        if (!stream.send(message)) break
    }
    val close = link.closed.await() as? TransportException.Closed
    if (close != null && close.byDaemon) stream.close(close.code, close.reason) else stream.close(NORMAL_CLOSURE, "")
}
