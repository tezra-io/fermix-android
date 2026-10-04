package io.tezra.fermix.push

import java.util.Base64

/** The FCM message's `v` (design section 10, "Message"): a push of protocol v2's typed plaintext. */
const val PUSH_VERSION = "2"

/** The ChaCha20-Poly1305 nonce `n` carries. */
const val NONCE_BYTES = 12

/** The one bucket every plaintext is padded to, so a push reveals nothing but that it happened. */
const val PADDED_PLAINTEXT_BYTES = 2_048

/** The Poly1305 tag appended to the ciphertext. */
const val TAG_BYTES = 16

/** What `c` carries: the bucket and its tag, 2,752 characters of base64. */
const val SEALED_BYTES = PADDED_PLAINTEXT_BYTES + TAG_BYTES

/** FCM's bound on a message's data map, keys and values in UTF-8, which the daemon keeps to. */
const val MAX_DATA_BYTES = 4_096

private const val BASE64_GROUP_CHARS = 4
private const val BASE64_GROUP_BYTES = 3

/** Plain base64 of exactly [bytes] bytes, a multiple of 3, so with no padding and one encoding only. */
private fun base64Of(bytes: Int): Regex {
    check(bytes % BASE64_GROUP_BYTES == 0) { "$bytes bytes would need base64 padding" }
    return Regex("[A-Za-z0-9+/]{${bytes / BASE64_GROUP_BYTES * BASE64_GROUP_CHARS}}")
}

private val NONCE_TEXT = base64Of(NONCE_BYTES)
private val SEALED_TEXT = base64Of(SEALED_BYTES)

/** What [PushEnvelope.read] made of a message's data. */
sealed interface EnvelopeRead {
    data class Read(
        val envelope: PushEnvelope,
    ) : EnvelopeRead

    /** Outside the bounds: [reason] names the field, `v`, `n` or `c`, or `size` for the whole map, never a value. */
    data class Refused(
        val reason: String,
    ) : EnvelopeRead
}

/**
 * An FCM message's `data`, `{"v":"2","n":…,"c":…}` (design section 10), read within its bounds and before
 * anything opens it: the map is a string from outside the app until a tag verifies (AGENTS.md), so one
 * outside the bounds costs no Keystore agreement. The nonce and the sealed bytes are handed out as copies.
 */
class PushEnvelope private constructor(
    private val nonceBytes: ByteArray,
    private val sealedBytes: ByteArray,
) {
    fun nonce(): ByteArray = nonceBytes.copyOf()

    fun sealed(): ByteArray = sealedBytes.copyOf()

    companion object {
        /**
         * [data] as an envelope: the whole map within [MAX_DATA_BYTES] first, then `v` exactly [PUSH_VERSION],
         * and `n` and `c` plain base64 of [NONCE_BYTES] and [SEALED_BYTES]. Another key is left alone, so a
         * daemon may add one within the bound.
         */
        fun read(data: Map<String, String>): EnvelopeRead {
            val refusal = refusalOf(data) ?: return EnvelopeRead.Read(envelopeOf(data))
            return EnvelopeRead.Refused(refusal)
        }

        private fun envelopeOf(data: Map<String, String>): PushEnvelope {
            val decoder = Base64.getDecoder()
            return PushEnvelope(decoder.decode(data.getValue("n")), decoder.decode(data.getValue("c")))
        }
    }
}

/** The field [data] is refused for, in the order they are read, or none. */
private fun refusalOf(data: Map<String, String>): String? =
    when {
        data.entries.sumOf { (key, value) -> key.utf8Bytes() + value.utf8Bytes() } > MAX_DATA_BYTES -> "size"
        data["v"] != PUSH_VERSION -> "v"
        data["n"]?.let(NONCE_TEXT::matches) != true -> "n"
        data["c"]?.let(SEALED_TEXT::matches) != true -> "c"
        else -> null
    }

private fun String.utf8Bytes(): Int = encodeToByteArray().size
