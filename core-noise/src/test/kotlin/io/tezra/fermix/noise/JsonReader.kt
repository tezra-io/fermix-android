package io.tezra.fermix.noise

/** Deeper than the vectors file goes (four levels), so a malformed file fails instead of recursing. */
private const val MAX_DEPTH = 8

/** More entries than any object or array in the vectors file holds. */
private const val MAX_ITEMS = 64

/**
 * Just enough JSON to read the vendored vectors: objects, arrays, strings without escapes, whole
 * numbers, true, false and null. This module takes no JSON library; anything outside the subset
 * fails loud instead of being misread.
 */
internal class JsonReader(
    private val text: String,
) {
    private var at = 0

    fun read(): Any? {
        val value = value(0)
        skipSpace()
        check(at == text.length) { "JSON has trailing text at $at" }
        return value
    }

    private fun value(depth: Int): Any? {
        check(depth <= MAX_DEPTH) { "JSON nests deeper than $MAX_DEPTH at $at" }
        skipSpace()
        check(at < text.length) { "JSON ends where a value should be" }
        return when (text[at]) {
            '{' -> members(depth)
            '[' -> elements(depth)
            '"' -> string()
            else -> literal()
        }
    }

    private fun members(depth: Int): Map<String, Any?> {
        val members = LinkedHashMap<String, Any?>()
        expect('{')
        if (take('}')) return members
        do {
            check(members.size < MAX_ITEMS) { "JSON object has more than $MAX_ITEMS members at $at" }
            skipSpace()
            val name = string()
            expect(':')
            members[name] = value(depth + 1)
        } while (take(','))
        expect('}')
        return members
    }

    private fun elements(depth: Int): List<Any?> {
        val elements = ArrayList<Any?>()
        expect('[')
        if (take(']')) return elements
        do {
            check(elements.size < MAX_ITEMS) { "JSON array has more than $MAX_ITEMS elements at $at" }
            elements += value(depth + 1)
        } while (take(','))
        expect(']')
        return elements
    }

    private fun string(): String {
        expect('"')
        val end = text.indexOf('"', at)
        check(end >= 0) { "JSON string starting at $at never ends" }
        val value = text.substring(at, end)
        check('\\' !in value) { "JSON string at $at has an escape, which this reader does not read" }
        at = end + 1
        return value
    }

    private fun literal(): Any? {
        val start = at
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '-')) at++
        return when (val token = text.substring(start, at)) {
            "null" -> null
            "true" -> true
            "false" -> false
            else -> token.toLongOrNull() ?: error("JSON value '$token' at $start is not one this reader knows")
        }
    }

    private fun expect(char: Char) {
        check(take(char)) { "JSON expected '$char' at $at" }
    }

    private fun take(char: Char): Boolean {
        skipSpace()
        if (at >= text.length || text[at] != char) return false
        at++
        return true
    }

    private fun skipSpace() {
        while (at < text.length && text[at].isWhitespace()) at++
    }
}
