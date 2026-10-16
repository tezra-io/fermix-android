package io.tezra.fermix.transport

import computer.iroh.Connection
import computer.iroh.Endpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The connect bound, in seconds, as a failure names it. */
private const val CONNECT_TIMEOUT_SECONDS = ROUTE_CONNECT_TIMEOUT_MS / 1_000

/** How one relay's connect ended. */
private sealed interface RelayOutcome {
    class Connected(
        val connection: Connection,
    ) : RelayOutcome

    class Failed(
        val relayHost: String,
        val reason: String,
    ) : RelayOutcome {
        override fun toString() = "$relayHost: $reason"
    }
}

/**
 * Dials every relay of the plan at once, each bounded at [ROUTE_CONNECT_TIMEOUT_MS], and keeps the first
 * connection: the others are cancelled, and one that connects anyway is closed (M62 stage 6, one relay URL per
 * address in the binding). The relays that failed before the winner are noted; failing at every relay is the
 * route's failure, naming each relay and why. Cancelled, it closes whatever connected, the winner too.
 */
internal suspend fun dialRoute(
    endpoint: Endpoint,
    plan: List<RouteDial>,
    notes: MutableList<String>,
): Connection {
    require(plan.isNotEmpty()) { "a route's dial names a relay" }
    val outcomes = Channel<RelayOutcome>(plan.size)
    val failures = ArrayList<RelayOutcome.Failed>()
    var winner: Connection? = null
    try {
        coroutineScope {
            val dials = plan.map { dial -> launch { outcomes.trySend(connectOnce(endpoint, dial)).getOrThrow() } }
            while (winner == null && failures.size < plan.size) {
                when (val outcome = outcomes.receive()) {
                    is RelayOutcome.Connected -> winner = outcome.connection
                    is RelayOutcome.Failed -> failures += outcome
                }
            }
            dials.forEach { it.cancel() }
        }
    } catch (cancelled: CancellationException) {
        // The scope waited for every dial: what connected is in hand or in the channel.
        winner?.closeSurplus()
        closeSurplus(outcomes)
        throw cancelled
    }
    // Every dial has ended: a connection that came in after the winner is surplus.
    closeSurplus(outcomes)
    notes += failures.map { "a relay failed before another answered: $it" }
    return winner
        ?: throw TransportException.Unreachable(RouteFailure("every relay failed: ${failures.joinToString("; ")}"))
}

private suspend fun connectOnce(
    endpoint: Endpoint,
    dial: RouteDial,
): RelayOutcome =
    try {
        connectWithin(endpoint, dial)?.let { RelayOutcome.Connected(it) }
            ?: RelayOutcome.Failed(dial.relayHost, "timed out after $CONNECT_TIMEOUT_SECONDS s")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expectedFailure: Exception) {
        // A relay that refuses or fails is that relay's outcome; the others may still answer.
        RelayOutcome.Failed(dial.relayHost, reasonOf(expectedFailure))
    }

/**
 * [dial]'s connection within [ROUTE_CONNECT_TIMEOUT_MS], or null past it. The timeout or a cancel may land even as
 * the binding hands the connection back, which then never leaves the timeout's block, so what the connect makes is
 * held outside it and closed on every path but the one that returns it.
 */
private suspend fun connectWithin(
    endpoint: Endpoint,
    dial: RouteDial,
): Connection? {
    var made: Connection? = null
    var inTime: Connection? = null
    try {
        inTime =
            withTimeoutOrNull(ROUTE_CONNECT_TIMEOUT_MS) {
                val address = addressOf(dial)
                try {
                    endpoint.connect(address, MOBILE_ALPN.encodeToByteArray()).also { made = it }
                } finally {
                    address.destroy()
                }
            }
        return inTime
    } finally {
        if (inTime == null) made?.closeSurplus()
    }
}

private fun closeSurplus(outcomes: Channel<RelayOutcome>) =
    generateSequence { outcomes.tryReceive().getOrNull() }
        .filterIsInstance<RelayOutcome.Connected>()
        .forEach { it.connection.closeSurplus() }

private fun Connection.closeSurplus() {
    close(0L, "surplus".encodeToByteArray())
    destroy()
}
