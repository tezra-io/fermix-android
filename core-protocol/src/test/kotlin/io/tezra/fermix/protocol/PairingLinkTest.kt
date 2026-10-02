package io.tezra.fermix.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

/** The pairing link (PROTOCOL.md "Pairing link"), from the vendored pairing_links.jsonl. */
class PairingLinkTest {
    private val fixture = Json.parseToJsonElement(vendored("fixtures/pairing_links.jsonl").trim()).jsonObject
    private val link = fixture.getValue("uri").jsonPrimitive.content
    private val query = fixture.getValue("query").jsonObject.mapValues { it.value.jsonPrimitive.content }

    /** The link with one parameter dropped; the version comes first, after the `?`. */
    private fun without(name: String) = link.replace(Regex("(?<=[?&])$name=[^&]*&?"), "")

    private fun replacing(
        name: String,
        value: String,
    ) = link.replace(Regex("(?<=[?&])$name=[^&]*"), Regex.escapeReplacement("$name=$value"))

    @Test
    fun `the vendored link parses to exactly its decoded fields`() {
        val parsed = PairingLink.parse(link)
        assertEquals(query.getValue("v").toInt(), parsed.version)
        assertEquals(
            Json.parseToJsonElement(query.getValue("candidates")).jsonArray.map {
                it.jsonPrimitive.content
            },
            parsed.candidates,
        )
        assertEquals(query.getValue("port").toInt(), parsed.port)
        assertArrayEquals(query.getValue("tls_fp").hexToByteArray(), parsed.tlsFingerprint)
        assertArrayEquals(Base64.getDecoder().decode(query.getValue("gateway_pk")), parsed.gatewayPublicKey)
        assertArrayEquals(Base64.getDecoder().decode(query.getValue("secret")), parsed.secret)
        assertEquals(query.getValue("name"), parsed.name)
        assertNull(parsed.profile)
        assertEquals(setOf("v", "candidates", "port", "tls_fp", "gateway_pk", "secret", "name"), query.keys)
    }

    @Test
    fun `the secret is the caller's to zero`() {
        val parsed = PairingLink.parse(link)
        parsed.secret.fill(0)
        assertArrayEquals(ByteArray(32), parsed.secret)
    }

    @Test
    fun `parsing zeroes every value it decoded, however it ends`() {
        val parsed = LinkedHashMap<String, ByteArray>()
        PairingLink.parse(link, parsed)
        assertEquals(REQUIRED_PARAMETERS.getValue(V1).toSet(), parsed.keys)
        parsed.forEach { (name, bytes) -> assertTrue(bytes.all { it == 0.toByte() }, name) }
        listOf(replacing("name", "%C3"), replacing("name", "%ZZ"), "$link&port=4131").forEach { broken ->
            val refused = LinkedHashMap<String, ByteArray>()
            assertThrows<ProtocolException>(broken) { PairingLink.parse(broken, refused) }
            assertTrue("secret" in refused, broken)
            refused.forEach { (name, bytes) -> assertTrue(bytes.all { it == 0.toByte() }, "$name in $broken") }
        }
    }

    @Test
    fun `an unknown parameter is ignored, and so is one whose name does not decode`() {
        assertEquals(4031, PairingLink.parse("$link&future=%ZZ&also&%ZZ=1&=2").port)
    }

    @Test
    fun `a version-1 link reads no profile, however one is written`() {
        listOf("&profile=dev", "&profile=%ZZ", "&profile=a&profile=b").forEach { extra ->
            assertNull(PairingLink.parse(link + extra).profile, extra)
        }
    }

    @Test
    fun `a parameter's name is form-decoded too`() {
        assertEquals(4031, PairingLink.parse(without("port") + "&p%6Frt=4031").port)
        assertEquals(
            "port",
            assertThrows<ProtocolException.RepeatedParameter> { PairingLink.parse("$link&p%6Frt=1") }.name,
        )
    }

    @Test
    fun `a fragment is no part of the query`() {
        assertEquals(query.getValue("name"), PairingLink.parse("$link#fragment").name)
    }

    @Test
    fun `a plus is a space, and an encoded plus a plus`() {
        assertEquals("Owner Mac", PairingLink.parse(replacing("name", "Owner+Mac")).name)
        assertEquals("a+b", PairingLink.parse(replacing("name", "a%2Bb")).name)
    }

    @Test
    fun `the name has no length bound, as the daemon's host name has none of 128 bytes`() {
        assertEquals(300, PairingLink.parse(replacing("name", "n".repeat(300))).name.length)
    }

    @Test
    fun `candidates nested past 32 levels are refused before they are parsed`() {
        val deep = replacing("candidates", "[".repeat(2_400))
        val refusal = onSmallStack { assertThrows<ProtocolException.MalformedParameter> { PairingLink.parse(deep) } }
        assertEquals("candidates", refusal.name)
    }

    @Test
    fun `a link that is not a pairing link is refused`() {
        assertThrows<ProtocolException.NotAPairingLink> {
            PairingLink.parse(
                link.replace("fermix://pair?", "https://pair?"),
            )
        }
    }

    @Test
    fun `each missing parameter is refused by name`() {
        listOf("candidates", "port", "tls_fp", "gateway_pk", "secret", "name").forEach { name ->
            check(without(name) != link) { "the vendored link has no $name to drop" }
            assertEquals(
                name,
                assertThrows<ProtocolException.MissingParameter> { PairingLink.parse(without(name)) }.name,
            )
        }
        assertEquals("v", assertThrows<ProtocolException.MissingParameter> { PairingLink.parse(without("v")) }.name)
    }

    @Test
    fun `a link of a newer format is refused with its version, before anything else is read`() {
        listOf("3" to 3, "10" to 10, "999999999" to 999_999_999).forEach { (written, version) ->
            val refusal =
                assertThrows<ProtocolException.NewerLinkVersion>(written) {
                    PairingLink.parse("fermix://pair?v=$written&secret=%ZZ")
                }
            assertEquals(version, refusal.version, written)
        }
        // Past nine digits a version is no number this parser reads.
        assertThrows<ProtocolException.MalformedParameter> { PairingLink.parse(replacing("v", "1000000000")) }
    }

    @Test
    fun `a parameter named twice is refused`() {
        assertEquals(
            "port",
            assertThrows<ProtocolException.RepeatedParameter> { PairingLink.parse("$link&port=4131") }.name,
        )
    }

    @Test
    fun `each malformed parameter is refused by name`() {
        val seventeen = List(17) { "\"10.0.0.$it\"" }.joinToString(",", "[", "]")
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(31) { 7 }).encodeForm()
        val cases =
            listOf(
                "v" to "0",
                "v" to "",
                "v" to "01",
                "v" to "%2B1",
                "v" to "002",
                "v" to "1.0",
                "candidates" to "not-json",
                "candidates" to "%7B%7D",
                "candidates" to "%5B1%5D",
                "candidates" to "%5B%22%22%5D",
                "candidates" to seventeen.encodeForm(),
                "candidates" to "%5B%22${"h".repeat(254)}%22%5D",
                "candidates" to "%5B%22a%5Cu0001b%22%5D",
                "candidates" to "%5B%22a%5Cu007Fb%22%5D",
                "port" to "0",
                "port" to "65536",
                "port" to "04031",
                "port" to "4031x",
                "tls_fp" to query.getValue("tls_fp").uppercase(),
                "tls_fp" to query.getValue("tls_fp").drop(2),
                "gateway_pk" to query.getValue("gateway_pk").encodeForm().replace("%3D", ""),
                "gateway_pk" to "AAAA",
                "gateway_pk" to "${"A".repeat(42)}B%3D",
                // 31 bytes: 44 characters, as 32 bytes are, ending in "==".
                "gateway_pk" to shortKey,
                "secret" to shortKey,
                "secret" to "${query.getValue("secret").dropLast(2)}*=",
                "secret" to "%2",
                // 42 characters: a length no key has.
                "secret" to "${"A".repeat(40)}%3D%3D",
                "secret" to "A".repeat(44),
                "secret" to "${"A".repeat(42)}B%3D",
                "name" to "",
                "name" to "%C3",
                "name" to "Owner's%20Macé",
                // A space only as +, and a byte past ASCII only as %XY, though both decode to valid UTF-8.
                "name" to "Owner Mac",
                "name" to "Ã©",
                "name" to "a%01b",
                "name" to "a%7Fb",
                "name" to "a%C2%85b",
                "name" to "+++",
            )
        cases.forEach { (name, value) ->
            val refusal =
                assertThrows<ProtocolException.MalformedParameter>(
                    "$name=$value",
                ) { PairingLink.parse(replacing(name, value)) }
            assertEquals(name, refusal.name, "$name=$value")
        }
    }

    @Test
    fun `the largest link a daemon builds parses`() {
        val hosts = List(16) { "\"${"h".repeat(253)}\"" }.joinToString(",", "[", "]")
        assertEquals(16, PairingLink.parse(replacing("candidates", hosts.encodeForm())).candidates.size)
    }

    private fun String.encodeForm() = java.net.URLEncoder.encode(this, Charsets.UTF_8)
}
