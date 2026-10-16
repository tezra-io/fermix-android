package io.tezra.fermix.transport

import io.tezra.fermix.transport.Candidate.Kind.IP
import io.tezra.fermix.transport.Candidate.Kind.NAME
import io.tezra.fermix.transport.Candidate.Scope.LAN
import io.tezra.fermix.transport.Candidate.Scope.TAILNET
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Design section 5.1's order and M62's: last successful, tailnet addresses, MagicDNS names, LAN addresses, route. */
class CandidateOrderTest {
    private val route =
        Candidate.Route("7".repeat(64), listOf(Candidate.Relay("us1.relay.fermix.ai", 7842)), "374b4c8f103627f7")

    private val lanIp = Candidate.Host("192.168.1.8", LAN, IP)
    private val lanIp2 = Candidate.Host("10.0.0.8", LAN, IP)
    private val lanName = Candidate.Host("workstation.local", LAN, NAME)
    private val tailnetIp = Candidate.Host("100.101.102.103", TAILNET, IP)
    private val tailnetIp2 = Candidate.Host("fd7a:115c:a1e0::1", TAILNET, IP)
    private val magicDns = Candidate.Host("workstation.tail1234.ts.net", TAILNET, NAME)

    @Test
    fun `tailnet addresses come first, then MagicDNS names, then LAN addresses, then LAN names`() {
        assertEquals(
            listOf(tailnetIp, magicDns, lanIp, lanName),
            candidateOrder(listOf(lanName, lanIp, magicDns, tailnetIp)),
        )
    }

    @Test
    fun `candidates of one class keep the order they were given in`() {
        assertEquals(
            listOf(tailnetIp2, tailnetIp, lanIp2, lanIp),
            candidateOrder(listOf(lanIp2, tailnetIp2, lanIp, tailnetIp)),
        )
    }

    @Test
    fun `the last successful candidate goes first and is tried once`() {
        assertEquals(
            listOf(lanIp, tailnetIp, magicDns),
            candidateOrder(listOf(magicDns, lanIp, tailnetIp), lastSuccessful = lanIp),
        )
    }

    @Test
    fun `a last successful candidate the list no longer holds is not tried`() {
        assertEquals(listOf(tailnetIp, lanIp), candidateOrder(listOf(lanIp, tailnetIp), lastSuccessful = lanIp2))
    }

    @Test
    fun `a candidate listed twice is tried once`() {
        assertEquals(listOf(tailnetIp, lanIp), candidateOrder(listOf(lanIp, tailnetIp, lanIp)))
    }

    @Test
    fun `more than the wire's 16 candidates is refused`() {
        val seventeen = (1..17).map { Candidate.Host("192.168.1.$it", LAN, IP) }
        assertEquals(16, candidateOrder(seventeen.take(16)).size)
        assertThrows<IllegalArgumentException> { candidateOrder(seventeen) }
    }

    @Test
    fun `a host that is empty or past 253 bytes is refused`() {
        assertThrows<IllegalArgumentException> { Candidate.Host("", LAN, IP) }
        assertEquals(253, Candidate.Host("a".repeat(253), LAN, NAME).host.length)
        assertThrows<IllegalArgumentException> { Candidate.Host("a".repeat(254), LAN, NAME) }
    }

    @Test
    fun `the route goes last, after every host`() {
        assertEquals(
            listOf(tailnetIp, magicDns, lanIp, lanName, route),
            candidateOrder(listOf(route, lanName, lanIp, magicDns, tailnetIp)),
        )
    }

    @Test
    fun `a route that was the last successful goes first`() {
        assertEquals(
            listOf(route, tailnetIp, lanIp),
            candidateOrder(listOf(lanIp, route, tailnetIp), lastSuccessful = route),
        )
    }

    @Test
    fun `sixteen hosts and the route race the best fifteen hosts and the route, sixteen in all`() {
        val sixteen = (1..16).map { Candidate.Host("192.168.1.$it", LAN, IP) }
        val ordered = candidateOrder(sixteen + route)
        assertEquals(MAX_CANDIDATES, ordered.size)
        assertEquals(sixteen.take(15) + route, ordered)
        val withLast = candidateOrder(sixteen + route, lastSuccessful = sixteen.last())
        assertEquals(listOf(sixteen.last()) + sixteen.take(14) + route, withLast)
    }

    @Test
    fun `two routes are refused`() {
        val other = route.copy(nodeId = "8".repeat(64))
        assertThrows<IllegalArgumentException> { candidateOrder(listOf(route, other)) }
    }

    @Test
    fun `a host never lies in the remote scope, and a route's fields are the wire's`() {
        assertThrows<IllegalArgumentException> { Candidate.Host("192.168.1.8", Candidate.Scope.REMOTE, IP) }
        assertEquals(Candidate.Scope.REMOTE, route.scope)
        assertEquals("https://us1.relay.fermix.ai/", route.relays.single().url)
        val relay = route.relays.single()
        listOf(
            { route.copy(nodeId = "7".repeat(63)) },
            { route.copy(nodeId = "A".repeat(64)) },
            { route.copy(relays = emptyList()) },
            { route.copy(relays = List(9) { relay }) },
            { route.copy(revision = "374B4C8F103627F7") },
            { Candidate.Relay("US1.relay.fermix.ai", 7842) },
            { Candidate.Relay("us1.relay.fermix.ai", 0) },
            { Candidate.Relay("us1.relay.fermix.ai", 65_536) },
        ).forEach { make -> assertThrows<IllegalArgumentException> { make() } }
    }
}
