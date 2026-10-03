package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.CandidateRacer
import io.tezra.fermix.transport.RaceResult
import io.tezra.fermix.transport.TransportException
import io.tezra.fermix.transport.candidateOrder

/** The reason the daemon's hourly `1000` close carries (PROTOCOL.md "Close codes"). */
internal const val LIFETIME_REASON = "Noise session lifetime reached"

/**
 * How one race and the connection it won ended: what to do next, the candidate a handshake completed
 * over, whether hello completed, and whether the ending was the hourly close, which reconnects silently.
 */
internal class Attempt(
    val next: Next,
    val reached: Candidate? = null,
    val connected: Boolean = false,
    val silent: Boolean = false,
)

/**
 * A pairing's connection after `pair_approved` (PROTOCOL.md "Noise modes and pairing", step 3): the
 * candidate it was won over, and its link, Noise session and channel, whose seq the pairing's frames have
 * moved on. A session's first attempt takes it over instead of racing (Session.adopt).
 */
internal class Adopted(
    val candidate: Candidate,
    val won: Handshaken,
    val channel: SecureChannel,
)

/**
 * One attempt: race the candidates, last successful first (design section 5.1); the winner's hello at
 * seq 1; and, once `hello_ack` completes it, the connection until it ends. Hello goes on the winner
 * alone, so the daemon sees one session per race. Hello carries protocol v2's `last_mutation_seq`
 * (design section 7, the `mutation_seq` row), the mutation feed's cursor. A session that adopted a
 * pairing's connection makes its first attempt on that connection, with hello at its next seq, and
 * races from the second on.
 */
internal class Connector(
    private val core: SessionCore,
    private val requests: Requests,
) {
    suspend fun attempt(): Attempt {
        val adopted = core.takeAdopted()
        if (adopted != null) return adopted.won.use { connection(adopted.candidate, it, adopted.channel) }
        val parts = core.parts
        val gatewayKey = core.instance.gatewayPublicKey
        val order = candidateOrder(core.candidates, core.lastSuccessful.value)
        return when (
            val race =
                CandidateRacer.race(
                    order,
                ) { handshake(parts.dialer, it, parts.staticKey, gatewayKey) }
        ) {
            is RaceResult.Won -> {
                race.value.use { connection(race.candidate, it) }
            }

            is RaceResult.PinMismatch -> {
                core.log(DiagnosticKind.RACE_FAILED, race.failure.toString())
                Attempt(Next.Stop(SessionState.IdentityChanged))
            }

            is RaceResult.AllFailed -> {
                core.log(DiagnosticKind.RACE_FAILED, race.failures.values.joinToString { it.toString() })
                race.failures.values
                    .firstOrNull(::isProtocolErrorClose)
                    ?.let { core.logProtocolError(it) }
                Attempt(nextAfterRace(race.failures.values))
            }
        }
    }

    private suspend fun connection(
        candidate: Candidate,
        won: Handshaken,
        channel: SecureChannel = SecureChannel(won.link, won.noise),
    ): Attempt {
        val lastMutationSeq =
            core.parts.store
                .cursors()
                .lastMutationSeq
        val cursor = core.timeline().cursor
        val hello =
            ClientEvent.Hello(
                core.instance.deviceId,
                core.parts.appVersion,
                cursor,
                SESSION_VERSION,
                lastMutationSeq,
            )
        return when (val outcome = exchangeHello(channel, hello, core::now)) {
            is HelloOutcome.Accepted -> live(candidate, won.link, channel, outcome)
            is HelloOutcome.Refused -> refused(candidate, outcome)
            is HelloOutcome.Failed -> ended(candidate, won.link, outcome.ending, upMs = null)
        }
    }

    private suspend fun live(
        candidate: Candidate,
        link: Link,
        channel: SecureChannel,
        accepted: HelloOutcome.Accepted,
    ): Attempt {
        core.lastSuccessful.value = candidate
        // A reconnect after the hourly close finds the link still shown up and caught up, and stays silent.
        val caughtUp = (core.state.value as? SessionState.Connected)?.caughtUp == true
        val connected = SessionState.Connected(candidate.scope, accepted.latencyMs, caughtUp)
        core.publish(connected)
        val upAt = core.now()
        val live = Live(core, channel, Keepalive(upAt), connected)
        core.live = live
        val ending =
            try {
                serve(core, live, requests, accepted.ack)
            } finally {
                core.live = null
                // A one-shot waits on this connection alone: its caller hears that it ended.
                live.asked.interrupt()
                live.fetches.interrupt()
            }
        return ended(candidate, link, ending, upMs = core.now() - upAt)
    }

    private suspend fun refused(
        candidate: Candidate,
        outcome: HelloOutcome.Refused,
    ): Attempt {
        core.log(DiagnosticKind.REFUSED, outcome.error.code)
        core.emit(SessionEvent.Refused(outcome.error))
        return Attempt(nextAfter(outcome.ending), candidate)
    }

    /**
     * A protocol error is closed with `1002`, as the daemon does; any other ending leaves the close to
     * the winner's own, `1000`. [upMs] is how long the connection was up after `hello_ack`, null when
     * hello did not complete (nextAfterConnection).
     */
    private fun ended(
        candidate: Candidate,
        link: Link,
        ending: Ending,
        upMs: Long?,
    ): Attempt {
        val daemonClose = (ending as? Ending.Transport)?.failure
        when {
            ending is Ending.ProtocolError -> {
                link.close(PROTOCOL_ERROR, "mobile protocol error")
                core.log(DiagnosticKind.PROTOCOL_ERROR, ending.detail)
            }

            daemonClose != null && isProtocolErrorClose(daemonClose) -> {
                core.logProtocolError(daemonClose)
            }

            else -> {
                core.log(DiagnosticKind.CLOSED, ending.toString())
            }
        }
        val next = nextAfterConnection(ending, upMs)
        return Attempt(next, candidate, upMs != null, silent = next == Next.ReconnectNow && ending.isLifetimeClose())
    }
}

/**
 * The daemon's `1002`, a protocol error on its side (PROTOCOL.md "Close codes"), which a protocol v1 daemon
 * also sends to a phone key it no longer holds. It is logged as a protocol error, so the app shows it as one
 * and never as a revocation (design section 9.4); the session races again after the backoff all the same.
 */
private fun isProtocolErrorClose(failure: Exception): Boolean =
    failure is TransportException.Closed && failure.byDaemon && failure.code == PROTOCOL_ERROR

private fun SessionCore.logProtocolError(close: Exception) = log(DiagnosticKind.PROTOCOL_ERROR, close.toString())

/**
 * The daemon's hourly close, `1000 "Noise session lifetime reached"` (design section 5.1), which alone
 * reconnects silently; its other `1000`, shutting down, is told like any other ending (PROTOCOL.md).
 */
private fun Ending.isLifetimeClose(): Boolean {
    val close = (this as? Ending.Transport)?.failure as? TransportException.Closed
    return close != null && close.byDaemon && close.code == NORMAL_CLOSURE && close.reason == LIFETIME_REASON
}
