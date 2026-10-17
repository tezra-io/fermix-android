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

/** The key that tells a reply block's type (the companion PROTOCOL.md "Blocks under a reply"). */
internal const val BLOCK_TYPE = "type"

/** The one block type this app draws: the places a reply links. */
internal const val PLACES_TYPE = "places"

/** The reply block union's serial name, which the shape check (ShapeCheck.kt) reads it by. */
internal const val REPLY_BLOCK_SERIAL_NAME = "io.tezra.fermix.protocol.ReplyBlock"

/** Whether a block's fields are a places block's: by `type` alone, the string "places". */
private fun isPlaces(fields: JsonObject): Boolean {
    val type = fields[BLOCK_TYPE]
    return type is JsonPrimitive && type.isString && type.content == PLACES_TYPE
}

/**
 * The model a block's fields are held to (ShapeCheck.kt): a places block's, and none for a type this app does not
 * know, which is skipped whatever it holds (the companion PROTOCOL.md "Blocks under a reply").
 */
internal fun blockModel(fields: JsonObject): SerialDescriptor? =
    if (isPlaces(fields)) serializer<ReplyBlock.Places>().descriptor else null

/**
 * The reply block union on the wire, JSON alone: a places block with `type` first and then its places, as the
 * daemon writes it, and a skipped type as it came. The shape check has passed the header before it decodes, so a
 * places block that reaches here holds places of their types.
 */
internal object ReplyBlockSerializer : KSerializer<ReplyBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(REPLY_BLOCK_SERIAL_NAME)

    override fun deserialize(decoder: Decoder): ReplyBlock {
        val input = decoder as? JsonDecoder ?: throw SerializationException("a reply block is read from JSON alone")
        val fields =
            input.decodeJsonElement() as? JsonObject ?: throw SerializationException("a reply block is not an object")
        if (!isPlaces(fields)) return ReplyBlock.Skipped(fields)
        return input.json.decodeFromJsonElement(serializer<ReplyBlock.Places>(), JsonObject(fields - BLOCK_TYPE))
    }

    override fun serialize(
        encoder: Encoder,
        value: ReplyBlock,
    ) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("a reply block is written as JSON alone")
        val element =
            when (value) {
                is ReplyBlock.Places -> {
                    val fields = output.json.encodeToJsonElement(serializer<ReplyBlock.Places>(), value).jsonObject
                    JsonObject(mapOf(BLOCK_TYPE to JsonPrimitive(PLACES_TYPE)) + fields)
                }

                is ReplyBlock.Skipped -> {
                    value.fields
                }
            }
        output.encodeJsonElement(element)
    }
}
