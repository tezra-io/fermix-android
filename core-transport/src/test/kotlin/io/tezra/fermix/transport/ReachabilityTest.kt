package io.tezra.fermix.transport

import io.tezra.fermix.transport.Candidate.Kind.IP
import io.tezra.fermix.transport.Candidate.Kind.NAME
import io.tezra.fermix.transport.Candidate.Scope.LAN
import io.tezra.fermix.transport.Candidate.Scope.TAILNET
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.aggregator.ArgumentsAccessor
import org.junit.jupiter.params.provider.CsvSource
import java.net.InetAddress

/** Design section 5.2's facts and what they say, every row of the truth table. */
class ReachabilityTest {
    private val tailnet = Candidate("100.101.102.103", TAILNET, IP)
    private val magicDns = Candidate("workstation.tail1234.ts.net", TAILNET, NAME)
    private val lan = Candidate("192.168.1.8", LAN, IP)

    // Columns: a network, a VPN on it, a 100.64/10 address on it, another app's VPN, a tailnet
    // candidate, and what they say.
    @ParameterizedTest(name = "network={0} vpn={1} cgnat={2} otherVpn={3} tailnet={4} -> {5}")
    @CsvSource(
        // No network, so no default network's VPN or address: nothing else is read.
        "false, false, false, false, false, NO_NETWORK",
        "false, false, false, false, true,  NO_NETWORK",
        "false, false, false, true,  false, NO_NETWORK",
        "false, false, false, true,  true,  NO_NETWORK",
        // A VPN applies to this app and holds a 100.64/10 address.
        "true,  true,  true,  false, false, TAILNET_LIKELY",
        "true,  true,  true,  false, true,  TAILNET_LIKELY",
        "true,  true,  true,  true,  false, TAILNET_LIKELY",
        "true,  true,  true,  true,  true,  TAILNET_LIKELY",
        // A VPN applies to this app with no 100.64/10 address.
        "true,  true,  false, false, true,  VPN_HOLDS_THE_SLOT",
        "true,  true,  false, true,  true,  VPN_HOLDS_THE_SLOT",
        "true,  true,  false, false, false, PLAIN",
        "true,  true,  false, true,  false, PLAIN",
        // No VPN applies to this app, and another app's VPN is up.
        "true,  false, false, true,  true,  EXCLUDED_FROM_TAILSCALE",
        "true,  false, true,  true,  true,  EXCLUDED_FROM_TAILSCALE",
        "true,  false, false, true,  false, PLAIN",
        "true,  false, true,  true,  false, PLAIN",
        // No VPN at all; a 100.64/10 address without one is a carrier's NAT.
        "true,  false, false, false, true,  TAILSCALE_OFF",
        "true,  false, false, false, false, TAILSCALE_OFF",
        "true,  false, true,  false, true,  TAILSCALE_OFF",
        "true,  false, true,  false, false, TAILSCALE_OFF",
    )
    fun `the facts and the candidates give one reachability`(row: ArgumentsAccessor) {
        val network = if (row.flag(0)) NETWORK else null
        val facts = facts(network, vpn = row.flag(1), cgnat = row.flag(2), otherVpn = row.flag(3))
        val candidates = if (row.flag(4)) listOf(lan, magicDns) else listOf(lan)
        assertEquals(row.get(5, Reachability::class.java), reachability(facts, candidates))
    }

    @Test
    fun `a MagicDNS name alone is a tailnet candidate, and no candidate at all is none`() {
        val slotTaken = facts(NETWORK, vpn = true)
        assertEquals(Reachability.VPN_HOLDS_THE_SLOT, reachability(slotTaken, listOf(magicDns)))
        assertEquals(Reachability.VPN_HOLDS_THE_SLOT, reachability(slotTaken, listOf(tailnet)))
        assertEquals(Reachability.PLAIN, reachability(slotTaken, emptyList()))
    }

    @Test
    fun `facts without a default network carry nothing of one`() {
        assertThrows<IllegalArgumentException> { facts(null, vpn = true) }
        assertThrows<IllegalArgumentException> { facts(null, cgnat = true) }
        assertFalse(NetworkFacts.NONE.hasNetwork)
        assertTrue(facts(NETWORK).hasNetwork)
    }

    @Test
    fun `a VPN network that is this app's default is not another app's`() {
        val tailscale = listOf(address("100.101.102.104"))
        val ours = networkFacts(VPN, defaultIsVpn = true, defaultAddresses = tailscale, vpnNetworks = setOf(VPN))
        assertEquals(facts(VPN, vpn = true, cgnat = true), ours)
        val home = listOf(address("192.168.1.20"))
        val excluded = networkFacts(NETWORK, defaultIsVpn = false, defaultAddresses = home, vpnNetworks = setOf(VPN))
        assertEquals(facts(NETWORK, otherVpn = true), excluded)
        val none = networkFacts(null, defaultIsVpn = false, defaultAddresses = emptyList(), vpnNetworks = emptySet())
        assertEquals(NetworkFacts.NONE, none)
    }

    @Test
    fun `100_64_0_0 slash 10 is the shared address range, and nothing beside it`() {
        assertTrue(isCgnatAddress(address("100.64.0.0")))
        assertTrue(isCgnatAddress(address("100.101.102.103")))
        assertTrue(isCgnatAddress(address("100.127.255.255")))
        assertFalse(isCgnatAddress(address("100.63.255.255")))
        assertFalse(isCgnatAddress(address("100.128.0.0")))
        assertFalse(isCgnatAddress(address("10.0.0.1")))
        assertFalse(isCgnatAddress(address("fd7a:115c:a1e0::1")))
    }

    private fun facts(
        network: Long?,
        vpn: Boolean = false,
        cgnat: Boolean = false,
        otherVpn: Boolean = false,
    ) = NetworkFacts(network, defaultHasVpn = vpn, defaultHasCgnatAddress = cgnat, otherUidVpnPresent = otherVpn)

    private fun ArgumentsAccessor.flag(column: Int): Boolean = getBoolean(column) ?: error("column $column is empty")

    /** An address literal, which InetAddress parses without a lookup. */
    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)

    private companion object {
        const val NETWORK = 101L
        const val VPN = 102L
    }
}
