package io.tezra.fermix.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MEDIA_CHUNK = "media_chunk"

/**
 * Encodes one client event as a frame at protocol [v] (1 or 2) and [seq] (from 1), with [raw] as
 * its tail: `attach_chunk` always carries one, `pair_request` from protocol v2 on, and nothing
 * else does. The event is held to its version's rules before anything is written.
 */
fun encodeClientEvent(
    v: Int,
    seq: ULong,
    event: ClientEvent,
    raw: ByteArray = ByteArray(0),
): ByteArray = encodeEvent(CLIENT_BOOK, v, seq, event, raw)

/**
 * Decodes one server frame: a known event, held to the rules of the version it carries, or
 * [ServerEvent.Unknown] for a `t` this codec does not know at that version. An `event_part` comes
 * back as itself; [EventPartAssembler] joins a run.
 */
fun decodeServerEvent(bytes: ByteArray): Decoded<ServerEvent> {
    val frame = Frame.decode(bytes)
    val text = utf8(frame.header)
    return decodeServerHeader(parseObject(text), text, frame.raw)
}

/** Decodes one client frame as the daemon would: an unknown client `t` is refused, as there. */
internal fun decodeClientEvent(bytes: ByteArray): Decoded<ClientEvent> {
    val frame = Frame.decode(bytes)
    val header = parseObject(utf8(frame.header))
    val envelope = readEnvelope(header)
    val rule = CLIENT_BOOK.rules[envelope.t] ?: throw ProtocolException.UnknownEvent(envelope.t)
    refuseIf(envelope.v !in rule.versions) { ProtocolException.EventNotInVersion(envelope.t, envelope.v) }
    val event = decodeKnown(CLIENT_BOOK, rule, envelope, header, frame.raw)
    return Decoded(envelope.v, envelope.seq, event, frame.raw)
}

/** Encodes one server event as the daemon would. */
internal fun encodeServerEvent(
    v: Int,
    seq: ULong,
    event: ServerEvent.Known,
    raw: ByteArray = ByteArray(0),
): ByteArray = encodeEvent(SERVER_BOOK, v, seq, event, raw)

/**
 * Decodes the logical event of a whole `event_part` run: its JSON object, which carries `t` and
 * the event's fields but neither `v` nor `seq`, at the run's [v] and its first frame's [seq].
 */
internal fun decodeLogicalServerEvent(
    v: Int,
    seq: ULong,
    json: ByteArray,
): Decoded<ServerEvent> {
    val text = utf8(json)
    val logical = parseObject(text)
    val stray = logical.keys.firstOrNull { it == "v" || it == "seq" }
    if (stray != null) throw ProtocolException.InvalidEnvelope(stray, "is in a logical event, which has none")
    val t = (logical["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (t == EVENT_PART || t == MEDIA_CHUNK) throw ProtocolException.UnsplittableEvent(t)
    val stamps = envelopeJson(v, "", seq).filterKeys { it != "t" }
    return decodeServerHeader(JsonObject(logical + stamps), text, ByteArray(0))
}

private fun decodeServerHeader(
    header: JsonObject,
    text: String,
    raw: ByteArray,
): Decoded<ServerEvent> {
    val envelope = readEnvelope(header)
    val rule = SERVER_BOOK.rules[envelope.t]?.takeIf { envelope.v in it.versions }
    val event = rule?.let { decodeKnown(SERVER_BOOK, it, envelope, header, raw) }
    return Decoded(envelope.v, envelope.seq, event ?: ServerEvent.Unknown(envelope.t, envelope.seq, text), raw)
}

/**
 * Decodes a known event: the fields its version does not carry dropped as unknown; the shape of its
 * model, nulls refused; the fields its version requires; then its rules and its raw tail.
 */
private fun <E : Any> decodeKnown(
    book: RuleBook<E>,
    rule: EventRule<E>,
    envelope: Envelope,
    header: JsonObject,
    raw: ByteArray,
): E {
    val dropped = rule.versioned.filter { envelope.v !in it.carriedIn }.map { it.name }
    val payload = JsonObject(header.filterKeys { it != "v" && it != "seq" && it !in dropped })
    checkShape(rule.t, payload, rule.descriptor)
    requireVersionFields(rule, envelope.v, payload)
    val event =
        try {
            WIRE_JSON.decodeFromJsonElement(book.serializer, payload)
        } catch (refusal: SerializationException) {
            throw ProtocolException.InvalidField(
                rule.t,
                "header",
                "does not decode into its model",
                ParserRefusal(refusal),
            )
        }
    validate(rule, envelope.v, event, raw)
    return event
}

private fun <E : Any> encodeEvent(
    book: RuleBook<E>,
    v: Int,
    seq: ULong,
    event: E,
    raw: ByteArray,
): ByteArray {
    refuseIf(v !in VERSIONS) { ProtocolException.UnsupportedVersion(v.toLong()) }
    refuseIf(seq == 0uL) { ProtocolException.InvalidEnvelope("seq", "is 0, and counts from 1") }
    val encoded = WIRE_JSON.encodeToJsonElement(book.serializer, event).jsonObject
    val t = encoded.getValue("t").jsonPrimitive.content
    val rule = book.rules.getValue(t)
    refuseIf(v !in rule.versions) { ProtocolException.EventNotInVersion(t, v) }
    val payload = encoded.filterKeys { it != "t" }
    val stray = rule.versioned.firstOrNull { v !in it.carriedIn && it.name in payload }
    if (stray != null) throw ProtocolException.FieldNotInVersion(t, stray.name, v)
    requireVersionFields(rule, v, payload)
    validate(rule, v, event, raw)
    val header = JsonObject(envelopeJson(v, t, seq) + payload)
    return Frame(WIRE_JSON.encodeToString(JsonObject.serializer(), header).encodeToByteArray(), raw).encode()
}

private fun requireVersionFields(
    rule: EventRule<*>,
    v: Int,
    payload: Map<String, Any>,
) {
    val missing = rule.versioned.firstOrNull { v in it.requiredIn && it.name !in payload }
    if (missing != null) throw ProtocolException.MissingField(rule.t, missing.name)
}

private fun <E> validate(
    rule: EventRule<E>,
    v: Int,
    event: E,
    raw: ByteArray,
) {
    val carriesRaw = v in rule.rawIn
    refuseIf(carriesRaw && raw.isEmpty()) { ProtocolException.MissingRaw(rule.t) }
    refuseIf(!carriesRaw && raw.isNotEmpty()) { ProtocolException.UnexpectedRaw(rule.t, raw.size) }
    rule.check(FieldCheck(rule.t, v, raw.size), event)
}
