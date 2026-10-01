package io.tezra.fermix.protocol

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Deeper than any model nests; the models are fixed, so passing it is a fault of this module. */
private const val MAX_MODEL_DEPTH = 8

private const val UNSIGNED_LONG = "kotlin.ULong"

/** What each primitive field must be, by the kind of its model property. */
private val PRIMITIVES: Map<SerialKind, Pair<String, (JsonPrimitive) -> Boolean>> =
    mapOf(
        PrimitiveKind.STRING to ("a string" to { value -> value.isString }),
        PrimitiveKind.BOOLEAN to ("true or false" to { value -> !value.isString && value.booleanOrNull != null }),
        PrimitiveKind.INT to (
            "a 32-bit integer" to { value ->
                !value.isString && value.content.toIntOrNull() != null
            }
        ),
        PrimitiveKind.LONG to
            ("a 64-bit integer" to { value -> !value.isString && value.content.toLongOrNull() != null }),
    )

/**
 * Checks a header's fields against the model they decode into, read from its descriptor: every
 * property without a default is present, none is null (an optional field is absent, never null, and
 * a row's metadata never holds one, PROTOCOL.md), and every value is of its property's type and, for
 * a closed set, one of its values. A field the model does not have is not looked at, a null in it
 * included. Each refusal names the field's path; the decoder that follows is left nothing to refuse.
 */
internal fun checkShape(
    t: String,
    json: JsonObject,
    descriptor: SerialDescriptor,
) = checkObject(t, "", json, descriptor, 0)

private fun checkObject(
    t: String,
    prefix: String,
    json: JsonObject,
    descriptor: SerialDescriptor,
    depth: Int,
) {
    check(depth <= MAX_MODEL_DEPTH) { "${descriptor.serialName} nests past $MAX_MODEL_DEPTH levels" }
    for (index in 0 until descriptor.elementsCount) {
        val path = join(prefix, descriptor.getElementName(index))
        val value = json[descriptor.getElementName(index)]
        if (value == null) {
            refuseIf(!descriptor.isElementOptional(index)) { ProtocolException.MissingField(t, path) }
            continue
        }
        checkValue(t, path, value, descriptor.getElementDescriptor(index), depth)
    }
}

private fun checkValue(
    t: String,
    path: String,
    value: JsonElement,
    descriptor: SerialDescriptor,
    depth: Int,
) {
    refuseIf(value is JsonNull) { ProtocolException.NullField(t, path) }
    val kind = descriptor.kind
    when {
        descriptor.isInline -> checkUnsigned(t, path, value, descriptor)
        kind == SerialKind.ENUM -> checkEnum(t, path, value, descriptor)
        kind is PrimitiveKind -> checkPrimitive(t, path, value, kind)
        kind == StructureKind.LIST -> checkList(t, path, value, descriptor, depth)
        kind == StructureKind.CLASS -> checkObject(t, path, objectAt(t, path, value), descriptor, depth + 1)
        kind == StructureKind.MAP -> refuseNullsWithin(t, path, objectAt(t, path, value))
        else -> error("no shape check for ${descriptor.serialName}, a $kind")
    }
}

/** Refuses a null anywhere in a free-form object, a row's metadata, by its path. */
private fun refuseNullsWithin(
    t: String,
    path: String,
    json: JsonObject,
) {
    val inner = firstNull(json, 0) ?: return
    throw ProtocolException.NullField(t, path + inner.asReversed().joinToString(""))
}

/**
 * The path to the first null in [element], as its segments (`.name`, `[index]`) innermost first, or
 * null when it holds none. A segment is built only on the way back from a null, so a wide value costs
 * one visit per node however long its keys are. [parseObject] has bounded the depth.
 */
private fun firstNull(
    element: JsonElement,
    depth: Int,
): List<String>? {
    check(depth <= MAX_JSON_DEPTH) { "a value $depth levels down got past the nesting scan" }
    return when (element) {
        is JsonNull -> {
            emptyList()
        }

        is JsonObject -> {
            element.entries.firstNotNullOfOrNull { (name, value) ->
                firstNull(value, depth + 1)?.plus(".${quotedKey(name)}")
            }
        }

        is JsonArray -> {
            element.withIndex().firstNotNullOfOrNull { (index, value) ->
                firstNull(value, depth + 1)?.plus("[$index]")
            }
        }

        is JsonPrimitive -> {
            null
        }
    }
}

/** ULong is the one inline class the models use: a whole number from 0 to 2^64 - 1. */
private fun checkUnsigned(
    t: String,
    path: String,
    value: JsonElement,
    descriptor: SerialDescriptor,
) {
    // A nullable property's descriptor is named with a trailing ?.
    check(descriptor.serialName.removeSuffix("?") == UNSIGNED_LONG) {
        "no shape check for the inline ${descriptor.serialName}"
    }
    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toULongOrNull()
    refuseIf(number == null) { ProtocolException.InvalidField(t, path, "is not an integer from 0 to 2^64 - 1") }
}

private fun checkEnum(
    t: String,
    path: String,
    value: JsonElement,
    descriptor: SerialDescriptor,
) {
    val names = descriptor.elementNames.toList()
    val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    refuseIf(name !in names) { ProtocolException.InvalidField(t, path, "is not one of ${names.joinToString()}") }
}

private fun checkPrimitive(
    t: String,
    path: String,
    value: JsonElement,
    kind: PrimitiveKind,
) {
    val (expected, accepts) = PRIMITIVES[kind] ?: error("no shape check for a $kind field")
    refuseIf(value !is JsonPrimitive || !accepts(value)) { ProtocolException.InvalidField(t, path, "is not $expected") }
}

private fun checkList(
    t: String,
    path: String,
    value: JsonElement,
    descriptor: SerialDescriptor,
    depth: Int,
) {
    val list = value as? JsonArray
    refuseIf(list == null) { ProtocolException.InvalidField(t, path, "is not an array") }
    list?.forEachIndexed { index, entry ->
        checkValue(t, "$path[$index]", entry, descriptor.getElementDescriptor(0), depth + 1)
    }
}

private fun objectAt(
    t: String,
    path: String,
    value: JsonElement,
): JsonObject = value as? JsonObject ?: throw ProtocolException.InvalidField(t, path, "is not an object")

private fun join(
    prefix: String,
    name: String,
) = if (prefix.isEmpty()) name else "$prefix.$name"
