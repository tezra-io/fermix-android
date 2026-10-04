package io.tezra.fermix.push

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The byte that ends the JSON in the bucket, before the zeros (design section 10, "Message"). */
private const val PAD_MARK: Byte = 0x80.toByte()

/** The kinds of design section 7's "Push plaintext" row. */
private const val KIND_MESSAGE = "message"
private const val KIND_APPROVAL = "approval"
private const val KIND_TURN_FAILED = "turn_failed"

/**
 * A push's typed plaintext, protocol v2's (design section 7, the "Push plaintext" row): `{kind, profile_id,
 * …}` with the id fields of its kind. The vendored export is protocol v1, whose APNs plaintext is
 * `{preview_text, profile_id}` with no kind and no padding, so these shapes are provisional until engine
 * stage D1's export brings its FCM case per kind (ProvisionalFcmCasesTest). Every field is shown as text at
 * most: none names a channel, a file, an intent or a URI.
 */
sealed interface PushPlaintext {
    /** A row: the reply that ends a turn, or a proactive row. [previewText] is none when it did not fit. */
    data class Message(
        val profileId: String,
        val serverSeq: ULong,
        val previewText: String?,
    ) : PushPlaintext

    /** A live approval, [expiresAt] in Unix seconds: no token, no command route, no text. */
    data class Approval(
        val profileId: String,
        val approvalId: String,
        val expiresAt: Long,
    ) : PushPlaintext

    /** A turn that failed, never one the owner stopped. */
    data class TurnFailed(
        val profileId: String,
        val turnId: String,
        val code: String,
    ) : PushPlaintext

    /** A kind this app does not know, or none: the generic notification. */
    data object Unknown : PushPlaintext
}

/** What [readPushPlaintext] made of an opened push. */
sealed interface PlaintextRead {
    data class Read(
        val plaintext: PushPlaintext,
    ) : PlaintextRead

    /** The tag verified, but [reason] says what is wrong with the bytes, naming a field and never a value. */
    data class Unreadable(
        val reason: String,
    ) : PlaintextRead
}

/** The plaintext's JSON: strict, as the daemon writes it. */
private val PLAINTEXT_JSON = Json { isLenient = false }

/**
 * The typed plaintext of [padded], the bytes an opened push holds: exactly [PADDED_PLAINTEXT_BYTES], the
 * UTF-8 JSON object, one 0x80 byte, then zeros (design section 10). A known kind with a field missing or of
 * the wrong type is unreadable; an unknown kind, or none, is [PushPlaintext.Unknown].
 */
fun readPushPlaintext(padded: ByteArray): PlaintextRead {
    val bytes = unpadded(padded) ?: return PlaintextRead.Unreadable("padding")
    return jsonObjectOf(bytes)?.let(::typed) ?: PlaintextRead.Unreadable("json")
}

/** The JSON's bytes, before the mark and the zeros, or none when [padded] is not one bucket so padded. */
private fun unpadded(padded: ByteArray): ByteArray? {
    val mark = padded.indexOfLast { it != 0.toByte() }
    val marked = padded.size == PADDED_PLAINTEXT_BYTES && mark >= 0 && padded[mark] == PAD_MARK
    return if (marked) padded.copyOf(mark) else null
}

/** [bytes] as one JSON object in UTF-8, or none. */
private fun jsonObjectOf(bytes: ByteArray): JsonObject? {
    val decoder =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        PLAINTEXT_JSON.parseToJsonElement(decoder.decode(ByteBuffer.wrap(bytes)).toString()) as? JsonObject
    } catch (expectedOfBadBytes: CharacterCodingException) {
        null
    } catch (expectedOfBadJson: SerializationException) {
        null
    }
}

/** A known kind's field that is missing or of the wrong type, by its [field] name. */
private class BadField(
    val field: String,
) : Exception("the push plaintext's $field is missing or of the wrong type")

/** [json] by its kind. */
private fun typed(json: JsonObject): PlaintextRead =
    try {
        PlaintextRead.Read(
            when (json.kindOrNone()) {
                KIND_MESSAGE -> {
                    PushPlaintext.Message(json.text("profile_id"), json.seq("server_seq"), json.preview())
                }

                KIND_APPROVAL -> {
                    PushPlaintext.Approval(
                        json.text("profile_id"),
                        json.text("approval_id"),
                        json.seconds("expires_at"),
                    )
                }

                KIND_TURN_FAILED -> {
                    PushPlaintext.TurnFailed(json.text("profile_id"), json.text("turn_id"), json.text("code"))
                }

                else -> {
                    PushPlaintext.Unknown
                }
            },
        )
    } catch (bad: BadField) {
        PlaintextRead.Unreadable("field ${bad.field}")
    }

/** The kind, none when it is absent or no string: either way no kind this app knows. */
private fun JsonObject.kindOrNone(): String? = (this["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The non-empty string [name]. */
private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: throw BadField(name)

/** `preview_text`: a string, or `null` when the daemon's preview did not fit, or absent, which says the same. */
private fun JsonObject.preview(): String? {
    val value = this["preview_text"]
    if (value == null || value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BadField("preview_text")
}

/**
 * A row's `server_seq`, from 1 up to what the phone's store keeps, a signed 64-bit column (data's notified set):
 * the wire allows an unsigned 64-bit number, and one past that bound is unreadable here, its instance's generic
 * notification, rather than a store's refusal that would end the process before anything is posted.
 */
private fun JsonObject.seq(name: String): ULong =
    (this[name] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.toULongOrNull()
        ?.takeIf { it in 1uL..Long.MAX_VALUE.toULong() }
        ?: throw BadField(name)

/** Unix seconds, from 0. */
private fun JsonObject.seconds(name: String): Long =
    (this[name] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.toLongOrNull()
        ?.takeIf { it >= 0L }
        ?: throw BadField(name)
