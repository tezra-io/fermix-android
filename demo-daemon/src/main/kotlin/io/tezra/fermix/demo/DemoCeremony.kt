package io.tezra.fermix.demo

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.PairDeniedReason
import io.tezra.fermix.protocol.Platform
import io.tezra.fermix.protocol.Profile
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.delay

/** The main profile, the one a daemon has (PROTOCOL.md "Client events"). */
internal const val MAIN = "main"

/** A chain's bounds, as the phone's own shape check holds them (attest's Chain): six certificates, 16 KiB. */
private const val MAX_CERTIFICATES = 6
private const val MAX_CHAIN_BYTES = 16 * 1024

/** The SubjectPublicKeyInfo of an X25519 key, before its 32 raw bytes: the leaf carries the phone's key so. */
private const val X25519_SPKI_PREFIX = "302a300506032b656e032100"

/** The most frames a phone sends while the owner decides, pings among them, before the demo approves anyway. */
private const val MAX_WAIT_FRAMES = 1_000

/**
 * The pairing ceremony on the daemon's side (PROTOCOL.md "Noise modes and pairing"; design section 6.3): IKpsk2
 * with the open window's secret, answered after [DemoTimes.check], `pair_request` within the handshake deadline, its
 * attestation's shape, then the computer's approval [DemoTimes.approve] after the request, answering pings meanwhile,
 * then `pair_approved` and the same session on as a paired one. A window pairs once, within [DemoTimes.window] of its
 * opening, 120 s as PROTOCOL.md's "Pairing link" has it; past that its link is refused. The demo checks the chain
 * as the phone does, its shape and that its leaf carries the handshake's key, and no more: a real daemon also holds
 * it to Google's roots, KeyMint's facts and a locked bootloader (design section 6.2), which an emulator's chain
 * fails.
 */
internal class DemoCeremony(
    private val connection: DemoConnection,
) {
    private val home = connection.home
    private val times = connection.parts.times

    suspend fun pair(first: ByteArray) {
        // Message 1 waits for message 2 while Connecting shows "Checking".
        delay(times.check)
        val window = home.windowAt(connection.parts.nowMs(), times.window)
        // No window open, or one past its life: refused before message 2, as a closed window's is (the phone's
        // Expired).
        if (window == null) return connection.close(PROTOCOL_ERROR, "mobile protocol error")
        val responder = IkResponder(home.fermix.gatewayKey, home.fermix.secret(window))
        val request = if (connection.respond(first, responder)) pairRequest() else null
        if (request != null && admitted(request) && ownerDecides()) {
            approve()
            DemoSession(connection).serve()
        }
    }

    /** The phone's `pair_request`, or none: the deadline passed (`1008`), the phone left, or it sent another event. */
    private suspend fun pairRequest(): ClientFrame? {
        val read = connection.receive(times.handshakeDeadline)
        val frame = (read as? Read.Got)?.frame
        when {
            read == Read.Quiet -> {
                connection.close(HANDSHAKE_DEADLINE, "mobile handshake deadline")
            }

            read is Read.Bound -> {
                connection.close(read.code, read.reason)
            }

            frame != null && frame.event !is ClientEvent.PairRequest -> {
                connection.close(
                    PROTOCOL_ERROR,
                    "mobile protocol error",
                )
            }
        }
        return frame?.takeIf { it.event is ClientEvent.PairRequest }
    }

    /** Whether the request's attestation passes; one that fails is denied `attestation` and closed. */
    private fun admitted(request: ClientFrame): Boolean {
        val passes = attested(request)
        if (!passes) deny(PairDeniedReason.ATTESTATION)
        return passes
    }

    /**
     * Whether the request's chain has the shape attest's own check gives it, its lengths add up to its raw tail,
     * and its leaf carries the key that ran the handshake.
     */
    private fun attested(frame: ClientFrame): Boolean {
        val request = frame.event as ClientEvent.PairRequest
        val lengths = request.attestation?.certLengths.orEmpty()
        val shaped =
            request.platform == Platform.ANDROID && lengths.size in 1..MAX_CERTIFICATES &&
                lengths.sum() == frame.raw.size && frame.raw.size <= MAX_CHAIN_BYTES
        if (!shaped) return false
        val leaf = frame.raw.copyOf(lengths.first())
        return contains(leaf, (X25519_SPKI_PREFIX + connection.phoneKey).hexToByteArray())
    }

    /**
     * The computer's owner reading the code for [DemoTimes.approve], while the phone's pings are answered and any
     * other event refused; whether the phone is still there to be approved.
     */
    private suspend fun ownerDecides(): Boolean {
        val until = connection.parts.nowMs() + times.approve
        var read: Read
        var frames = 0
        do {
            val left = until - connection.parts.nowMs()
            read = if (left > 0) connection.receive(left) else Read.Quiet
            (read as? Read.Got)?.let { meanwhile(it.frame) }
            frames++
        } while (read is Read.Got && frames < MAX_WAIT_FRAMES)
        if (read is Read.Bound) connection.close(read.code, read.reason)
        return read is Read.Got || read == Read.Quiet
    }

    /** A frame while the owner decides: a ping is answered, any other event refused. */
    private suspend fun meanwhile(frame: ClientFrame) {
        if (frame.event != ClientEvent.Ping) return refuse()
        delay(times.pong)
        connection.send(ServerEvent.Pong)
    }

    private fun refuse() = connection.send(ServerEvent.Error("unsupported_event", "the owner is still deciding"))

    private fun approve() {
        val deviceId = "demo-${home.fermix.index}-${home.devices.size + home.forgotten.size + 1}"
        home.devices[connection.phoneKey] = deviceId
        home.openWindow = null
        val profiles = listOf(Profile(MAIN, home.fermix.agent))
        val salt = base64(home.fermix.pushSalt)
        connection.send(
            ServerEvent.PairApproved(deviceId, listOf(home.fermix.wireRoute), profiles, salt, push = emptyList()),
        )
    }

    private fun deny(reason: PairDeniedReason) {
        connection.send(ServerEvent.PairDenied(reason))
        connection.close(REVOKED, "pairing ${reason.name.lowercase()}")
    }
}

/** Whether [haystack] holds [needle] as a run of bytes. */
private fun contains(
    haystack: ByteArray,
    needle: ByteArray,
): Boolean = (0..haystack.size - needle.size).any { at -> needle.indices.all { haystack[at + it] == needle[it] } }
