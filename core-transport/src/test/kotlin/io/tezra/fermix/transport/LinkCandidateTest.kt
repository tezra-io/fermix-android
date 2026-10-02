package io.tezra.fermix.transport

import io.tezra.fermix.transport.Candidate.Kind.IP
import io.tezra.fermix.transport.Candidate.Kind.NAME
import io.tezra.fermix.transport.Candidate.Scope.LAN
import io.tezra.fermix.transport.Candidate.Scope.TAILNET
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A pairing link's hosts, classified from their text alone by the ranges the daemon draws them from
 * (PROTOCOL.md "Pairing link"; design section 13.3, step 3): the tailnet's 100.64.0.0/10 and MagicDNS
 * names, the private LAN ranges, and nothing else.
 */
class LinkCandidateTest {
    @Test
    fun `the vendored link's hosts are a MagicDNS name, a tailnet address and a LAN address`() {
        assertEquals(
            Candidate("workstation.tail1234.ts.net", TAILNET, NAME),
            linkCandidate("workstation.tail1234.ts.net"),
        )
        assertEquals(Candidate("100.101.102.103", TAILNET, IP), linkCandidate("100.101.102.103"))
        assertEquals(Candidate("192.168.1.8", LAN, IP), linkCandidate("192.168.1.8"))
    }

    @Test
    fun `each range holds its edges and nothing past them`() {
        val inside =
            mapOf(
                "100.64.0.0" to TAILNET,
                "100.127.255.255" to TAILNET,
                "10.0.0.0" to LAN,
                "10.255.255.255" to LAN,
                "172.16.0.0" to LAN,
                "172.31.255.255" to LAN,
                "192.168.0.0" to LAN,
                "192.168.255.255" to LAN,
            )
        inside.forEach { (host, scope) -> assertEquals(Candidate(host, scope, IP), linkCandidate(host), host) }
        val outside =
            listOf(
                "100.63.255.255",
                "100.128.0.0",
                "9.255.255.255",
                "11.0.0.0",
                "172.15.255.255",
                "172.32.0.0",
                "192.167.255.255",
                "192.169.0.0",
                "127.0.0.1",
                "8.8.8.8",
                "169.254.1.1",
            )
        outside.forEach { host -> assertNull(linkCandidate(host), host) }
    }

    @Test
    fun `an address literal is plain dotted decimal, never looked up`() {
        listOf("010.0.0.1", "10.0.0", "10.0.0.256", "10.0.0.1.", "10.0.0.1 ", "+10.0.0.1", "10.0.0.01", "0x0a.0.0.1")
            .forEach { host -> assertNull(linkCandidate(host), host) }
    }

    @Test
    fun `a name is a MagicDNS name in lowercase labels under ts dot net, and nothing else`() {
        assertEquals(Candidate("a.ts.net", TAILNET, NAME), linkCandidate("a.ts.net"))
        val longest = "${"a".repeat(63)}.${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(54)}.ts.net"
        assertEquals(253, longest.length)
        assertEquals(TAILNET, linkCandidate(longest)?.scope)
        val refused =
            listOf(
                "ts.net",
                ".ts.net",
                "workstation.local",
                "workstation.tail1234.ts.net.",
                "Workstation.tail1234.ts.net",
                "work_station.tail1234.ts.net",
                "-workstation.tail1234.ts.net",
                "workstation-.tail1234.ts.net",
                "work..tail1234.ts.net",
                "${"a".repeat(64)}.ts.net",
                "a$longest",
                "example.com",
                "fd7a:115c:a1e0::1",
                "::1",
            )
        refused.forEach { host -> assertNull(linkCandidate(host), host) }
    }
}
