package io.tezra.fermix.demo

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Frame
import io.tezra.fermix.protocol.MAX_EVENT_BYTES
import io.tezra.fermix.protocol.MAX_EVENT_PARTS
import io.tezra.fermix.protocol.MAX_HEADER_BYTES
import io.tezra.fermix.protocol.MAX_RAW_BYTES
import io.tezra.fermix.protocol.MIN_EVENT_PARTS
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.decodeClientEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

// Frames as the daemon writes them and reads them, built from core-protocol's models: every event the daemon
// sends is a model instance, encoded with the wire's JSON settings (its `t` discriminator, no nulls), and the
// phone decodes it with core-protocol's own decoder, which holds it to the contract's rules. Only an event
// outside the catalogue is written as text. The phone's frames are read with core-protocol's client decoder, as
// the daemon reads them: an unknown `t`, a field the event's version does not allow or a value its rules refuse is
// a refusal, which the demo closes `1002` on (DemoConnection).

private val wireJson =
    Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/** The protocol version every frame of the demo carries: v2, the design's section 7. */
const val DAEMON_VERSION = 2

/** One client frame as the daemon reads it, with its raw tail: a `pair_request`'s chain or a chunk's bytes. */
class ClientFrame(
    val v: Int,
    val seq: ULong,
    val event: ClientEvent,
    val raw: ByteArray = ByteArray(0),
)

/** A server event's JSON object as the daemon writes it: `t` and its fields, no envelope. */
fun logicalEvent(event: ServerEvent.Known): JsonObject =
    wireJson.encodeToJsonElement(serializer<ServerEvent.Known>(), event).jsonObject

/** One server frame: the envelope's `v`, `t` and `seq`, then the event's fields. */
fun serverFrame(
    v: Int,
    seq: ULong,
    event: ServerEvent.Known,
    raw: ByteArray = ByteArray(0),
): ByteArray = Frame(headerOf(v, seq, event), raw).encode()

/** A server frame's JSON header, whatever its length: the envelope's `v`, `t` and `seq`, then the event's fields. */
private fun headerOf(
    v: Int,
    seq: ULong,
    event: ServerEvent.Known,
): ByteArray {
    val fields = logicalEvent(event)
    val envelope = mapOf("v" to JsonPrimitive(v), "t" to fields.getValue("t"), "seq" to JsonPrimitive(seq.toLong()))
    return JsonObject(envelope + fields.filterKeys { it != "t" }).toString().encodeToByteArray()
}

/** [event] as an `event_part` run of [parts] frames from [firstSeq] (PROTOCOL.md "Continuation frames"). */
fun serverRun(
    firstSeq: ULong,
    event: ServerEvent.Known,
    parts: Int,
): List<ByteArray> {
    val text = logicalEvent(event).toString().encodeToByteArray()
    val slice = (text.size + parts - 1) / parts
    return (0 until parts).map { index ->
        val tail = text.copyOfRange(index * slice, minOf((index + 1) * slice, text.size))
        serverFrame(DAEMON_VERSION, firstSeq + index.toULong(), ServerEvent.EventPart(index, parts), tail)
    }
}

/**
 * [event] as the daemon sends it from [firstSeq]: one frame while its header fits the 4,096 bytes a header
 * takes, and an `event_part` run of as few slices of at most 60 KiB as hold it otherwise, never fewer than two.
 * An event past the 1 MiB a run carries is the caller's to cut; it fails loud here.
 */
fun framesOf(
    firstSeq: ULong,
    event: ServerEvent.Known,
): List<ByteArray> {
    val header = headerOf(DAEMON_VERSION, firstSeq, event)
    if (header.size <= MAX_HEADER_BYTES) return listOf(Frame(header, ByteArray(0)).encode())
    val headerBytes = logicalEvent(event).toString().encodeToByteArray().size
    require(headerBytes <= MAX_EVENT_BYTES) { "a $headerBytes-byte event is past what a run carries" }
    val parts = maxOf(MIN_EVENT_PARTS, (headerBytes + MAX_RAW_BYTES - 1) / MAX_RAW_BYTES)
    check(parts <= MAX_EVENT_PARTS) { "a $headerBytes-byte event takes $parts parts" }
    return serverRun(firstSeq, event, parts)
}

/** A frame whose `t` the catalogue does not hold: a newer daemon's event. */
fun unknownFrame(
    seq: ULong,
    t: String,
): ByteArray = Frame("""{"v":2,"t":"$t","seq":$seq,"future":true}""".encodeToByteArray(), ByteArray(0)).encode()

/** The client frame in [bytes], as the daemon reads it; a frame the contract refuses throws ProtocolException. */
fun clientFrame(bytes: ByteArray): ClientFrame {
    val decoded = decodeClientEvent(bytes)
    return ClientFrame(decoded.v, decoded.seq, decoded.event, decoded.raw)
}
