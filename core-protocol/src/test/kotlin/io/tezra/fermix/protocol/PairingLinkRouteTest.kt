package io.tezra.fermix.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A pairing link's route (PROTOCOL.md "Pairing link"): `iroh`, `relays` and `relays_rev` come together or not
 * at all, from pairing_links.jsonl's second link. Each relay `host:quic_port` is the relay `https://<host>/`
 * with QUIC address discovery on that port.
 */
class PairingLinkRouteTest {
    private val fixture = pairingLinkFixture(1)
    private val link = fixture.getValue("uri").jsonPrimitive.content
    private val query = fixture.getValue("query").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun without(name: String) = link.replace(Regex("(?<=[?&])$name=[^&]*&?"), "").removeSuffix("&")

    private fun replacing(
        name: String,
        value: String,
    ) = link.replace(Regex("(?<=[?&])$name=[^&]*"), Regex.escapeReplacement("$name=$value"))

    @Test
    fun `the vendored link with a route reads it, and its hosts as the link without one`() {
        val parsed = PairingLink.parse(link)
        val relays = listOf(Relay("https://us1.relay.fermix.ai/", 7842), Relay("https://eu1.relay.fermix.ai/", 7842))
        assertEquals(Candidate.Iroh(query.getValue("iroh"), relays, query.getValue("relays_rev")), parsed.route)
        assertEquals(
            PairingLink.parse(pairingLinkFixture(0).getValue("uri").jsonPrimitive.content).candidates,
            parsed.candidates,
        )
    }

    @Test
    fun `a route parameter without the others is refused naming the first one missing`() {
        assertEquals("relays", missing(without("relays")))
        assertEquals("relays_rev", missing(without("relays_rev")))
        assertEquals("iroh", missing(without("iroh")))
        assertEquals("relays", missing(without("relays").let { it.replace(Regex("&relays_rev=[^&]*"), "") }))
        assertEquals("iroh", missing(without("iroh").let { it.replace(Regex("&relays=[^&]*"), "") }))
    }

    @Test
    fun `a link without any of them pairs as before, with no route`() {
        val bare = without("iroh").replace(Regex("&relays=[^&]*"), "").replace(Regex("&relays_rev=[^&]*"), "")
        assertNull(PairingLink.parse(bare).route)
    }

    @Test
    fun `a version-1 link reads no route, however one is written`() {
        val v1 = without("profile").replace("?v=2&", "?v=1&")
        assertNull(PairingLink.parse(v1).route)
        assertNull(PairingLink.parse(v1.replace(Regex("&relays=[^&]*"), "")).route)
    }

    @Test
    fun `eight relays are read, and a ninth refused`() {
        val eight = List(8) { "r$it.relay.fermix.ai%3A${7_842 + it}" }.joinToString("%2C")
        assertEquals(
            8,
            PairingLink
                .parse(replacing("relays", eight))
                .route
                ?.relays
                ?.size,
        )
        assertEquals(
            "https://r7.relay.fermix.ai/",
            PairingLink
                .parse(replacing("relays", eight))
                .route
                ?.relays
                ?.last()
                ?.url,
        )
        assertEquals("relays", malformed(replacing("relays", "$eight%2Cr9.relay.fermix.ai%3A9")))
    }

    @Test
    fun `each malformed route parameter is refused by name`() {
        val cases =
            listOf(
                "iroh" to query.getValue("iroh").uppercase(),
                "iroh" to query.getValue("iroh").drop(1),
                "iroh" to "",
                "relays" to "",
                "relays" to "us1.relay.fermix.ai",
                "relays" to "us1.relay.fermix.ai%3A0",
                "relays" to "us1.relay.fermix.ai%3A65536",
                "relays" to "us1.relay.fermix.ai%3A07842",
                "relays" to "US1.relay.fermix.ai%3A7842",
                "relays" to "-us1.relay.fermix.ai%3A7842",
                "relays" to "us1.relay.fermix.ai.%3A7842",
                "relays" to "us1.relay.fermix.ai%3A7842%2C",
                "relays" to "%2Cus1.relay.fermix.ai%3A7842",
                "relays" to "us1.relay.fermix.ai%3A7842+eu1.relay.fermix.ai%3A7842",
                "relays_rev" to query.getValue("relays_rev").uppercase(),
                "relays_rev" to query.getValue("relays_rev").drop(1),
            )
        cases.forEach { (name, value) -> assertEquals(name, malformed(replacing(name, value)), "$name=$value") }
    }

    private fun missing(link: String) =
        assertThrows<ProtocolException.MissingParameter>(link) {
            PairingLink.parse(link)
        }.name

    private fun malformed(link: String) =
        assertThrows<ProtocolException.MalformedParameter>(link) {
            PairingLink.parse(link)
        }.name
}
