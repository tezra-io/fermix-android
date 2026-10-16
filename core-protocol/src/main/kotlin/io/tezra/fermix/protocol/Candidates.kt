package io.tezra.fermix.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/** The key that tells a candidate's kind; a host has none (PROTOCOL.md "Server events"). */
internal const val CANDIDATE_KIND = "kind"

/** The one kind this app dials besides a host: the iroh route (PROTOCOL.md "Remote reach"). */
internal const val IROH_KIND = "iroh"

/** The candidate union's serial name, which the shape check (ShapeCheck.kt) reads it by. */
internal const val CANDIDATE_SERIAL_NAME = "io.tezra.fermix.protocol.Candidate"

/** One relay of a pairing link's `relays`: `host:quic_port`, the port a plain decimal (the schema's pattern). */
private val LINK_RELAY = Regex("($RELAY_HOST_PATTERN):([1-9][0-9]{0,4})")

/** Which of the union a candidate's fields are: by `kind` alone, a host without one. */
internal enum class CandidateForm { HOST, IROH, SKIPPED }

internal fun candidateForm(fields: JsonObject): CandidateForm {
    val kind = fields[CANDIDATE_KIND] ?: return CandidateForm.HOST
    val iroh = kind is JsonPrimitive && kind.isString && kind.content == IROH_KIND
    return if (iroh) CandidateForm.IROH else CandidateForm.SKIPPED
}

/**
 * The model a candidate's fields are held to (ShapeCheck.kt): a host's or the route's, and none for a kind this app
 * does not know, which is skipped whatever it holds (PROTOCOL.md "Server events").
 */
internal fun candidateModel(fields: JsonObject): SerialDescriptor? =
    when (candidateForm(fields)) {
        CandidateForm.HOST -> serializer<Candidate.Host>().descriptor
        CandidateForm.IROH -> serializer<Candidate.Iroh>().descriptor
        CandidateForm.SKIPPED -> null
    }

/**
 * The route a pairing link writes in its `iroh`, `relays` and `relays_rev` (PROTOCOL.md "Pairing link"), each
 * parameter's text: the node id, one to eight relays as `host:quic_port` joined by `,`, each the relay
 * `https://<host>/` with QUIC address discovery on its port, and the list's revision. Each is refused by name.
 */
internal fun linkRoute(
    nodeId: String,
    relays: String,
    revision: String,
): Candidate.Iroh {
    refuseIf(!NODE_ID.matches(nodeId)) {
        ProtocolException.MalformedParameter("iroh", "is not 64 lowercase hex digits")
    }
    val entries = relays.split(',')
    refuseIf(entries.size > MAX_ROUTE_RELAYS) {
        ProtocolException.MalformedParameter("relays", "names ${entries.size} relays, past $MAX_ROUTE_RELAYS")
    }
    val relayList = entries.map(::linkRelay)
    refuseIf(!RELAY_LIST_REV.matches(revision)) {
        ProtocolException.MalformedParameter("relays_rev", "is not 16 lowercase hex digits")
    }
    return Candidate.Iroh(nodeId, relayList, revision)
}

/** One `host:quic_port` of a link's `relays`, its port 1 to 65,535. */
private fun linkRelay(entry: String): Relay {
    val match = LINK_RELAY.matchEntire(entry)
    val port =
        match
            ?.groupValues
            ?.last()
            ?.toInt()
            ?.takeIf { it <= MAX_PORT }
    if (match == null || port == null) {
        throw ProtocolException.MalformedParameter("relays", "holds '$entry', not host:quic_port")
    }
    return Relay("https://${match.groupValues[1]}/", port)
}

/**
 * The candidate union on the wire, JSON alone: a host as its own fields, the iroh route with `kind` first and
 * then its fields, and a skipped kind as it came. The shape check has passed the header before it decodes, so
 * what reaches here is an object whose host or route fields are of their types.
 */
internal object CandidateSerializer : KSerializer<Candidate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(CANDIDATE_SERIAL_NAME)

    override fun deserialize(decoder: Decoder): Candidate {
        val input = decoder as? JsonDecoder ?: throw SerializationException("a candidate is read from JSON alone")
        val fields =
            input.decodeJsonElement() as? JsonObject ?: throw SerializationException("a candidate is not an object")
        return when (candidateForm(fields)) {
            CandidateForm.HOST -> {
                input.json.decodeFromJsonElement(serializer<Candidate.Host>(), fields)
            }

            CandidateForm.IROH -> {
                input.json.decodeFromJsonElement(serializer<Candidate.Iroh>(), JsonObject(fields - CANDIDATE_KIND))
            }

            CandidateForm.SKIPPED -> {
                Candidate.Skipped(fields)
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Candidate,
    ) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("a candidate is written as JSON alone")
        val element =
            when (value) {
                is Candidate.Host -> {
                    output.json.encodeToJsonElement(serializer<Candidate.Host>(), value)
                }

                is Candidate.Iroh -> {
                    val fields = output.json.encodeToJsonElement(serializer<Candidate.Iroh>(), value).jsonObject
                    JsonObject(mapOf(CANDIDATE_KIND to JsonPrimitive(IROH_KIND)) + fields)
                }

                is Candidate.Skipped -> {
                    value.fields
                }
            }
        output.encodeJsonElement(element)
    }
}
