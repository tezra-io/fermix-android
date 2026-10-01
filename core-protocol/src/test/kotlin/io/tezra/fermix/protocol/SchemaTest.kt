package io.tezra.fermix.protocol

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val ENVELOPE = setOf("v", "t", "seq")
private const val ULONG = "kotlin.ULong"

/**
 * The models against protocol.schema.json, which they are written by hand from and checked
 * against here rather than generated from. "Required" is read by reflection over the kotlinx
 * descriptors (a property without a default), together with the version table in Rules.kt for
 * the fields one version alone requires; the catalogue is the rule books' keys; each field's type is
 * its descriptor's kind, and a nested object's its model. Where protocol v2 adds to a protocol v1
 * shape, the addition must be one ProvisionalV2Test lists. Every bound the schema states is planted in
 * SchemaBoundsTest.
 */
class SchemaTest {
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
            .toSet()

    private fun JsonObject.required() =
        this["required"]
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.toSet()
            .orEmpty()

    private fun JsonObject.properties() = this["properties"]?.jsonObject.orEmpty()

    private fun SerialDescriptor.required() =
        (0 until elementsCount).filterNot(::isElementOptional).map(::getElementName).toSet()

    /** Each nested definition of the schema, and the model that carries it. */
    private val shapes: Map<String, SerialDescriptor> =
        mapOf(
            "profile" to serializer<Profile>().descriptor,
            "candidate" to serializer<Candidate>().descriptor,
            "caps" to serializer<Caps>().descriptor,
            "commandDescriptor" to serializer<CommandDescriptor>().descriptor,
            "mediaRef" to serializer<MediaRef>().descriptor,
            "historyMessage" to serializer<HistoryMessage>().descriptor,
            "linkPreviewCard" to serializer<LinkPreviewCard>().descriptor,
        )

    /** A protocol v1 definition, its model, and the model's version-bound fields from Rules.kt. */
    private class Model(
        val name: String,
        val definition: JsonObject,
        val descriptor: SerialDescriptor,
        val versioned: List<VersionedField>,
    )

    /** Every protocol v1 definition with a model: the events of both directions and the nested shapes. */
    private fun v1Models(): List<Model> {
        val client =
            catalogue("clientEvent").map {
                CLIENT_BOOK.rules.getValue(it).let { r ->
                    Model(it, def(it), r.descriptor, r.versioned)
                }
            }
        val server =
            catalogue("serverEvent").map {
                SERVER_BOOK.rules.getValue(it).let { r ->
                    Model(it, def(it), r.descriptor, r.versioned)
                }
            }
        return client + server + shapes.map { (name, descriptor) -> Model(name, def(name), descriptor, emptyList()) }
    }

    /** The fields protocol v1 requires of a model: its non-optional properties, and its v1 rows in Rules.kt. */
    private fun Model.requiredAtV1(): Set<String> =
        descriptor.required() + versioned.filter { V1 in it.requiredIn }.map { it.name }

    @Test
    fun `the codec's bounds are the schema's`() {
        assertEquals(schema.getValue("x-max-json-header-bytes").jsonPrimitive.int, MAX_HEADER_BYTES)
        assertEquals(schema.getValue("x-max-raw-chunk-bytes").jsonPrimitive.int, MAX_RAW_BYTES)
        assertEquals(schema.getValue("x-max-plaintext-bytes").jsonPrimitive.int, MAX_PLAINTEXT_BYTES)
        assertEquals(schema.getValue("x-max-event-bytes").jsonPrimitive.int, MAX_EVENT_BYTES)
        assertEquals(schema.getValue("x-max-event-parts").jsonPrimitive.int, MAX_EVENT_PARTS)
        assertEquals(
            "uint32be-json-length + json-header + raw-bytes",
            schema.getValue("x-frame-format").jsonPrimitive.content,
        )
    }

    @Test
    fun `the codec reads every version the vendored schema serves`() {
        val range = schema.getValue("x-supported-version-range").jsonObject
        val served = range.getValue("min").jsonPrimitive.int..range.getValue("max").jsonPrimitive.int
        assertTrue(served.all { it in VERSIONS }, "the schema serves $served, the codec reads $VERSIONS")
    }

    @Test
    fun `the client events the codec knows are the schema's and protocol v2's`() {
        assertEquals(catalogue("clientEvent") + V2_CLIENT_EVENTS, CLIENT_BOOK.rules.keys)
        assertEquals(CLIENT_BOOK.rules.keys, sealedNames(CLIENT_BOOK.serializer.descriptor))
    }

    @Test
    fun `the server events the codec knows are the schema's and protocol v2's`() {
        assertEquals(catalogue("serverEvent") + V2_SERVER_EVENTS, SERVER_BOOK.rules.keys)
        assertEquals(SERVER_BOOK.rules.keys, sealedNames(SERVER_BOOK.serializer.descriptor))
    }

    @Test
    fun `each protocol v1 model requires exactly the fields its schema definition does`() {
        v1Models().forEach { model ->
            assertEquals(model.definition.required() - ENVELOPE, model.requiredAtV1(), model.name)
        }
    }

    @Test
    fun `each protocol v1 model carries its definition's fields, and adds only protocol v2's`() {
        v1Models().forEach { model ->
            val properties = model.definition.properties()
            val schemaFields =
                properties.keys - ENVELOPE - properties.filterValues { "x-reserved" in it.jsonObject }.keys
            val modelFields = model.descriptor.elementNames.toSet()
            assertEquals(emptySet<String>(), schemaFields - modelFields, "${model.name} lacks a schema field")
            assertEquals(V2_FIELDS[model.name].orEmpty(), modelFields - schemaFields, "${model.name} adds a field")
        }
    }

    @Test
    fun `each closed set holds the schema's values and protocol v2's`() {
        v1Models().forEach { model ->
            model.definition.properties().filterValues { "enum" in it.jsonObject }.forEach { (field, property) ->
                val values =
                    property.jsonObject
                        .getValue("enum")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                        .toSet()
                val element = model.descriptor.getElementDescriptor(model.descriptor.getElementIndex(field))
                val name = "${model.name}.$field"
                assertEquals(SerialKind.ENUM, element.kind, name)
                assertEquals(values + V2_VALUES[name].orEmpty(), element.elementNames.toSet(), name)
            }
        }
    }

    @Test
    fun `each protocol v1 field, and each entry of an array, is of its definition's type`() {
        v1Models().forEach { model ->
            model.definition
                .properties()
                .filter { (field, property) -> field !in ENVELOPE && "x-reserved" !in property.jsonObject }
                .forEach { (field, property) -> assertTyped(model, field, property.jsonObject) }
        }
    }

    /**
     * [field] of [model] has its [property]'s type, its entries their items' type, and it is a ULong
     * when it reaches 2^64 - 1.
     */
    private fun assertTyped(
        model: Model,
        field: String,
        property: JsonObject,
    ) {
        val name = "${model.name}.$field"
        val element = model.descriptor.getElementDescriptor(model.descriptor.getElementIndex(field))
        assertEquals(schemaType(name, property), modelType(element), name)
        val items = property["items"]?.jsonObject
        if (items !=
            null
        ) {
            assertEquals(schemaType("$name[]", items), modelType(element.getElementDescriptor(0)), "$name[]")
        }
        val maximum = property["maximum"]?.jsonPrimitive?.content?.toBigInteger()
        if (maximum != null && maximum > Long.MAX_VALUE.toBigInteger()) {
            assertEquals(ULONG, element.serialName.removeSuffix("?"), "$name reaches 2^64 - 1")
        }
    }

    /** A schema property's type: its `type`, or what its `enum`, `const` or `$ref` says it is. */
    private fun schemaType(
        name: String,
        property: JsonObject,
    ): String {
        val reference = property["\$ref"]?.jsonPrimitive?.content?.substringAfterLast('/')
        return when {
            name in V2_CLOSED_SETS || "enum" in property -> {
                "enum"
            }

            "const" in property -> {
                if (property.getValue("const").jsonPrimitive.booleanOrNull !=
                    null
                ) {
                    "boolean"
                } else {
                    "const"
                }
            }

            reference != null -> {
                "object ${shapes[reference]?.serialName ?: "with no model for $reference"}"
            }

            else -> {
                property.getValue("type").jsonPrimitive.content
            }
        }
    }

    /** A model property's type, in the schema's words. */
    private fun modelType(descriptor: SerialDescriptor): String {
        val kind = descriptor.kind
        return when {
            descriptor.isInline -> if (descriptor.serialName.removeSuffix("?") == ULONG) "integer" else "inline"
            kind == SerialKind.ENUM -> "enum"
            kind == PrimitiveKind.STRING -> "string"
            kind == PrimitiveKind.INT || kind == PrimitiveKind.LONG -> "integer"
            kind == PrimitiveKind.BOOLEAN -> "boolean"
            kind == StructureKind.LIST -> "array"
            kind == StructureKind.MAP -> "object"
            kind == StructureKind.CLASS -> "object ${descriptor.serialName.removeSuffix("?")}"
            else -> "$kind"
        }
    }

    @Test
    fun `the pairing link requires the schema's parameters`() {
        assertEquals(def("pairingLink").required(), REQUIRED_PARAMETERS.getValue(V1).toSet())
    }

    private fun sealedNames(descriptor: SerialDescriptor) =
        descriptor
            .getElementDescriptor(1)
            .elementDescriptors
            .map {
                it.serialName
            }.toSet()
}
