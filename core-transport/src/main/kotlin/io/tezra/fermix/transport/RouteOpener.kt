package io.tezra.fermix.transport

/** The ALPN a mobile session takes over the route (PROTOCOL.md "Remote reach"). */
const val MOBILE_ALPN = "fermix/mobile/1"

/** Each relay's connect is bounded so; the race's own connect bound, as a WebSocket's (M62 stage 6). */
const val ROUTE_CONNECT_TIMEOUT_MS = 10_000L

/** Opens a session's stream over a daemon's iroh route: the phone's one iroh endpoint in production ([IrohRail]). */
fun interface RouteOpener {
    /**
     * A new stream to [route]'s daemon, its connection kept for the next; or [TransportException.Unreachable],
     * whose [RouteFailure] names each relay and how its connect failed, or the endpoint that did not bind.
     */
    suspend fun open(route: Candidate.Route): RouteStream
}

/**
 * One connect of a route's dial: the daemon's [nodeId] through the relay at [relayUrl], and [addresses] to try
 * directly, none in production, where the relays and iroh's own discovery through them find the daemon.
 */
data class RouteDial(
    val nodeId: String,
    val relayHost: String,
    val relayUrl: String,
    val addresses: List<String>,
)

/** The production plan: every relay of [route] at once, each with no address of its own (M62 stage 6). */
fun relayDials(route: Candidate.Route): List<RouteDial> =
    route.relays.map { RouteDial(route.nodeId, it.host, it.url, addresses = emptyList()) }
