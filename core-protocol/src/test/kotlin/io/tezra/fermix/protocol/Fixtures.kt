package io.tezra.fermix.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.util.Base64

// The vendored contract, read from contracts/mobile itself, which core-protocol/build.gradle.kts
// puts on the test classpath; nothing here is a copy.

private val reader = Json

/** One vendored file's text. */
internal fun vendored(path: String): String {
    val stream =
        checkNotNull(Frame::class.java.getResourceAsStream("/$path")) {
            "/$path is not on the test classpath; core-protocol/build.gradle.kts puts contracts/mobile there"
        }
    return stream.use { it.readBytes().decodeToString(throwOnInvalidSequence = true) }
}

/** One fixture line, as the bytes of the JSON header it is. */
class HeaderLine(
    val number: Int,
    val text: String,
) {
    val bytes: ByteArray = text.encodeToByteArray()
    val json: JsonObject = reader.parseToJsonElement(text).jsonObject
    val t: String = json.getValue("t").jsonPrimitive.content

    /** The frame the line is the header of, with no raw tail. */
    fun frame(): ByteArray = prefixed(bytes)

    /** The parameterized tests are named by line and event. */
    override fun toString() = "line $number, $t"
}

private const val HEADER_OPENING = "{\"header\":"
private const val TAIL_KEY = ",\"bytes_b64\":"

/** One `{header, bytes_b64}` line: a frame with a raw tail, as the JSONL stores it. */
class BinaryLine(
    val number: Int,
    val text: String,
) {
    private val json = reader.parseToJsonElement(text).jsonObject
    val header: JsonObject = json.getValue("header").jsonObject

    /**
     * The header's bytes as the file carries them, cut from the line's text and never printed again,
     * so the byte-for-byte gate compares against the fixture's own spelling of every value.
     */
    val headerBytes: ByteArray = headerText().encodeToByteArray()
    val raw: ByteArray = Base64.getDecoder().decode(json.getValue("bytes_b64").jsonPrimitive.content)
    val t: String = header.getValue("t").jsonPrimitive.content

    fun frame(): ByteArray = prefixed(headerBytes, raw)

    override fun toString() = "line $number, $t"

    /** The text between `{"header":` and `,"bytes_b64":`, checked to be the header the line parses to. */
    private fun headerText(): String {
        check(text.startsWith(HEADER_OPENING)) { "line $number does not open with $HEADER_OPENING" }
        val end = text.lastIndexOf(TAIL_KEY)
        check(end > HEADER_OPENING.length) { "line $number has no $TAIL_KEY after its header" }
        val slice = text.substring(HEADER_OPENING.length, end)
        check(reader.parseToJsonElement(slice) == header) { "line $number's header is not the text before $TAIL_KEY" }
        return slice
    }
}

internal fun headerLines(path: String): List<HeaderLine> =
    vendored(path).lines().filter { it.isNotEmpty() }.mapIndexed { index, line -> HeaderLine(index + 1, line) }

internal fun binaryLines(path: String): List<BinaryLine> =
    vendored(path).lines().filter { it.isNotEmpty() }.mapIndexed { index, line -> BinaryLine(index + 1, line) }

/** A frame from its parts, built by hand: the uint32be length, the header, the tail. */
internal fun prefixed(
    header: ByteArray,
    raw: ByteArray = ByteArray(0),
): ByteArray =
    ByteBuffer
        .allocate(4 + header.size + raw.size)
        .putInt(header.size)
        .put(header)
        .put(raw)
        .array()

/** A header's frame, from its JSON text. */
internal fun frameOf(
    header: String,
    raw: ByteArray = ByteArray(0),
): ByteArray = prefixed(header.encodeToByteArray(), raw)

/** The header text of an encoded frame. */
internal fun headerOf(frame: ByteArray): String = Frame.decode(frame).header.decodeToString()

private val PATH_SEGMENT = Regex("""([^.\[\]]+)|\[([0-9]+)]""")

/**
 * [header] with [value] at [path], a field path as the refusals name it (`caps.commands[0].name`):
 * an object's key set, or an array's entry replaced or, one past its end, appended.
 */
internal fun planted(
    header: JsonObject,
    path: String,
    value: JsonElement,
): JsonObject = header.at(segments(path), value).jsonObject

/** [header] without the field at [path], whose last segment is a key the header holds there. */
internal fun removed(
    header: JsonObject,
    path: String,
): JsonObject {
    val parentPath = path.substringBeforeLast('.', "")
    val key = path.substringAfterLast('.')
    val parent = if (parentPath.isEmpty()) header else elementAt(header, parentPath).jsonObject
    check(key in parent) { "'$path' is not in the header" }
    val without = JsonObject(parent - key)
    return if (parentPath.isEmpty()) without else planted(header, parentPath, without)
}

/** A field path's segments: a key as a string, an index as an int. */
private fun segments(path: String): List<Any> {
    val segments = PATH_SEGMENT.findAll(path).map { it.groups[2]?.value?.toInt() ?: it.value }.toList()
    check(segments.isNotEmpty()) { "'$path' names no field" }
    return segments
}

private fun elementAt(
    header: JsonObject,
    path: String,
): JsonElement =
    segments(path).fold<Any, JsonElement>(header) { element, segment ->
        if (segment is Int) element.jsonArray[segment] else element.jsonObject.getValue(segment as String)
    }

/** [value] at [segments] under this element, where [JsonNull] stands for a key that is absent so far. */
private fun JsonElement.at(
    segments: List<Any>,
    value: JsonElement,
): JsonElement =
    when (val head = segments.firstOrNull()) {
        null -> value
        is String -> atKey(head, segments.drop(1), value)
        else -> atIndex(head as Int, segments.drop(1), value)
    }

private fun JsonElement.atKey(
    key: String,
    rest: List<Any>,
    value: JsonElement,
): JsonObject {
    val fields = if (this is JsonNull) emptyMap() else jsonObject
    return JsonObject(fields + (key to (fields[key] ?: JsonNull).at(rest, value)))
}

private fun JsonElement.atIndex(
    index: Int,
    rest: List<Any>,
    value: JsonElement,
): JsonArray {
    val entries = if (this is JsonNull) mutableListOf() else jsonArray.toMutableList()
    check(index <= entries.size) { "[$index] is past the end of a ${entries.size}-entry array" }
    val entry = (entries.getOrNull(index) ?: JsonNull).at(rest, value)
    if (index == entries.size) entries.add(entry) else entries[index] = entry
    return JsonArray(entries)
}

/** A stack far smaller than a phone thread's, so a recursion no bound stops overflows it at once. */
private const val SMALL_STACK_BYTES = 256L * 1_024
private const val SMALL_STACK_MILLIS = 60_000L

/**
 * Runs [block] on a thread with a 256 KiB stack and hands back what it returned or threw, a
 * StackOverflowError included, so a test of a bound on nesting fails the same way on every machine.
 */
internal fun <T> onSmallStack(block: () -> T): T {
    var outcome: Result<T>? = null
    val thread = Thread(null, { outcome = runCatching(block) }, "small-stack", SMALL_STACK_BYTES)
    thread.start()
    thread.join(SMALL_STACK_MILLIS)
    check(!thread.isAlive) { "the small-stack thread ran past $SMALL_STACK_MILLIS ms" }
    return checkNotNull(outcome) { "the small-stack thread ended without an outcome" }.getOrThrow()
}
