package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Relay
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.io.IOException
import io.tezra.fermix.protocol.Candidate as WireCandidate

/** The link's route as its three parameters (PROTOCOL.md "Pairing link"). */
private val LINK_ROUTE =
    mapOf(
        "iroh" to ROUTE.nodeId,
        "relays" to "us1.relay.fermix.ai:7842,eu1.relay.fermix.ai:7842",
        "relays_rev" to ROUTE.revision,
    )

/**
 * A pairing link with a route pairs from any network (PROTOCOL.md "Pairing link" and "Remote reach"): its hosts and
 * its route race together, the route last, and the record keeps the route `pair_approved` names, else the link's,
 * which the session the pairing hands over acknowledges in its every `hello` once the record is written.
 */
class PairingRouteTest {
    @Test
    fun `a link's route races after its hosts, and wins when they fail`() =
        runTest {
            val harness = PairingHarness(this)
            harness.daemon.refusal =
                { candidate -> IOException("no route to $candidate").takeIf { candidate is Candidate.Host } }
            harness.start(harness.linkText(route = LINK_ROUTE))
            val connection = harness.paired()
            assertEquals(ROUTE, connection.candidate)
            assertEquals(listOf(TAILNET, ROUTE), harness.daemon.dialed)
            connection.pairRequest()
            connection.send(APPROVAL)
            val facts = harness.state<PairingState.Approved>().facts
            // pair_approved names no route: the link's is the record's.
            assertEquals(ROUTE, facts.route)
            assertEquals(listOf(APPROVED_ROUTE), facts.candidates)
        }

    @Test
    fun `pair_approved's route is the record's over the link's`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start(harness.linkText(route = LINK_ROUTE))
            val connection = harness.paired()
            connection.pairRequest()
            val newer =
                WireCandidate.Iroh(
                    ROUTE.nodeId,
                    listOf(Relay("https://ap1.relay.fermix.ai/", 7842)),
                    "00000000000000a1",
                )
            connection.send(APPROVAL.copy(candidates = APPROVAL.candidates + newer))
            val facts = harness.state<PairingState.Approved>().facts
            assertEquals(
                Candidate.Route(ROUTE.nodeId, listOf(Candidate.Relay("ap1.relay.fermix.ai", 7842)), "00000000000000a1"),
                facts.route,
            )
        }

    @Test
    fun `a link with a route and no host pairs over the route`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start(harness.linkText(candidates = emptyList(), route = LINK_ROUTE))
            val connection = harness.paired()
            assertEquals(ROUTE, connection.candidate)
            connection.pairRequest()
            connection.send(APPROVAL)
            assertEquals(ROUTE, harness.state<PairingState.Approved>().facts.route)
        }

    @Test
    fun `the session a pairing hands over names the saved route's revision in each later hello`() =
        runTest {
            val harness = PairingHarness(this)
            var saved: Candidate.Route? = null
            harness.relayListRev = { saved?.revision }
            harness.start(harness.linkText(route = LINK_ROUTE))
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            // Its first hello goes before the record is written: the route is saved before it is acknowledged.
            assertNull(assertInstanceOf<ClientEvent.Hello>(connection.next().event).relayListRev)
            connection.send(HELLO_ACK)
            val approved = harness.state<PairingState.Approved>()
            saved = approved.facts.route
            connection.close(1000, "Noise session lifetime reached")
            val next = harness.daemon.accept()
            next.handshake()
            assertEquals(ROUTE.revision, assertInstanceOf<ClientEvent.Hello>(next.next().event).relayListRev)
            approved.session.close()
        }

    @Test
    fun `a link without a route pairs as before, and its record holds none`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val facts = harness.approved().facts
            assertEquals(null, facts.route)
            assertTrue(harness.daemon.dialed.none { it is Candidate.Route })
        }
}
