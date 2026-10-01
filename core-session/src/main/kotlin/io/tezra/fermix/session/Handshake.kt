package io.tezra.fermix.session

import io.tezra.fermix.noise.InitiatorHandshake
import io.tezra.fermix.noise.NoiseSession
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Decoded
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.VersionDirection
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** A candidate's whole attempt, the socket and both Noise messages: the connect timeout (design section 5.1). */
internal const val HANDSHAKE_TIMEOUT_MS = 10_000L

/** How long the daemon has to answer `hello`. */
internal const val HELLO_TIMEOUT_MS = 10_000L

/** A link whose Noise handshake completed: a race's winner, closed with the session it carries. */
internal class Handshaken(
    val link: Link,
    val noise: NoiseSession,
) : AutoCloseable {
    override fun close() {
        link.close(NORMAL_CLOSURE, "")
        noise.close()
    }
}

/**
 * One candidate's attempt: the socket, then Noise IK to [gatewayKey] with an empty payload, the protocol
 * v1 daemon's only one. A message 2 that does not authenticate throws NoiseException.AuthenticationFailed,
 * the daemon's identity changing; a close during it throws the close, which the race reads. The timeout
 * may fire even as its block returns, so what the block opens is held outside it, and closed on every
 * path but the one that hands it over.
 */
internal suspend fun handshake(
    dialer: Dialer,
    candidate: Candidate,
    staticKey: StaticKey,
    gatewayKey: ByteArray,
): Handshaken {
    var link: Link? = null
    var noise: NoiseSession? = null
    var won: Handshaken? = null
    try {
        withTimeout(HANDSHAKE_TIMEOUT_MS) {
            val opened = dialer.dial(candidate).also { link = it }
            noise = ik(opened, staticKey, gatewayKey)
        }
        won = Handshaken(checkNotNull(link), checkNotNull(noise))
        return won
    } finally {
        if (won == null) {
            noise?.close()
            link?.close(NORMAL_CLOSURE, "")
        }
    }
}

private suspend fun ik(
    link: Link,
    staticKey: StaticKey,
    gatewayKey: ByteArray,
): NoiseSession =
    InitiatorHandshake.ik(staticKey, gatewayKey).use { handshake ->
        // A link that is closing takes nothing; the receive below then reads why it closed.
        link.send(handshake.writeFirstMessage(ByteArray(0)))
        val second = link.incoming.receiveCatching().getOrNull() ?: throw link.closed.await()
        handshake.readSecondMessage(second).session
    }

/** How `hello` went. */
internal sealed interface HelloOutcome {
    /** [ack] completed hello, [latencyMs] after it was sent. */
    class Accepted(
        val ack: ServerEvent.HelloAck,
        val latencyMs: Long,
    ) : HelloOutcome

    /** The daemon refused protocol v2 with [error]; the session stops on [ending]. */
    class Refused(
        val error: ServerEvent.Error,
        val ending: Ending.Refused,
    ) : HelloOutcome

    class Failed(
        val ending: Ending,
    ) : HelloOutcome
}

/**
 * Sends [hello] at seq 1 and reads the first event back (PROTOCOL.md "Envelope, ordering, and version
 * negotiation"): a `hello_ack` at v2 whose window holds 2, or `unsupported_protocol_version`, which a
 * protocol v1 daemon sends at v1. Anything else is a protocol error.
 */
internal suspend fun exchangeHello(
    channel: SecureChannel,
    hello: ClientEvent.Hello,
    now: () -> Long,
): HelloOutcome {
    val sentAt = now()
    channel.send(hello)
    val first =
        withTimeoutOrNull(HELLO_TIMEOUT_MS) { channel.receive() }
            ?: return HelloOutcome.Failed(Ending.ProtocolError("no hello_ack within $HELLO_TIMEOUT_MS ms"))
    return when (first) {
        is Input.End -> HelloOutcome.Failed(first.ending)
        is Input.Frame -> answer(first.decoded, now() - sentAt)
    }
}

private fun answer(
    first: Decoded<ServerEvent>,
    latencyMs: Long,
): HelloOutcome {
    val event = first.event
    return when {
        event is ServerEvent.Error && event.code == ServerEvent.Error.UNSUPPORTED_PROTOCOL_VERSION -> {
            refusal(event)
        }

        event !is ServerEvent.HelloAck || first.v != SESSION_VERSION -> {
            HelloOutcome.Failed(Ending.ProtocolError("${nameOf(event)} at v${first.v} where hello_ack was due"))
        }

        SESSION_VERSION in event.minVersion..event.maxVersion -> {
            HelloOutcome.Accepted(event, latencyMs)
        }

        else -> {
            HelloOutcome.Failed(Ending.Refused(directionOf(event.maxVersion)))
        }
    }
}

private fun refusal(error: ServerEvent.Error): HelloOutcome {
    val direction = error.direction ?: error.maxVersion?.let(::directionOf)
    return if (direction == null) {
        HelloOutcome.Failed(Ending.ProtocolError("unsupported_protocol_version names no direction"))
    } else {
        HelloOutcome.Refused(error, Ending.Refused(direction))
    }
}

/** A window that ends below protocol v2 is an older daemon's; one that starts above it, a newer one's. */
private fun directionOf(maxVersion: Int): VersionDirection =
    if (maxVersion < SESSION_VERSION) VersionDirection.CLIENT_TOO_NEW else VersionDirection.CLIENT_TOO_OLD

/** An event's name for a diagnostic: never its fields, which may carry an approval's token. */
internal fun nameOf(event: ServerEvent): String =
    when (event) {
        is ServerEvent.Unknown -> event.t
        is ServerEvent.Known -> event::class.simpleName ?: "event"
    }
