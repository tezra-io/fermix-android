package io.tezra.fermix.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

private const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val RELAY = """{"url":"https://us1.relay.fermix.ai/","quic_port":7842}"""
private const val HOST = """{"host":"192.168.1.8","interface":"en0","scope":"lan"}"""
private const val CARD =
    """{"url":"https://example.com","site":"Example","title":"Example page","description":"A page",""" +
        """"image_ref":"$SHA"}"""
private const val LOCATION = """{"latitude":40.7233,"longitude":-74.003}"""
private const val RATING = """{"value":4.6,"best":5,"count":321}"""
private const val HOURS = """{"current_day":"Thursday 07:00-19:00"}"""
private const val DISTANCE = """{"value":0.4,"units":"km"}"""
private const val PLACE =
    """{"name":"Example Coffee","canonical_url":"https://example.coffee/",""" +
        """"source_url":"https://provider.example/place/1","location":$LOCATION,"address":"12 Example St",""" +
        """"rating":$RATING,"price_range":"$$","categories":["coffee_shop"],"opening_hours":$HOURS,""" +
        """"distance":$DISTANCE}"""
private const val PLACES = """{"type":"places","places":[$PLACE]}"""

/** A whole instance of each nested definition, every optional field present. */
private val SAMPLES: Map<String, String> =
    mapOf(
        "profile" to """{"id":"main","name":"Fermix"}""",
        "candidate" to HOST,
        "hostCandidate" to HOST,
        "irohCandidate" to
            """{"kind":"iroh","node_id":"${"7".repeat(64)}","relays":[$RELAY],"relays_rev":"374b4c8f103627f7"}""",
        "relay" to RELAY,
        // The cert lengths of client_binary_frames.jsonl's pair_request, whose tail the plant keeps.
        "attestation" to """{"kind":"android_keymint","cert_lengths":[16,12,4]}""",
        "requestOutcome" to
            """{"client_msg_id":"c","status":"failed","turn_id":"t","result_server_seq":1,"error":"cancelled"}""",
        "caps" to """{"commands":[],"media":true,"streaming":true,"turn_done":true,"max_media_bytes":1}""",
        "commandDescriptor" to """{"name":"help","aliases":["h"],"description":"Lists the commands"}""",
        "mediaRef" to
            """{"ref":"r","sha256":"$SHA","kind":"image","mime":"image/jpeg","size_bytes":1,"filename":"a.jpg",""" +
            """"caption":"A photo"}""",
        "historyMessage" to
            """{"server_seq":13,"role":"user","content":"Hi","kind":"text","ts":"2026-09-27T09:00:00.000000Z",""" +
            """"media_refs":[],"client_msg_id":"c","in_reply_to":"r","metadata":{},"link_previews":[$CARD],""" +
            """"blocks":[$PLACES],"truncated":true}""",
        "linkPreviewCard" to CARD,
        "replyBlock" to PLACES,
        "placesBlock" to PLACES,
        "place" to PLACE,
        "place.location" to LOCATION,
        "place.rating" to RATING,
        "place.opening_hours" to HOURS,
        "place.distance" to DISTANCE,
    )

private val ENVELOPE = setOf("v", "t", "seq")

/**
 * The unions (CandidateSerializer, ReplyBlockSerializer): definitions with no fields of their own, each planted as
 * its alternatives, the candidate's its `oneOf` and a reply block's its `allOf`'s `then`. An alternative's tag, the
 * route's `kind` and a places block's `type`, is the union's, which a value past its `const` makes a kind or a type
 * this app skips, never refuses (PROTOCOL.md "Server events", the companion PROTOCOL.md "Blocks under a reply"), so
 * it is no bound to plant.
 */
private val UNIONS = setOf("candidate", "replyBlock")
private val UNION_TAGS = mapOf("irohCandidate" to "kind", "placesBlock" to "type")

/**
 * Every bound protocol.schema.json states on a field, planted as a violation into a valid frame of its
 * event and refused under the field's path: minLength, maxLength and x-max-bytes, minimum, maximum,
 * pattern, minItems, maxItems, const, enum, and an array's items. Each frame is the fullest fixture line
 * of its event at the schema's version, with its own tail, a nested definition planted, wherever an event
 * holds it, in a whole sample of its own; a server event is decoded as a run's logical event when it takes
 * no tail, so no bound is out of reach of the 4 KiB header. PROTOCOL.md's rules that the schema does not
 * state are in ValidationTest, and design section 7's bounds beyond the export in ProvisionalV2Test.
 */
class SchemaBoundsTest {
    private val schema = Json.parseToJsonElement(vendored("protocol.schema.json")).jsonObject
    private val defs = schema.getValue("\$defs").jsonObject
    private val version =
        schema
            .getValue("x-protocol-version")
            .jsonPrimitive.content
            .toInt()

    /** A definition by name, or an object one holds in place by `definition.field` (a place's location). */
    private fun def(name: String): JsonObject {
        if ('.' !in name) return defs.getValue(name).jsonObject
        val owner = def(name.substringBefore('.'))
        return owner
            .getValue("properties")
            .jsonObject
            .getValue(name.substringAfter('.'))
            .jsonObject
    }

    private fun catalogue(direction: String) =
        def(direction)
            .getValue("properties")
            .jsonObject
            .getValue("t")
            .jsonObject
            .getValue("enum")
            .jsonArray
            .map { it.jsonPrimitive.content }

    /** One frame a bound is planted in: its event, header and tail, and, once planted, the field refused. */
    private inner class Plant(
        val client: Boolean,
        val t: String,
        val header: JsonObject,
        val field: String,
        val bound: String,
        val tail: ByteArray,
    ) {
        private val raw = def(t)["x-raw-bytes"]?.jsonPrimitive?.boolean == true

        fun decode() {
            when {
                client -> {
                    decodeClientEvent(frameOf("$header", tail))
                }

                raw -> {
                    decodeServerEvent(frameOf("$header", tail))
                }

                else -> {
                    decodeLogicalServerEvent(
                        version,
                        1uL,
                        "${JsonObject(header - "v" - "seq")}".encodeToByteArray(),
                    )
                }
            }
        }

        /** This frame with [value] at [path], refused for [bound] under [refusedAt]. */
        fun with(
            path: String,
            value: JsonElement,
            bound: String,
            refusedAt: String = path,
        ) = Plant(client, t, planted(header, path, value), refusedAt, bound, tail)

        override fun toString() = "${if (client) "client" else "server"} $t: $field $bound"
    }

    /** The fullest fixture line of each event of one direction, by `t`. */
    private fun fullest(
        client: Boolean,
        t: String,
    ): Plant {
        val (lines, binary) =
            if (client) {
                "fixtures/client_events.jsonl" to "fixtures/client_binary_frames.jsonl"
            } else {
                "fixtures/server_events.jsonl" to "fixtures/server_binary_frames.jsonl"
            }
        val frames =
            headerLines(lines).map { it.json to ByteArray(0) } + binaryLines(binary).map { it.header to it.raw }
        val (header, tail) =
            checkNotNull(
                frames
                    .filter {
                        it.first
                            .getValue("t")
                            .jsonPrimitive.content == t
                    }.maxByOrNull { it.first.size },
            ) {
                "no fixture carries the ${if (client) "client" else "server"} event $t"
            }
        return Plant(client, t, header, "", "", tail)
    }

    /** Every frame a bound is planted in, before it is planted. */
    private fun hosts(): List<Pair<Plant, JsonObject>> {
        val events =
            catalogue("clientEvent").map { fullest(true, it) to def(it) } +
                catalogue("serverEvent").map { fullest(false, it) to def(it) }
        val nested = nestings().map { nesting -> planted(nesting) to fieldsOf(nesting.name) }
        return events + nested
    }

    /** The fullest frame of [nesting]'s event, the sample of each definition that holds it planted first. */
    private fun planted(nesting: Nesting): Plant {
        val chain = generateSequence(nesting) { it.outer }.toList().asReversed()
        return chain.fold(fullest(nesting.client, nesting.t)) { frame, level ->
            frame.with(level.path, Json.parseToJsonElement(SAMPLES.getValue(level.name)), "")
        }
    }

    /** A nested definition, the event that holds it and the path of its first instance there. */
    private data class Nesting(
        val name: String,
        val client: Boolean,
        val t: String,
        val path: String,
        val outer: Nesting? = null,
    )

    /**
     * Each place an event holds a nested definition, or an object a definition holds in place, read from the schema,
     * each union as each of its alternatives. Four levels down (a page's message's places block's place's location)
     * is as deep as the schema nests.
     */
    private fun nestings(): List<Nesting> {
        val direct =
            listOf(true to "clientEvent", false to "serverEvent").flatMap { (client, direction) ->
                catalogue(direction).flatMap { t ->
                    references(t, "").map { (name, path) -> Nesting(name, client, t, path) }
                }
            }
        var level = expand(direct)
        val all = level.toMutableList()
        repeat(3) {
            level =
                expand(
                    level.flatMap { outer ->
                        references(outer.name, "${outer.path}.").map { (name, path) ->
                            outer.copy(name = name, path = path, outer = outer)
                        }
                    },
                )
            all += level
        }
        check(level.all { references(it.name, "").isEmpty() }) { "a definition nests five deep; plant it too" }
        return all
    }

    /** A union's place as each of its alternatives, every other nesting as it is. */
    private fun expand(nestings: List<Nesting>): List<Nesting> =
        nestings.flatMap { nesting ->
            if (nesting.name !in UNIONS) listOf(nesting) else alternatives(nesting.name).map { nesting.copy(name = it) }
        }

    /** A union's alternatives: the candidate's `oneOf`, a reply block's `allOf` entries' `then`. */
    private fun alternatives(union: String): List<String> {
        val oneOf = def(union)["oneOf"]?.jsonArray?.map { it.jsonObject }
        val then = def(union)["allOf"]?.jsonArray?.map { it.jsonObject.getValue("then").jsonObject }
        return checkNotNull(oneOf ?: then) { "$union is no union" }.map { checkNotNull(it.reference()) }
    }

    /** [name]'s definition, without the union's tag. */
    private fun fieldsOf(name: String): JsonObject {
        val tag = UNION_TAGS[name] ?: return def(name)
        return JsonObject(def(name) + ("properties" to JsonObject(def(name).getValue("properties").jsonObject - tag)))
    }

    /**
     * The nested definitions [name]'s fields hold, by reference or in place (`name.field`), each with the path of its
     * first instance under [prefix].
     */
    private fun references(
        name: String,
        prefix: String,
    ): List<Pair<String, String>> =
        def(name).fields().mapNotNull { (field, value) ->
            val property = value.jsonObject
            val direct = property.reference()
            val item = property["items"]?.jsonObject?.reference()
            when {
                direct != null -> direct to "$prefix$field"
                item != null -> item to "$prefix$field[0]"
                "properties" in property -> "$name.$field" to "$prefix$field"
                else -> null
            }
        }

    private fun JsonObject.reference() = this["\$ref"]?.jsonPrimitive?.content?.substringAfterLast('/')

    @TestFactory
    fun `every frame a bound is planted in decodes before it is planted`(): List<DynamicTest> =
        hosts().map { (host, _) -> dynamicTest("$host") { host.decode() } }

    @TestFactory
    fun `every schema bound on a protocol v1 field is refused under its path`(): List<DynamicTest> =
        hosts().flatMap { (host, definition) -> plants(host, definition) }.map { plant ->
            dynamicTest("$plant") {
                val refusal = assertThrows<ProtocolException.InvalidField> { plant.decode() }
                assertEquals(plant.t, refusal.t, "$plant")
                assertEquals(plant.field, refusal.field, "$plant")
            }
        }

    @Test
    fun `each nested definition is planted wherever an event holds it, in a sample with every field it defines`() {
        val nested = defs.keys.filter { it !in catalogue("clientEvent") + catalogue("serverEvent") }
        val hosted = nestings().map { it.name }.toSet()
        val defined = hosted.filterNot { '.' in it }.toSet()
        assertEquals(nested.toSet() - setOf("envelope", "clientEvent", "serverEvent", "pairingLink") - UNIONS, defined)
        hosted.forEach { name ->
            assertEquals(def(name).fields().keys, Json.parseToJsonElement(SAMPLES.getValue(name)).jsonObject.keys, name)
        }
    }

    private fun JsonObject.fields() =
        getValue("properties").jsonObject.filter { (name, property) ->
            name !in ENVELOPE && "x-reserved" !in property.jsonObject
        }

    /** Each bound [definition] states on a field of [host]'s frame, planted where [host] names its instance. */
    private fun plants(
        host: Plant,
        definition: JsonObject,
    ): List<Plant> {
        val prefix = if (host.field.isEmpty()) "" else "${host.field}."
        return definition.fields().flatMap { (name, property) ->
            violations(property.jsonObject).map { (bound, suffix, value) ->
                host.with("$prefix$name", value, "$name $bound", "$prefix$name$suffix")
            }
        }
    }

    /** Each bound [property] states, as its name, the path under the field it is refused at, and a value past it. */
    private fun violations(property: JsonObject): List<Triple<String, String, JsonElement>> {
        val item = property["items"]?.jsonObject
        val maxLength = property.number("maxLength") ?: property.number("x-max-bytes")
        return listOfNotNull(
            property.number("minLength")?.takeIf { it > BigInteger.ZERO }?.let {
                Triple("minLength", "", JsonPrimitive("a".repeat(it.toInt() - 1)))
            },
            maxLength?.let { Triple("maxLength", "", JsonPrimitive("a".repeat(it.toInt() + 1))) },
            property.number("minimum")?.let { Triple("minimum", "", JsonPrimitive(it - BigInteger.ONE)) },
            property.number("maximum")?.let { Triple("maximum", "", JsonPrimitive(it + BigInteger.ONE)) },
            property["pattern"]?.let { Triple("pattern", "", JsonPrimitive("!")) },
            property.number("minItems")?.takeIf { it > BigInteger.ZERO }?.let {
                Triple("minItems", "", JsonArray(List(it.toInt() - 1) { itemSample(item) }))
            },
            property
                .number(
                    "maxItems",
                )?.let { Triple("maxItems", "", JsonArray(List(it.toInt() + 1) { itemSample(item) })) },
            property["const"]?.let { Triple("const", "", JsonPrimitive(!it.jsonPrimitive.boolean)) },
            property["enum"]?.let { Triple("enum", "", JsonPrimitive("not-a-value")) },
            item?.number("minLength")?.let { Triple("items' minLength", "[0]", JsonArray(listOf(JsonPrimitive("")))) },
        )
    }

    /** A valid entry of an array whose items are [item]. */
    private fun itemSample(item: JsonObject?): JsonElement {
        val reference =
            item
                ?.get("\$ref")
                ?.jsonPrimitive
                ?.content
                ?.substringAfterLast('/')
        return when {
            reference != null -> Json.parseToJsonElement(SAMPLES.getValue(reference))
            item?.get("type")?.jsonPrimitive?.content == "string" -> JsonPrimitive("x")
            item?.get("type")?.jsonPrimitive?.content == "integer" -> JsonPrimitive(1)
            else -> error("no sample for an array of $item")
        }
    }

    private fun JsonObject.number(key: String): BigInteger? = this[key]?.jsonPrimitive?.content?.toBigInteger()
}
