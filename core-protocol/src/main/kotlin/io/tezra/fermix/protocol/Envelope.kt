package io.tezra.fermix.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import java.nio.charset.CharacterCodingException

/**
 * How deep a header, or a run's logical event, may nest. The deepest field the protocol defines is
 * five levels down (`history_page.messages[].metadata.reaction.emoji`); free-form metadata and an
 * unknown event could nest without end, and the parser recurses once per level, so text past this
 * bound is refused before it is parsed. The bound is this codec's own: the contract sets none.
 */
internal const val MAX_JSON_DEPTH = 32

/**
 * The wire's JSON: `t` names the event, a field this codec does not know is ignored, and an absent
 * optional field is never written as null.
 */
internal val WIRE_JSON =
    Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/** A header's `v`, `t` and `seq`, as read and range-checked. */
internal class Envelope(
    val v: Int,
    val t: String,
    val seq: ULong,
)

/**
 * The envelope as written, so its fields come first and in their order:
 * `{"v":1,"t":"hello","seq":1,...}`.
 */
@Serializable
private class EnvelopeFields(
    @SerialName("v") val v: Int,
    @SerialName("t") val t: String,
    @SerialName("seq") val seq: ULong,
)

/** Reads the envelope: `v` is 1 or 2, `t` a non-empty string, `seq` an integer from 1 to 2^64 - 1. */
internal fun readEnvelope(header: JsonObject): Envelope =
    Envelope(readVersion(header), readName(header), readSeq(header))

internal fun envelopeJson(
    v: Int,
    t: String,
    seq: ULong,
): JsonObject = WIRE_JSON.encodeToJsonElement(serializer<EnvelopeFields>(), EnvelopeFields(v, t, seq)).jsonObject

/** A header's text: strict UTF-8, so a malformed byte is refused rather than replaced. */
internal fun utf8(bytes: ByteArray): String =
    try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (refusal: CharacterCodingException) {
        throw ProtocolException.MalformedHeader("it is not valid UTF-8", refusal)
    }

/**
 * Parses a header, or a run's logical event, as one JSON object. The parser is looser than RFC 8259
 * in two ways this codec closes: it takes a raw control character (below U+0020) inside a string,
 * which RFC 8259 has escaped, and a bare word or a number with a sign or a leading zero as a value.
 * So the text is scanned for the first before it is parsed (see [refusalBeforeParsing]), and every
 * bare value is held to JSON's grammar after. The parser also recurses once per level of nesting, so
 * the same scan reads the depth off the text first. A key named twice takes its last value, as the
 * parser reads it; the daemon never writes one. No refusal quotes the text, which may carry an
 * approval's token.
 *
 * The parse holds every value as an object on the heap, tens of bytes apiece, so a text of many small
 * values costs far more heap than its length: a 1 MiB logical event of `[0,0,…]` takes tens of MiB
 * while it is decoded, and a row keeps its metadata's tree as long as the row is held. The contract
 * bounds an event's bytes and not its values, and this codec adds no bound of its own; core-session
 * budgets heap for it.
 */
internal fun parseObject(text: String): JsonObject {
    val early = refusalBeforeParsing(text, MAX_JSON_DEPTH)
    refuseIf(early != null) { ProtocolException.MalformedHeader("it $early") }
    val element =
        try {
            WIRE_JSON.parseToJsonElement(text)
        } catch (refusal: SerializationException) {
            throw ProtocolException.MalformedHeader("it is not JSON", ParserRefusal(refusal))
        }
    refuseLooseValues(element, 0)
    return element as? JsonObject ?: throw ProtocolException.MalformedHeader("it is not an object")
}

/**
 * Why [text] is refused before it is parsed, or null when it is not: it opens more than [maxDepth]
 * arrays and objects inside each other, counted outside string literals, or it holds a raw control
 * character inside one. One pass over the text, which stops at the first refusal, so it is bounded by
 * the text's length.
 */
internal fun refusalBeforeParsing(
    text: String,
    maxDepth: Int,
): String? {
    val scan = TextScan(maxDepth)
    text.any(scan::read)
    return scan.refusal
}

/** [refusalBeforeParsing]'s pass: how deep it is, whether it is inside a string, and why it stopped. */
private class TextScan(
    private val maxDepth: Int,
) {
    private var depth = 0
    private var inString = false
    private var escaped = false
    var refusal: String? = null
        private set

    /** Reads one character; true once the text is refused. */
    fun read(char: Char): Boolean {
        if (inString && char < ' ') refusal = "holds a raw control character inside a string"
        when {
            escaped -> escaped = false
            inString && char == '\\' -> escaped = true
            inString -> inString = char != '"'
            char == '"' -> inString = true
            char == '[' || char == '{' -> depth++
            char == ']' || char == '}' -> depth--
        }
        if (depth > maxDepth) refusal = "nests deeper than $maxDepth levels"
        return refusal != null
    }
}

/** RFC 8259's bare values: a number without a sign or a leading zero, true, false and null. */
private val BARE_VALUE = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?|true|false|null")

/** Refuses a bare value outside RFC 8259's grammar anywhere in [element], an unknown field's included. */
private fun refuseLooseValues(
    element: JsonElement,
    depth: Int,
) {
    check(depth <= MAX_JSON_DEPTH) { "a value $depth levels down got past the nesting scan" }
    when (element) {
        is JsonObject -> {
            element.values.forEach { refuseLooseValues(it, depth + 1) }
        }

        is JsonArray -> {
            element.forEach { refuseLooseValues(it, depth + 1) }
        }

        is JsonPrimitive -> {
            refuseIf(!element.isString && !BARE_VALUE.matches(element.content)) {
                ProtocolException.MalformedHeader("it holds a bare value that is not JSON")
            }
        }
    }
}

private fun readVersion(header: JsonObject): Int {
    val version =
        envelopeField(header, "v").takeUnless { it.isString }?.content?.toLongOrNull()
            ?: throw ProtocolException.InvalidEnvelope("v", "is not an integer")
    refuseIf(version < V1 || version > V2) { ProtocolException.UnsupportedVersion(version) }
    return version.toInt()
}

private fun readName(header: JsonObject): String =
    envelopeField(header, "t").takeIf { it.isString && it.content.isNotEmpty() }?.content
        ?: throw ProtocolException.InvalidEnvelope("t", "is not a non-empty string")

private fun readSeq(header: JsonObject): ULong =
    envelopeField(header, "seq")
        .takeUnless { it.isString }
        ?.content
        ?.toULongOrNull()
        ?.takeIf { it >= 1uL }
        ?: throw ProtocolException.InvalidEnvelope("seq", "is not an integer from 1 to 2^64 - 1")

private fun envelopeField(
    header: JsonObject,
    field: String,
): JsonPrimitive =
    header[field] as? JsonPrimitive
        ?: throw ProtocolException.InvalidEnvelope(field, if (field in header) "is not a JSON value" else "is absent")
