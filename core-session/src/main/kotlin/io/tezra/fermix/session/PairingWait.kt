package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Decoded
import io.tezra.fermix.protocol.PairDeniedReason
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.VersionDirection
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** A pairing window lasts at most this long (PROTOCOL.md "Noise modes and pairing", step 2). */
internal const val PAIRING_WINDOW_MS = 120_000L

/**
 * How long past the phone's own 120 s the wait goes on: the window opened before the link was scanned,
 * so its `pair_denied{timeout}` comes first, and this bounds a daemon that never sends it.
 */
private const val DECISION_GRACE_MS = 10_000L

/** The most frames the wait reads: a pong per ping, with room for events a newer daemon adds. */
private const val MAX_WAIT_FRAMES = 64

/** The most times the keepalive wakes in one wait: a ping each 25 s and a check on each pong due. */
private const val MAX_WAIT_WAKES = 32

/** The text of a `4003` close that ends a pairing: `pairing <reason>` (PROTOCOL.md, step 4). */
private const val PAIRING_CLOSE_PREFIX = "pairing "

/** A pairing's `4003` close, after `pair_denied` or in its place. */
private const val PAIRING_ENDED = 4003

/** What one read of the wait gives the ceremony. */
internal sealed interface WaitStep {
    /** A keepalive answer. */
    data object Pong : WaitStep

    /** An event a newer daemon sends that this app does not know: the wait goes on. */
    data object Pending : WaitStep

    data class Approved(
        val event: ServerEvent.PairApproved,
    ) : WaitStep

    data class Ends(
        val state: PairingState.Ended,
    ) : WaitStep
}

/**
 * The owner's decision on [channel] (PROTOCOL.md "Noise modes and pairing", steps 2 to 4): the keepalive
 * goes on throughout, since the daemon answers `ping` while the decision is pending, and the read ends at
 * `pair_approved`, which it returns, or at the ending it reads, which it throws as [CeremonyEnded]. A
 * daemon silent past the window and its grace is [PairingState.Expired]. The keepalive stops before the
 * approval is handed on, so no ping of the wait follows it.
 */
internal suspend fun awaitDecision(
    channel: SecureChannel,
    clock: TimeSource,
): ServerEvent.PairApproved {
    val started = clock.markNow()
    val now = { started.elapsedNow().inWholeMilliseconds }
    val keepalive = Keepalive(now())
    return withTimeoutOrNull(PAIRING_WINDOW_MS + DECISION_GRACE_MS) {
        coroutineScope {
            val pinging = launch { keepAlive(channel, keepalive, now) }
            decision(channel, keepalive, now).also { pinging.cancel() }
        }
    } ?: throw CeremonyEnded(PairingState.Expired)
}

/**
 * Pings as the keepalive says, and ends the wait as [PairingState.LostMidWait] when two pongs in a row do
 * not come. Past [MAX_WAIT_WAKES] it stops: the window and its grace are over by then.
 */
private suspend fun keepAlive(
    channel: SecureChannel,
    keepalive: Keepalive,
    now: () -> Long,
) {
    repeat(MAX_WAIT_WAKES) {
        delay((keepalive.nextCheckMs - now()).coerceAtLeast(0))
        when (keepalive.poll(now())) {
            KeepaliveAction.PING -> channel.send(ClientEvent.Ping)
            KeepaliveAction.LOST -> throw CeremonyEnded(PairingState.LostMidWait)
            KeepaliveAction.NONE -> Unit
        }
    }
}

private suspend fun decision(
    channel: SecureChannel,
    keepalive: Keepalive,
    now: () -> Long,
): ServerEvent.PairApproved {
    repeat(MAX_WAIT_FRAMES) {
        when (val step = waitStep(channel.receive())) {
            WaitStep.Pong -> keepalive.pong(now())
            WaitStep.Pending -> Unit
            is WaitStep.Approved -> return step.event
            is WaitStep.Ends -> throw CeremonyEnded(step.state)
        }
    }
    throw CeremonyEnded(PairingState.ProtocolError("no decision in $MAX_WAIT_FRAMES frames"))
}

/**
 * One read of the wait: `pong`, `pair_approved` at protocol v2, `pair_denied`, a version refusal, an event
 * this app does not know, which it passes over, or the connection's end. Anything else is a protocol error.
 */
internal fun waitStep(input: Input): WaitStep =
    when (input) {
        is Input.End -> WaitStep.Ends(waitEnding(input.ending))
        is Input.Frame -> frameStep(input.decoded)
    }

private fun frameStep(frame: Decoded<ServerEvent>): WaitStep {
    val event = frame.event
    return when {
        event == ServerEvent.Pong -> {
            WaitStep.Pong
        }

        event is ServerEvent.Unknown -> {
            WaitStep.Pending
        }

        event is ServerEvent.PairApproved && frame.v == SESSION_VERSION -> {
            WaitStep.Approved(event)
        }

        event is ServerEvent.PairDenied -> {
            WaitStep.Ends(denialEnding(event.reason))
        }

        event is ServerEvent.Error && event.code == ServerEvent.Error.UNSUPPORTED_PROTOCOL_VERSION -> {
            WaitStep.Ends(versionEnding(event))
        }

        else -> {
            WaitStep.Ends(PairingState.ProtocolError("${nameOf(event)} at v${frame.v} while pairing"))
        }
    }
}

/** Design section 13.3's row for each `pair_denied` reason. */
internal fun denialEnding(reason: PairDeniedReason): PairingState.Ended =
    when (reason) {
        PairDeniedReason.DENIED, PairDeniedReason.CANCELLED -> PairingState.Denied
        PairDeniedReason.TIMEOUT -> PairingState.Expired
        PairDeniedReason.DEVICE_DISCONNECTED -> PairingState.LostMidWait
        PairDeniedReason.ATTESTATION, PairDeniedReason.PLATFORM_UNSUPPORTED -> PairingState.AttestationRefused
        PairDeniedReason.ATTESTATION_UNAVAILABLE -> PairingState.AttestationUnavailable
    }

/** A version refusal: a daemon too old for this app is "Older Fermix", one too new "Newer Fermix". */
private fun versionEnding(error: ServerEvent.Error): PairingState.Ended =
    when (error.refusedDirection()) {
        VersionDirection.CLIENT_TOO_NEW -> PairingState.OlderFermix
        VersionDirection.CLIENT_TOO_OLD -> PairingState.NewerFermix
        null -> PairingState.ProtocolError("unsupported_protocol_version names no direction")
    }

/**
 * The connection's end while the owner decides. The daemon closes `1002` after `pair_request` when
 * another phone's request is waiting (`request_pending`), but also, with the same text, when the window
 * closed after the handshake or the request was invalid: the wire does not tell these apart, so `1002`
 * reads as the first, the likeliest (PairingState.AnotherPairingInProgress). `4003 "pairing <reason>"` is
 * a `pair_denied` whose frame did not come; anything else, `4001 "connection replaced"` among them, lost
 * the connection. A frame this side refused is a protocol error, which the ceremony closes with `1002`.
 */
internal fun waitEnding(ending: Ending): PairingState.Ended {
    val close = ((ending as? Ending.Transport)?.failure as? TransportException.Closed)?.takeIf { it.byDaemon }
    return when {
        ending is Ending.ProtocolError -> PairingState.ProtocolError(ending.detail)
        close?.code == PROTOCOL_ERROR -> PairingState.AnotherPairingInProgress
        close?.code == PAIRING_ENDED -> closedPairing(close.reason)
        else -> PairingState.LostMidWait
    }
}

private fun closedPairing(reason: String): PairingState.Ended {
    val word = reason.removePrefix(PAIRING_CLOSE_PREFIX).takeIf { reason.startsWith(PAIRING_CLOSE_PREFIX) }
    val denial = PairDeniedReason.entries.firstOrNull { it.name.lowercase() == word }
    return denial?.let(::denialEnding) ?: PairingState.ProtocolError("closed 4003 \"$reason\" while pairing")
}
