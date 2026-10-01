package io.tezra.fermix.protocol

// A pairing link's query, read as application/x-www-form-urlencoded: `&` between parameters, `=`
// between a name and its value, and in both `+` a space and `%XY` a byte. A value is decoded straight
// into a byte array, so the secret's bytes are never a string of their own once decoded (PairingLink.kt).

private const val SPACE = 0x20
private const val FIRST_PRINTABLE = 0x21
private const val LAST_PRINTABLE = 0x7E
private const val HEX = 16
private const val ESCAPE_CHARS = 3

/**
 * Reads into [values] the decoded value of each parameter of [query] that [names] lists, and skips
 * every other unread, a name that is not form-encoded among them. A listed parameter that is
 * repeated, or whose value is not form-encoded, is refused. [values] is the caller's, who zeroes it
 * however this ends.
 */
internal fun readForm(
    query: String,
    names: Collection<String>,
    values: MutableMap<String, ByteArray>,
) {
    for (pair in query.split('&')) {
        val name = parameterName(pair.substringBefore('='))
        if (name == null || name !in names) continue
        refuseIf(name in values) { ProtocolException.RepeatedParameter(name) }
        values[name] = formDecoded(name, pair.substringAfter('=', ""))
    }
}

/** A parameter's name, decoded; null for one that is not form-encoded, which names none this parser reads. */
private fun parameterName(raw: String): String? = if (isFormEncoded(raw)) decode(raw).decodeToString() else null

private fun formDecoded(
    name: String,
    text: String,
): ByteArray {
    refuseIf(!isFormEncoded(text)) {
        ProtocolException.MalformedParameter(
            name,
            "is not form-encoded: a % without two hex digits, or a character carried only encoded",
        )
    }
    return decode(text)
}

/** Every character printable ASCII, a space only as `+`, and every `%` followed by two hex digits. */
private fun isFormEncoded(text: String): Boolean =
    text.all { it.code in FIRST_PRINTABLE..LAST_PRINTABLE } &&
        text.indices.all { at -> text[at] != '%' || (hexAt(text, at + 1) >= 0 && hexAt(text, at + 2) >= 0) }

/** Decodes [text], which [isFormEncoded] has passed: one byte per character or per `%XY`, in one pass. */
private fun decode(text: String): ByteArray {
    val bytes = ByteArray(text.length - (ESCAPE_CHARS - 1) * text.count { it == '%' })
    var at = 0
    for (index in bytes.indices) {
        val escaped = text[at] == '%'
        bytes[index] = if (escaped) (hexAt(text, at + 1) * HEX + hexAt(text, at + 2)).toByte() else plain(text[at])
        at += if (escaped) ESCAPE_CHARS else 1
    }
    return bytes
}

/** The hex digit at [at], or -1 when there is none there. */
private fun hexAt(
    text: String,
    at: Int,
): Int = text.getOrNull(at)?.let { Character.digit(it, HEX) } ?: -1

private fun plain(char: Char): Byte = (if (char == '+') SPACE else char.code).toByte()
