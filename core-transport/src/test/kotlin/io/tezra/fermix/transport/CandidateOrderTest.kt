package io.tezra.fermix.transport

import io.tezra.fermix.transport.Candidate.Kind.IP
import io.tezra.fermix.transport.Candidate.Kind.NAME
import io.tezra.fermix.transport.Candidate.Scope.LAN
import io.tezra.fermix.transport.Candidate.Scope.TAILNET
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Design section 5.1's order: last successful, tailnet addresses, MagicDNS names, LAN addresses. */
class CandidateOrderTest {
    private val lanIp = Candidate("192.168.1.8", LAN, IP)
    private val lanIp2 = Candidate("10.0.0.8", LAN, IP)
    private val lanName = Candidate("workstation.local", LAN, NAME)
    private val tailnetIp = Candidate("100.101.102.103", TAILNET, IP)
    private val tailnetIp2 = Candidate("fd7a:115c:a1e0::1", TAILNET, IP)
    private val magicDns = Candidate("workstation.tail1234.ts.net", TAILNET, NAME)

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
        val seventeen = (1..17).map { Candidate("192.168.1.$it", LAN, IP) }
        assertEquals(16, candidateOrder(seventeen.take(16)).size)
        assertThrows<IllegalArgumentException> { candidateOrder(seventeen) }
    }

    @Test
    fun `a host that is empty or past 253 bytes is refused`() {
        assertThrows<IllegalArgumentException> { Candidate("", LAN, IP) }
        assertEquals(253, Candidate("a".repeat(253), LAN, NAME).host.length)
        assertThrows<IllegalArgumentException> { Candidate("a".repeat(254), LAN, NAME) }
    }
}
