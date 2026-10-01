package io.tezra.fermix.protocol

/**
 * The checks Rules.kt states one event's fields with, at the frame's [version] and with its raw tail
 * of [rawSize] bytes. Each refuses with the event's name and the field's path, and an absent optional
 * field (null) passes every check but [present].
 */
internal class FieldCheck(
    val t: String,
    val version: Int,
    val rawSize: Int,
) {
    fun require(
        field: String,
        holds: Boolean,
        reason: String,
    ) = refuseIf(!holds) { ProtocolException.InvalidField(t, field, reason) }

    fun present(
        field: String,
        value: Any?,
    ) = refuseIf(value == null) { ProtocolException.MissingField(t, field) }

    fun nonEmpty(
        field: String,
        value: String?,
    ) = require(field, value == null || value.isNotEmpty(), "is empty")

    /** UTF-8 bytes, the unit of the schema's `x-max-bytes`; a lone surrogate is not text at all. */
    fun maxBytes(
        field: String,
        value: String?,
        max: Int,
    ) {
        if (value == null) return
        require(field, !value.hasLoneSurrogate(), "is not valid UTF-8")
        val size = value.encodeToByteArray().size
        require(field, size <= max, "is $size bytes, past $max")
    }

    /** Unicode scalar values, the unit of the schema's `maxLength`. */
    fun maxScalars(
        field: String,
        value: String?,
        max: Int,
    ) {
        if (value == null) return
        val count = value.codePointCount(0, value.length)
        require(field, count <= max, "is $count characters, past $max")
    }

    fun inRange(
        field: String,
        value: Long?,
        range: LongRange,
    ) = require(field, value == null || value in range, "is $value, outside ${range.first} to ${range.last}")

    fun positive(
        field: String,
        value: ULong?,
    ) = require(field, value == null || value >= 1uL, "is 0, and counts from 1")

    fun matches(
        field: String,
        value: String?,
        pattern: Regex,
    ) = require(field, value == null || pattern.matches(value), "does not match ${pattern.pattern}")

    fun size(
        field: String,
        list: List<*>?,
        range: IntRange,
    ) = require(
        field,
        list == null || list.size in range,
        "has ${list?.size} entries, outside ${range.first} to ${range.last}",
    )

    /** Text the owner reads in the pairing prompt: non-blank, in UTF-8 bytes, and printable (PROTOCOL.md). */
    fun printable(
        field: String,
        value: String,
        maxBytes: Int,
    ) {
        require(field, value.isNotBlank(), "is blank")
        maxBytes(field, value, maxBytes)
        require(field, value.none(::isControl), "holds a C0, C1 or DEL control character")
    }

    /** Runs [check] on every entry of [list], each under its own path, `field[index]`. */
    fun <T> each(
        field: String,
        list: List<T>?,
        check: FieldCheck.(String, T) -> Unit,
    ) = list?.forEachIndexed { index, entry -> check("$field[$index]", entry) }
}

private const val LAST_C0 = '\u001F'
private const val DELETE = '\u007F'
private const val LAST_C1 = '\u009F'

/** A C0 or C1 control character, or DEL: never in text the owner reads (PROTOCOL.md, design section 6.3). */
internal fun isControl(char: Char) = char <= LAST_C0 || char in DELETE..LAST_C1

private fun String.hasLoneSurrogate(): Boolean =
    indices.any { index ->
        val char = this[index]
        when {
            char.isHighSurrogate() -> index + 1 >= length || !this[index + 1].isLowSurrogate()
            char.isLowSurrogate() -> index == 0 || !this[index - 1].isHighSurrogate()
            else -> false
        }
    }
