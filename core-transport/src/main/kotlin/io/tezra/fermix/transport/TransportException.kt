package io.tezra.fermix.transport

import okhttp3.Response
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * How many failures under one are searched for a pin refusal. The TLS layer wraps the trust manager's
 * exception in its SSLHandshakeException, and OkHttp adds each later route's failure to the first as
 * suppressed, so a refusal sits a few failures down; the bound also ends a cycle.
 */
private const val MAX_RELATED_FAILURES = 32

/**
 * Every way an open fails or a connection ends, each its own type. [PinMismatch] is the one the UI
 * never retries; the others are the reconnect loop's to retry.
 */
sealed class TransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The server's leaf certificate is not the one `tls_fp` pins (design section 13.3, "Wrong
     * machine"): a security event, never retried. A daemon that was reinstalled shows up this way
     * too, and is rendered "identity changed", never trusted anew (onboarding gotcha 9).
     */
    class PinMismatch(
        cause: Throwable,
    ) : TransportException("the server's certificate is not the one tls_fp pins", cause)

    /** The candidate could not be reached, or its connection failed with no close frame. */
    class Unreachable(
        cause: Throwable,
    ) : TransportException("the candidate could not be reached: $cause", cause)

    /**
     * The server answered [httpCode] where the WebSocket upgrade was due, and OkHttp refused the
     * answer for the reason [cause] gives: a 101 that is not a WebSocket upgrade carries it here.
     */
    class Refused(
        val httpCode: Int,
        cause: Throwable,
    ) : TransportException("the server's HTTP $httpCode answer is not the WebSocket upgrade", cause)

    /**
     * The connection closed with [code] and [reason]. [byDaemon] is true when they came in the
     * daemon's close frame, and false when this side closed first, or refused a message with a code
     * of the daemon's own (1002, 1003, 1009). PROTOCOL.md's close codes are the ones the daemon sends,
     * so core-session reads them only from a close the daemon sent.
     */
    class Closed(
        val code: Int,
        val reason: String,
        val byDaemon: Boolean,
    ) : TransportException("${if (byDaemon) "the daemon" else "this side"} closed the WebSocket with $code \"$reason\"")
}

/**
 * Types what OkHttp reports. A pin refusal comes first, wherever it sits under the failure: the trust
 * manager's own [PinMismatchException], or the [SSLPeerUnverifiedException] that OkHttp throws when
 * the verifier returns false, which this client's verifier does only on a leaf that is not pinned. A
 * host with two addresses, as a MagicDNS name has, fails as its first route did once both have
 * failed, and a refusal on the other is suppressed under it; it is a security event all the same.
 * When the other address opens instead, OkHttp reports no failure, so that refusal never reaches
 * here. An HTTP answer is a refusal, with OkHttp's reason; anything else is a candidate out of reach.
 */
internal fun failureOf(
    failure: Throwable,
    response: Response?,
): TransportException {
    val pinRefused = related(failure).any { it is PinMismatchException || it is SSLPeerUnverifiedException }
    return when {
        pinRefused -> TransportException.PinMismatch(failure)
        response != null -> TransportException.Refused(response.code, failure)
        else -> TransportException.Unreachable(failure)
    }
}

/** [failure] and the failures under it, its causes and suppressed ones, breadth first and bounded. */
private fun related(failure: Throwable): List<Throwable> {
    val found = ArrayList<Throwable>()
    val pending = ArrayDeque(listOf(failure))
    while (pending.isNotEmpty() && found.size < MAX_RELATED_FAILURES) {
        val next = pending.removeFirst()
        found += next
        pending += listOfNotNull(next.cause) + next.suppressed
    }
    return found
}
