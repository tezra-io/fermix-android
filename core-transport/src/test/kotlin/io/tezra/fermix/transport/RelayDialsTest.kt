package io.tezra.fermix.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How production dials a route (M62 section 3.1): the daemon's node id at every relay the route lists, all at once,
 * one dial per relay as the binding takes one relay URL per address, and no address of its own, as iroh's own
 * discovery through the relays finds the daemon's paths.
 */
class RelayDialsTest {
    @Test
    fun `a route is dialled at each of its relays, by the relay's URL, with no direct address`() {
        val node = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"
        val route =
            Candidate.Route(
                node,
                listOf(Candidate.Relay("us1.relay.fermix.ai", 7842), Candidate.Relay("eu1.relay.fermix.ai", 7842)),
                "374b4c8f103627f7",
            )
        assertEquals(
            listOf(
                RouteDial(node, "us1.relay.fermix.ai", "https://us1.relay.fermix.ai/", addresses = emptyList()),
                RouteDial(node, "eu1.relay.fermix.ai", "https://eu1.relay.fermix.ai/", addresses = emptyList()),
            ),
            relayDials(route),
        )
    }
}
