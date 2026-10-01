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
private const val CARD =
    """{"url":"https://example.com","site":"Example","title":"Example page","description":"A page",""" +
        """"image_ref":"$SHA"}"""

/** A whole instance of each nested definition, every optional field present. */
private val SAMPLES: Map<String, String> =
    mapOf(
        "profile" to """{"id":"main","name":"Fermix"}""",
        "candidate" to """{"host":"192.168.1.8","interface":"en0","scope":"lan"}""",
        "caps" to """{"commands":[],"media":true,"streaming":true,"max_media_bytes":1}""",
        "commandDescriptor" to """{"name":"help","aliases":["h"],"description":"Lists the commands"}""",
        "mediaRef" to
            """{"ref":"r","sha256":"$SHA","kind":"image","mime":"image/jpeg","size_bytes":1,"filename":"a.jpg",""" +
            """"caption":"A photo"}""",
        "historyMessage" to
            """{"server_seq":13,"role":"user","content":"Hi","kind":"text","ts":"2026-09-27T09:00:00.000000Z",""" +
            """"media_refs":[],"client_msg_id":"c","in_reply_to":"r","metadata":{},"link_previews":[$CARD],""" +
            """"truncated":true}""",
        "linkPreviewCard" to CARD,
    )

private val ENVELOPE = setOf("v", "t", "seq")

/**
 * Every bound protocol.schema.json states on a protocol v1 field, planted as a violation into a
 * valid frame of its event and refused under the field's path: minLength, maxLength and x-max-bytes,
 * minimum, maximum, pattern, minItems, maxItems, const, enum, and an array's items. Each frame is the
 * fullest fixture line of its event, a nested definition planted, wherever an event holds it, in a
 * whole sample of its own; a server event is decoded as a run's logical event when it takes no tail,
 * so no bound is out of reach of the 4 KiB header. PROTOCOL.md's rules that the schema does not state
 * are in ValidationTest, and protocol v2's bounds in ProvisionalV2Test.
 */
class SchemaBoundsTest {
    private val schema = Json.parseToJsonElement(vendored("protocol.schema.json")).jsonObject
    private val defs = schema.getValue("\$defs").jsonObject

    private fun def(name: String) = defs.getValue(name).jsonObject

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
    ) {
        private val raw = def(t)["x-raw-bytes"]?.jsonPrimitive?.boolean == true

        fun decode() {
            val tail = if (raw) byteArrayOf(1) else ByteArray(0)
            when {
                client -> decodeClientEvent(frameOf("$header", tail))
                raw -> decodeServerEvent(frameOf("$header", tail))
                else -> decodeLogicalServerEvent(1, 1uL, "${JsonObject(header - "v" - "seq")}".encodeToByteArray())
            }
        }

        /** This frame with [value] at [path], refused for [bound] under [refusedAt]. */
        fun with(
            path: String,
            value: JsonElement,
            bound: String,
            refusedAt: String = path,
        ) = Plant(client, t, planted(header, path, value), refusedAt, bound)

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
        val headers = headerLines(lines).map { it.json } + binaryLines(binary).map { it.header }
        val header =
            checkNotNull(headers.filter { it.getValue("t").jsonPrimitive.content == t }.maxByOrNull { it.size }) {
                "no fixture carries the ${if (client) "client" else "server"} event $t"
            }
        return Plant(client, t, header, "", "")
    }

    /** Every frame a bound is planted in, before it is planted. */
    private fun hosts(): List<Pair<Plant, JsonObject>> {
        val events =
            catalogue("clientEvent").map { fullest(true, it) to def(it) } +
                catalogue("serverEvent").map { fullest(false, it) to def(it) }
        val nested =
            nestings().map { (name, t, path) ->
                fullest(false, t).with(path, Json.parseToJsonElement(SAMPLES.getValue(name)), "") to def(name)
            }
        return events + nested
    }

    /**
     * Each place a server event holds a nested definition, read from the schema: the definition, the
     * event, and the path of its first instance there. Two levels down is as deep as the schema nests.
     */
    private fun nestings(): List<Triple<String, String, String>> {
        val direct =
            catalogue("serverEvent").flatMap { t ->
                references(def(t), "").map { (name, path) -> Triple(name, t, path) }
            }
        val inner =
            direct.flatMap { (outer, t, at) ->
                references(def(outer), "$at.").map { (name, path) -> Triple(name, t, path) }
            }
        check(
            inner.all { (name) ->
                references(def(name), "").isEmpty()
            },
        ) { "a definition nests three deep; plant it too" }
        return direct + inner
    }

    /** The nested definitions [definition]'s fields hold, each with the path of its first instance under [prefix]. */
    private fun references(
        definition: JsonObject,
        prefix: String,
    ): List<Pair<String, String>> =
        definition.fields().mapNotNull { (field, value) ->
            val property = value.jsonObject
            val direct = property.reference()
            val item = property["items"]?.jsonObject?.reference()
            when {
                direct != null -> direct to "$prefix$field"
                item != null -> item to "$prefix$field[0]"
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
        val hosted = nestings().map { it.first }.toSet()
        assertEquals(nested.toSet() - setOf("envelope", "clientEvent", "serverEvent", "pairingLink"), hosted)
        assertEquals(emptyList<Pair<String, String>>(), catalogue("clientEvent").flatMap { references(def(it), "") })
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
            else -> error("no sample for an array of $item")
        }
    }

    private fun JsonObject.number(key: String): BigInteger? = this[key]?.jsonPrimitive?.content?.toBigInteger()
}
