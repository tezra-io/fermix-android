package io.tezra.fermix.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.CharacterCodingException
import java.util.Base64

/** What every pairing link starts with (the schema's `pairingLink` `x-uri-prefix`). */
const val PAIRING_LINK_PREFIX = "fermix://pair?"

/**
 * The parameters each link version requires and the only ones it reads; link version 2 adds
 * `profile` (design section 6.3, and section 7's `v` row: QR `v=2`, D11).
 */
internal val REQUIRED_PARAMETERS: Map<Int, List<String>> =
    listOf("v", "candidates", "port", "tls_fp", "gateway_pk", "secret", "name").let { v1 ->
        mapOf(V1 to v1, V2 to v1 + "profile")
    }

/**
 * The route's parameters, link version 2's alone, read only together (PROTOCOL.md "Pairing link"): a link
 * with one of them and not another is refused naming the first missing, in this order.
 */
internal val ROUTE_PARAMETERS = listOf("iroh", "relays", "relays_rev")

private val LINK_VERSIONS = mapOf("1" to V1, "2" to V2)

/** A port's largest value: the listener's TCP port, and a relay's QUIC port. */
internal const val MAX_PORT = 65_535
private const val BASE64_KEY_CHARS = 44
private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** 32 bytes fill 256 of the 258 bits of 43 base64 characters; canonical base64 leaves the last two 0. */
private const val UNUSED_KEY_BITS = 0b11

private val PORT = Regex("[1-9][0-9]{0,4}")
private val FINGERPRINT = Regex("[0-9a-f]{64}")

/** A link version as a plain decimal, at most nine digits so it is an Int; one past 2 is a newer format. */
private val DECIMAL_VERSION = Regex("[1-9][0-9]{0,8}")

/**
 * A daemon's pairing link, the owner's QR code (PROTOCOL.md "Pairing link"):
 * `fermix://pair?v=…&candidates=…&port=…&tls_fp=…&gateway_pk=…&secret=…&name=…`, and from link
 * version 2 `&profile=…` (design section 6.3 and section 7's `v` row). [version] is handed to the
 * caller, who refuses a version-1 link at scan (design D1). [name] and [profile] are shown to the
 * owner, so neither is blank or holds a control character; the contract bounds neither's length, and
 * the daemon's name is its host name, which can pass 128 bytes.
 *
 * [secret] is the one-time pairing PSK, held in a byte array the caller zeroes once the handshake
 * no longer needs it. Its 32 bytes never sit in a string, and every array parsing decodes its base64
 * text into is zeroed however parsing ends; it is decoded last, so no refusal leaves a decoded copy
 * behind. Its base64 text, like the rest of the link, does pass through transient strings as the query
 * is split, and those cannot be zeroed: a JVM string is immutable, and the QR decoder or the clipboard
 * that produced the link made copies of its own, so they live until the collector reclaims them. The
 * 120-second window and the single use bound that exposure.
 */
class PairingLink private constructor(
    val version: Int,
    values: Map<String, ByteArray>,
) {
    // Each property is read from its parameter in the order declared, which is the order of the
    // refusals; the secret comes last (see above).
    val candidates: List<String> = candidates(values.getValue("candidates"))
    val port: Int = port(values.getValue("port"))
    val tlsFingerprint: ByteArray = fingerprint(values.getValue("tls_fp"))
    val gatewayPublicKey: ByteArray = key("gateway_pk", values.getValue("gateway_pk"))
    val name: String = label("name", values.getValue("name"))

    /** The profile the link pairs into, from link version 2; a version-1 link has none. */
    val profile: String? = values["profile"]?.let { label("profile", it) }

    /**
     * The daemon's iroh route, from link version 2's `iroh`, `relays` and `relays_rev`, each relay
     * `https://<host>/` with QUIC address discovery on its port; none on a link without them, which pairs over
     * its hosts alone (PROTOCOL.md "Pairing link" and "Remote reach").
     */
    val route: Candidate.Iroh? =
        values["iroh"]?.let { iroh ->
            val relays = values.getValue("relays")
            linkRoute(text("iroh", iroh), text("relays", relays), text("relays_rev", values.getValue("relays_rev")))
        }
    val secret: ByteArray = key("secret", values.getValue("secret"))

    companion object {
        /**
         * Reads a link with a form decoder: a space is `+`, and a `+`, `/` or `=` of base64 arrives
         * percent-encoded. `v` is read first, then only that version's parameters: any other is
         * ignored, and a missing, repeated or malformed one of them is refused by name.
         */
        fun parse(link: String): PairingLink = parse(link, LinkedHashMap())

        /** [parse], decoding into [values], which it zeroes however it ends; a test hands in its own. */
        internal fun parse(
            link: String,
            values: MutableMap<String, ByteArray>,
        ): PairingLink {
            refuseIf(!link.startsWith(PAIRING_LINK_PREFIX)) { ProtocolException.NotAPairingLink() }
            // A fragment is no part of a URI's query (RFC 3986).
            val query = link.substring(PAIRING_LINK_PREFIX.length).substringBefore('#')
            try {
                readForm(query, listOf("v"), values)
                val version = linkVersion(values)
                val names = REQUIRED_PARAMETERS.getValue(version)
                readForm(query, names - "v", values)
                val missing = names.firstOrNull { it !in values }
                if (missing != null) throw ProtocolException.MissingParameter(missing)
                if (version == V2) readRoute(query, values)
                return PairingLink(version, values)
            } finally {
                values.values.forEach { it.fill(0) }
            }
        }
    }
}

/** Reads the route's parameters into [values]: all three, or none, else the first missing is refused. */
private fun readRoute(
    query: String,
    values: MutableMap<String, ByteArray>,
) {
    readForm(query, ROUTE_PARAMETERS, values)
    if (ROUTE_PARAMETERS.none { it in values }) return
    val missing = ROUTE_PARAMETERS.firstOrNull { it !in values }
    if (missing != null) throw ProtocolException.MissingParameter(missing)
}

/**
 * The link's version, written exactly `1` or `2`: the schema's `pairingLink.v` is the string "1". A plain
 * decimal past 2 is a newer format's, refused as such so the scan can say which side to update.
 */
private fun linkVersion(values: Map<String, ByteArray>): Int {
    val text = values["v"]?.let { text("v", it) } ?: throw ProtocolException.MissingParameter("v")
    LINK_VERSIONS[text]?.let { return it }
    refuseIf(DECIMAL_VERSION.matches(text)) { ProtocolException.NewerLinkVersion(text.toInt()) }
    throw ProtocolException.MalformedParameter("v", "is '$text', not 1 or 2")
}

private fun candidates(bytes: ByteArray): List<String> {
    val text = text("candidates", bytes)
    val early = refusalBeforeParsing(text, MAX_JSON_DEPTH)
    refuseIf(early != null) { ProtocolException.MalformedParameter("candidates", "$early") }
    val element =
        try {
            WIRE_JSON.parseToJsonElement(text)
        } catch (refusal: SerializationException) {
            throw ProtocolException.MalformedParameter("candidates", "is not JSON", refusal)
        }
    val hosts =
        (element as? JsonArray)?.map(::host)
            ?: throw ProtocolException.MalformedParameter("candidates", "is not a JSON array")
    refuseIf(hosts.size > MAX_CANDIDATES) {
        ProtocolException.MalformedParameter("candidates", "has ${hosts.size} hosts, past $MAX_CANDIDATES")
    }
    return hosts
}

/** One candidate: a string of 1 to 253 bytes without a control character. */
private fun host(element: JsonElement): String {
    val host =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ProtocolException.MalformedParameter("candidates", "holds a value that is not a string")
    val size = host.encodeToByteArray().size
    refuseIf(size !in 1..MAX_HOST_BYTES) {
        ProtocolException.MalformedParameter(
            "candidates",
            "holds a host of $size bytes, outside 1 to $MAX_HOST_BYTES",
        )
    }
    refuseIf(host.any(::isControl)) {
        ProtocolException.MalformedParameter("candidates", "holds a host with a C0, C1 or DEL control character")
    }
    return host
}

private fun port(bytes: ByteArray): Int {
    val text = text("port", bytes)
    val port = text.takeIf(PORT::matches)?.toInt()?.takeIf { it <= MAX_PORT }
    return port ?: throw ProtocolException.MalformedParameter("port", "is '$text', not a TCP port")
}

private fun fingerprint(bytes: ByteArray): ByteArray {
    val text = text("tls_fp", bytes)
    refuseIf(!FINGERPRINT.matches(text)) {
        ProtocolException.MalformedParameter("tls_fp", "is not 64 lowercase hex digits")
    }
    return text.hexToByteArray()
}

/**
 * 32 bytes in canonical standard base64 with padding, read from the bytes themselves so no string
 * holds them. Canonical, so that one key has one link: the decoder alone would also take a last
 * character whose two unused bits are set.
 */
private fun key(
    name: String,
    bytes: ByteArray,
): ByteArray {
    val shaped =
        bytes.size == BASE64_KEY_CHARS &&
            bytes.last() == '='.code.toByte() &&
            (0 until BASE64_KEY_CHARS - 1).all { sextet(bytes[it]) >= 0 }
    refuseIf(!shaped) {
        ProtocolException.MalformedParameter(name, "is not 32 bytes in standard base64 with padding")
    }
    refuseIf((sextet(bytes[BASE64_KEY_CHARS - 2]) and UNUSED_KEY_BITS) != 0) {
        ProtocolException.MalformedParameter(
            name,
            "is not canonical base64: its last character sets bits past the 32 bytes",
        )
    }
    return Base64.getDecoder().decode(bytes)
}

/** The six bits a base64 character stands for, or -1 for a byte outside the alphabet. */
private fun sextet(byte: Byte): Int = BASE64_ALPHABET.indexOf(byte.toInt().toChar())

/** Text the phone shows the owner: not blank and without a C0, C1 or DEL control character. */
private fun label(
    name: String,
    bytes: ByteArray,
): String {
    val text = text(name, bytes)
    refuseIf(text.isBlank()) { ProtocolException.MalformedParameter(name, "is blank") }
    refuseIf(text.any(::isControl)) {
        ProtocolException.MalformedParameter(name, "holds a C0, C1 or DEL control character")
    }
    return text
}

private fun text(
    name: String,
    bytes: ByteArray,
): String {
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (refusal: CharacterCodingException) {
            throw ProtocolException.MalformedParameter(name, "is not UTF-8", refusal)
        }
    refuseIf(text.isEmpty()) { ProtocolException.MalformedParameter(name, "is empty") }
    return text
}
