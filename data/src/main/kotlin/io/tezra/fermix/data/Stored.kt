package io.tezra.fermix.data

import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.HexFormat

/**
 * How a wire model is stored: as JSON through core-protocol's own models and their generated serializers,
 * configured as the wire's codec configures them (core-protocol's WIRE_JSON, which it keeps internal): `t`
 * names a client event, a field the model does not know is ignored, and an absent optional field is not
 * written. So a stored row or request is the wire's shape, and one an older build wrote reads back in a
 * newer one. core-protocol's public codec frames an event with its `v` and `seq`, which storage has none of;
 * StoredJsonTest holds what this writes to that codec's text, byte for byte, envelope aside.
 */
internal val STORED_JSON =
    Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/** A SHA-256 in lowercase hex: an instance's id, a pinned certificate, a cached blob's name. */
internal val SHA256_HEX = Regex("[0-9a-f]{64}")

/** A `server_seq` as a SQLite integer: the daemon's seqs are SQLite row ids, so every one fits. */
internal fun ULong.toColumn(): Long {
    require(this <= Long.MAX_VALUE.toULong()) { "seq $this is past what SQLite stores" }
    return toLong()
}

/** A stored seq back as the wire's unsigned one. */
internal fun Long.toSeq(): ULong {
    check(this >= 0L) { "a stored seq is $this" }
    return toULong()
}

/** The lowercase hex of [bytes]' SHA-256: an instance's id, and a cached blob's name. */
internal fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
