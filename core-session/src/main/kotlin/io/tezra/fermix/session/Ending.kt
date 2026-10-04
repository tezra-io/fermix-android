package io.tezra.fermix.session

import io.tezra.fermix.noise.NoiseException
import io.tezra.fermix.protocol.VersionDirection
import io.tezra.fermix.transport.TransportException

/** The one-hour session lifetime, or the daemon shutting down; and this side's close with nothing wrong. */
internal const val NORMAL_CLOSURE = 1000

/** A protocol error, which this side closes with too. */
internal const val PROTOCOL_ERROR = 1002

/** `connection replaced`: another connection of this phone is live. */
private const val REPLACED = 4001

/** A connection the daemon closes sooner than this after its `hello_ack` is retried after the backoff. */
internal const val STABLE_CONNECTION_MS = 5_000L

/** `device revoked` while live. */
private const val REVOKED = 4003

/** Protocol v2's `device not paired`: revoked while the phone was away (design section 7, "Close codes"). */
private const val NOT_PAIRED = 4004

/** Why a connection ended. */
internal sealed interface Ending {
    /** The link ended: a close frame, from either side, or a socket that failed with none. */
    data class Transport(
        val failure: TransportException,
    ) : Ending

    /** This side refused what the daemon sent, and closes with `1002`. */
    data class ProtocolError(
        val detail: String,
    ) : Ending

    /** Two pings in a row went unanswered. */
    data object KeepaliveLost : Ending

    /** The phone's network changed, which is a reason to race again (design section 5.1). */
    data object NetworkChanged : Ending

    /** The keepalive's bound on one connection's life ran out; the daemon closes far sooner, at an hour. */
    data object LifetimeReached : Ending

    /**
     * An upload went 30 s with no answer, or the socket did not drain: the next connection starts it again, a
     * restart counted, as after any cut (design section 8.5), and the daemon drops its partial upload and its slot
     * with this one (onboarding gotcha 17). Kept alive, the connection would hold every later `msg` behind it.
     */
    data object UploadStalled : Ending

    /** `error{code:"unsupported_protocol_version"}`, or a `hello_ack` whose window leaves out protocol v2. */
    data class Refused(
        val direction: VersionDirection,
    ) : Ending
}

/** What the session does next. */
internal sealed interface Next {
    /** Race again at once, with a fresh handshake. */
    data object ReconnectNow : Next

    /** Race again after the backoff's wait (core-transport's Backoff). */
    data object Backoff : Next

    /** Reconnect no more. */
    data class Stop(
        val state: SessionState.Ended,
    ) : Next
}

/** Design section 7's "Close codes" row and PROTOCOL.md "Close codes", and the session's own endings. */
internal fun nextAfter(ending: Ending): Next =
    when (ending) {
        is Ending.Transport -> {
            nextAfterTransport(ending.failure)
        }

        is Ending.ProtocolError -> {
            Next.Backoff
        }

        Ending.KeepaliveLost, Ending.NetworkChanged, Ending.LifetimeReached, Ending.UploadStalled -> {
            Next.ReconnectNow
        }

        is Ending.Refused -> {
            Next.Stop(
                if (ending.direction == VersionDirection.CLIENT_TOO_NEW) {
                    SessionState.OlderDaemon
                } else {
                    SessionState.NewerDaemon
                },
            )
        }
    }

/**
 * What follows a connection that was up [upMs] after its `hello_ack`, or null when hello did not
 * complete. Only a connection that was up reconnects at once: one that ended inside hello waits the
 * backoff, and so does one the daemon closed within [STABLE_CONNECTION_MS] of its `hello_ack`, or a
 * daemon that closed every hello, or every connection at once, would be raced in a tight loop.
 */
internal fun nextAfterConnection(
    ending: Ending,
    upMs: Long?,
): Next {
    val next = nextAfter(ending)
    val brief = upMs == null || (ending is Ending.Transport && upMs < STABLE_CONNECTION_MS)
    return if (next == Next.ReconnectNow && brief) Next.Backoff else next
}

/**
 * A daemon's close code, read only from a close the daemon sent: `1000` reconnects at once, `4001`
 * stops this connection for good, `4003` and `4004` are a revocation, and every other code, `1002`
 * included, reconnects after the backoff's wait. A `1002` that came at once again would otherwise
 * reconnect in a tight loop.
 */
internal fun nextAfterClose(code: Int): Next =
    when (code) {
        NORMAL_CLOSURE -> Next.ReconnectNow
        REPLACED -> Next.Stop(SessionState.Replaced)
        REVOKED, NOT_PAIRED -> Next.Stop(SessionState.Revoked)
        else -> Next.Backoff
    }

/**
 * A race in which every candidate failed. A certificate that is not pinned or a Noise key that does
 * not authenticate is the daemon's identity changing, never a revocation and never trusted anew
 * (onboarding gotcha 9); a daemon that closed the handshake as revoked or replaced stops the session;
 * anything else is retried after the backoff's wait.
 */
internal fun nextAfterRace(failures: Collection<Exception>): Next {
    val identity = failures.any { it is TransportException.PinMismatch || it is NoiseException.AuthenticationFailed }
    val closes = failures.filterIsInstance<TransportException.Closed>().filter { it.byDaemon }
    return if (identity) {
        Next.Stop(SessionState.IdentityChanged)
    } else {
        closes.map { nextAfterClose(it.code) }.firstOrNull { it is Next.Stop } ?: Next.Backoff
    }
}

private fun nextAfterTransport(failure: TransportException): Next =
    when {
        failure is TransportException.PinMismatch -> Next.Stop(SessionState.IdentityChanged)
        failure is TransportException.Closed && failure.byDaemon -> nextAfterClose(failure.code)
        else -> Next.Backoff
    }
