package io.tezra.fermix.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val NODE = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"
private const val REV = "374b4c8f103627f7"
private const val HOST = """{"host":"192.168.1.8","interface":"en0","scope":"lan"}"""
private const val US1 = """{"url":"https://us1.relay.fermix.ai/","quic_port":7842}"""

/**
 * A candidate of `hello_ack` and `pair_approved` is a host (no `kind`) or the iroh route (`kind` "iroh"); a
 * candidate of any other kind is skipped, never refused and never a host (PROTOCOL.md "Server events" and
 * "Remote reach"). The list holds at most 16 of them, skipped ones counted, and the route may sit anywhere in it.
 */
class CandidateUnionTest {
    private fun helloAck(candidates: String) =
        """{"v":2,"t":"hello_ack","seq":1,"session_id":"s","min_version":2,"max_version":2,"profiles":[],""" +
            """"candidates":$candidates,"history_head_seq":0,"read_up_to_seq":0,"caps":{"commands":[],""" +
            """"max_media_bytes":1}}"""

    private fun iroh(
        node: String = "\"$NODE\"",
        relays: String = "[$US1]",
        rev: String = "\"$REV\"",
    ) = """{"kind":"iroh","node_id":$node,"relays":$relays,"relays_rev":$rev}"""

    private fun candidatesOf(header: String): List<Candidate> =
        (decodeServerEvent(frameOf(header)).event as ServerEvent.HelloAck).candidates

    private fun refused(candidates: String): ProtocolException =
        assertThrows<ProtocolException>(candidates) { decodeServerEvent(frameOf(helloAck(candidates))) }

    @Test
    fun `a candidate without a kind is a host, and the iroh kind is the route`() {
        val candidates = candidatesOf(helloAck("[$HOST,${iroh()}]"))
        assertEquals(Candidate.Host("192.168.1.8", "en0", CandidateScope.LAN), candidates[0])
        val relay = Relay("https://us1.relay.fermix.ai/", 7842)
        assertEquals(Candidate.Iroh(NODE, listOf(relay), REV), candidates[1])
    }

    @Test
    fun `the route encodes with its kind first, as the daemon writes it`() {
        val header = helloAck("[$HOST,${iroh()}]")
        val decoded = decodeServerEvent(frameOf(header))
        assertEquals(header, headerOf(encodeServerEvent(2, 1uL, decoded.event as ServerEvent.Known)))
    }

    @Test
    fun `a candidate of a kind this app does not know is skipped, not refused, whatever else it holds`() {
        val wireguard = """{"kind":"wireguard","peer":"x","node_id":"not hex","relays":[]}"""
        val numbered = """{"kind":7,"host":"192.168.1.9"}"""
        val candidates = candidatesOf(helloAck("[$HOST,$wireguard,$numbered,${iroh()}]"))
        assertEquals(4, candidates.size)
        val skipped = candidates.filterIsInstance<Candidate.Skipped>()
        assertEquals(listOf("wireguard", null), skipped.map { it.kind })
        assertTrue(candidates[3] is Candidate.Iroh)
    }

    @Test
    fun `a skipped candidate encodes back as it came`() {
        val header = helloAck("""[{"kind":"wireguard","peer":"x"},$HOST]""")
        val decoded = decodeServerEvent(frameOf(header))
        assertEquals(header, headerOf(encodeServerEvent(2, 1uL, decoded.event as ServerEvent.Known)))
    }

    @Test
    fun `the route need not be last, and two routes are both read`() {
        val candidates = candidatesOf(helloAck("[${iroh()},$HOST,${iroh(rev = "\"0000000000000001\"")}]"))
        assertEquals(listOf(true, false, true), candidates.map { it is Candidate.Iroh })
    }

    @Test
    fun `a list of more than 16 candidates is refused, skipped ones counted`() {
        val sixteen = List(15) { HOST } + """{"kind":"other"}"""
        candidatesOf(helloAck(sixteen.joinToString(",", "[", "]")))
        val field = (refused((sixteen + HOST).joinToString(",", "[", "]")) as ProtocolException.InvalidField).field
        assertEquals("candidates", field)
    }

    @Test
    fun `a route that breaks the schema is refused under its path`() {
        val cases =
            mapOf(
                iroh(node = "\"${NODE.uppercase()}\"") to "candidates[0].node_id",
                iroh(node = "\"${NODE.drop(1)}\"") to "candidates[0].node_id",
                iroh(relays = "[]") to "candidates[0].relays",
                iroh(relays = List(9) { US1 }.joinToString(",", "[", "]")) to "candidates[0].relays",
                iroh(relays = """[{"url":"http://us1.relay.fermix.ai/","quic_port":7842}]""") to
                    "candidates[0].relays[0].url",
                iroh(relays = """[{"url":"https://us1.relay.fermix.ai","quic_port":7842}]""") to
                    "candidates[0].relays[0].url",
                iroh(relays = """[{"url":"https://US1.relay.fermix.ai/","quic_port":7842}]""") to
                    "candidates[0].relays[0].url",
                iroh(relays = """[{"url":"https://us1.relay.fermix.ai/","quic_port":0}]""") to
                    "candidates[0].relays[0].quic_port",
                iroh(relays = """[{"url":"https://us1.relay.fermix.ai/","quic_port":65536}]""") to
                    "candidates[0].relays[0].quic_port",
                iroh(rev = "\"374b4c8f103627f\"") to "candidates[0].relays_rev",
                iroh(rev = "\"374B4C8F103627F7\"") to "candidates[0].relays_rev",
            )
        cases.forEach { (route, path) ->
            val refusal = refused("[$route]")
            assertTrue(refusal is ProtocolException.InvalidField, "$route: $refusal")
            assertEquals(path, (refusal as ProtocolException.InvalidField).field, route)
        }
    }

    @Test
    fun `a route missing a field is refused by its path`() {
        val missing = """{"kind":"iroh","node_id":"$NODE","relays":[$US1]}"""
        assertEquals("candidates[0].relays_rev", (refused("[$missing]") as ProtocolException.MissingField).field)
        val noPort = """{"kind":"iroh","node_id":"$NODE","relays":[{"url":"https://a/"}],"relays_rev":"$REV"}"""
        assertEquals(
            "candidates[0].relays[0].quic_port",
            (refused("[$noPort]") as ProtocolException.MissingField).field,
        )
    }

    @Test
    fun `pair_approved reads the route as hello_ack does`() {
        val header =
            """{"v":2,"t":"pair_approved","seq":1,"device_id":"d","candidates":[$HOST,${iroh()}],"profiles":[],""" +
                """"push_salt":"c3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3M="}"""
        val approved = decodeServerEvent(frameOf(header)).event as ServerEvent.PairApproved
        assertEquals(NODE, (approved.candidates.last() as Candidate.Iroh).nodeId)
        assertNull(approved.candidates.firstOrNull { it is Candidate.Skipped })
    }
}
