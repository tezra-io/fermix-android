package io.tezra.fermix.session

import io.tezra.fermix.protocol.CandidateScope
import io.tezra.fermix.protocol.Caps
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Frame
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.Profile
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import io.tezra.fermix.protocol.Candidate as WireCandidate

// Frames as the daemon writes them and reads them, built from core-protocol's models: every event a
// test sends is a model instance, encoded with the wire's JSON settings (its `t` discriminator, no
// nulls), and the session decodes it with core-protocol's own decoder, which holds it to the
// contract's rules. Only an event outside the catalogue is written as text.

private val wireJson =
    Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = false
    }

internal const val PROFILE = "main"
internal const val TS = "2026-10-01T09:00:00Z"

/** A paired session's acknowledgement at protocol v2: one profile, one tailnet candidate, empty cursors. */
internal val HELLO_ACK =
    ServerEvent.HelloAck(
        sessionId = "mobile-session-1",
        minVersion = 2,
        maxVersion = 2,
        profiles = listOf(Profile(PROFILE, "Fermix")),
        candidates = listOf(WireCandidate("100.101.102.103", "utun4", CandidateScope.TAILNET)),
        historyHeadSeq = 0uL,
        readUpToSeq = 0uL,
        caps = Caps(commands = emptyList(), maxMediaBytes = 20_971_520),
        mutationHeadSeq = 0uL,
    )

/** One client frame as the daemon reads it. */
internal class ClientFrame(
    val v: Int,
    val seq: ULong,
    val event: ClientEvent,
)

/** A server event's JSON object as the daemon writes it: `t` and its fields, no envelope. */
internal fun logicalEvent(event: ServerEvent.Known): JsonObject =
    wireJson.encodeToJsonElement(serializer<ServerEvent.Known>(), event).jsonObject

/** One server frame: the envelope's `v`, `t` and `seq`, then the event's fields. */
internal fun serverFrame(
    v: Int,
    seq: ULong,
    event: ServerEvent.Known,
    raw: ByteArray = ByteArray(0),
): ByteArray {
    val fields = logicalEvent(event)
    val envelope = mapOf("v" to JsonPrimitive(v), "t" to fields.getValue("t"), "seq" to JsonPrimitive(seq.toLong()))
    val header = JsonObject(envelope + fields.filterKeys { it != "t" })
    return Frame(header.toString().encodeToByteArray(), raw).encode()
}

/** [event] as an `event_part` run of [parts] frames from [firstSeq] (PROTOCOL.md "Continuation frames"). */
internal fun serverRun(
    firstSeq: ULong,
    event: ServerEvent.Known,
    parts: Int,
): List<ByteArray> {
    val text = logicalEvent(event).toString().encodeToByteArray()
    val slice = (text.size + parts - 1) / parts
    return (0 until parts).map { index ->
        val tail = text.copyOfRange(index * slice, minOf((index + 1) * slice, text.size))
        serverFrame(2, firstSeq + index.toULong(), ServerEvent.EventPart(index, parts), tail)
    }
}

/** A frame whose `t` the catalogue does not hold: a newer daemon's event. */
internal fun unknownFrame(
    seq: ULong,
    t: String,
): ByteArray = Frame("""{"v":2,"t":"$t","seq":$seq,"future":true}""".encodeToByteArray(), ByteArray(0)).encode()

internal fun clientFrame(bytes: ByteArray): ClientFrame {
    val header = wireJson.parseToJsonElement(Frame.decode(bytes).header.decodeToString()).jsonObject
    val event = wireJson.decodeFromJsonElement(serializer<ClientEvent>(), JsonObject(header - "v" - "seq"))
    val seq =
        header
            .getValue("seq")
            .jsonPrimitive.content
            .toULong()
    return ClientFrame(header.getValue("v").jsonPrimitive.int, seq, event)
}

internal fun message(
    seq: ULong,
    role: String = "assistant",
    clientMsgId: String? = null,
): HistoryMessage = HistoryMessage(seq, role, "Row $seq", TS, emptyList(), clientMsgId = clientMsgId)

internal fun row(
    seq: ULong,
    role: String = "assistant",
    clientMsgId: String? = null,
): ServerEvent.Row = ServerEvent.Row(PROFILE, seq, role, "Row $seq", TS, emptyList(), clientMsgId = clientMsgId)

internal fun page(
    messages: List<HistoryMessage>,
    nextAfterSeq: ULong,
    head: ULong,
    prevBeforeSeq: ULong? = null,
): ServerEvent.HistoryPage = ServerEvent.HistoryPage(PROFILE, messages, nextAfterSeq, head, prevBeforeSeq)

internal fun msg(
    clientMsgId: String,
    text: String = "Hello",
    retryOf: String? = null,
): ClientEvent.Msg = ClientEvent.Msg(clientMsgId, PROFILE, text, emptyList(), retryOf)
