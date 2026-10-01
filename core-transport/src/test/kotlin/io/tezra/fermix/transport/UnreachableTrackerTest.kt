package io.tezra.fermix.transport

import io.tezra.fermix.transport.Candidate.Kind.IP
import io.tezra.fermix.transport.Candidate.Scope.TAILNET
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * "Can't reach" needs both facts (onboarding gotcha 20, design section 13.5): the phone has a network,
 * and every candidate has failed for 30 s. No network is "Waiting for network"; the first completed
 * handshake clears either line.
 */
class UnreachableTrackerTest {
    private val online =
        NetworkFacts(1L, defaultHasVpn = false, defaultHasCgnatAddress = false, otherUidVpnPresent = false)
    private val otherNetwork = online.copy(defaultNetwork = 2L)
    private val candidate = Candidate("100.101.102.103", TAILNET, IP)

    @Test
    fun `with no network yet it is waiting for one`() {
        assertEquals(LinkStatus.WaitingForNetwork, UnreachableTracker().status(nowMs = 0))
    }

    @Test
    fun `with a network it is connecting until every candidate has failed for 30 s, then it cannot reach`() {
        var tracker = UnreachableTracker().network(online)
        assertEquals(LinkStatus.Connecting, tracker.status(nowMs = 0))
        tracker = tracker.allFailed(atMs = 1_000)
        assertEquals(31_000L, tracker.cannotReachAtMs)
        tracker = tracker.allFailed(atMs = 3_000).allFailed(atMs = 7_000)
        assertEquals(LinkStatus.Connecting, tracker.status(nowMs = 30_999))
        assertEquals(LinkStatus.CannotReach, tracker.status(nowMs = 31_000))
        assertEquals(LinkStatus.CannotReach, tracker.status(nowMs = 600_000))
    }

    @Test
    fun `the first completed handshake clears cannot reach, and a later loss starts the clock again`() {
        var tracker = UnreachableTracker().network(online).allFailed(atMs = 0)
        assertEquals(LinkStatus.CannotReach, tracker.status(nowMs = 30_000))
        tracker = tracker.connected(candidate)
        assertEquals(LinkStatus.Connected(candidate), tracker.status(nowMs = 30_000))
        assertNull(tracker.cannotReachAtMs)
        tracker = tracker.lost()
        assertEquals(LinkStatus.Connecting, tracker.status(nowMs = 90_000))
        tracker = tracker.allFailed(atMs = 90_000)
        assertEquals(LinkStatus.Connecting, tracker.status(nowMs = 119_999))
        assertEquals(LinkStatus.CannotReach, tracker.status(nowMs = 120_000))
    }

    @Test
    fun `failures without a network never start the clock, and losing the network stops it`() {
        var tracker = UnreachableTracker().allFailed(atMs = 0)
        assertNull(tracker.cannotReachAtMs)
        assertEquals(LinkStatus.WaitingForNetwork, tracker.status(nowMs = 60_000))
        tracker = tracker.network(online).allFailed(atMs = 60_000)
        tracker = tracker.network(NetworkFacts.NONE)
        assertEquals(LinkStatus.WaitingForNetwork, tracker.status(nowMs = 100_000))
        tracker = tracker.network(online)
        assertEquals(LinkStatus.Connecting, tracker.status(nowMs = 100_000))
        tracker = tracker.allFailed(atMs = 100_000)
        assertEquals(130_000L, tracker.cannotReachAtMs)
    }

    @Test
    fun `a change from one network to another keeps the clock running`() {
        val tracker = UnreachableTracker().network(online).allFailed(atMs = 0).network(otherNetwork)
        assertEquals(LinkStatus.CannotReach, tracker.status(nowMs = 30_000))
    }

    @Test
    fun `a live connection whose network is gone is waiting for one`() {
        val tracker = UnreachableTracker().network(online).connected(candidate).network(NetworkFacts.NONE)
        assertEquals(LinkStatus.WaitingForNetwork, tracker.status(nowMs = 0))
    }

    @Test
    fun `a network that comes back is connecting until a new handshake completes`() {
        var tracker = UnreachableTracker().network(online).connected(candidate).network(NetworkFacts.NONE)
        tracker = tracker.network(otherNetwork)
        assertEquals(LinkStatus.Connecting, tracker.status(nowMs = 0))
        tracker = tracker.connected(candidate)
        assertEquals(LinkStatus.Connected(candidate), tracker.status(nowMs = 0))
    }
}
