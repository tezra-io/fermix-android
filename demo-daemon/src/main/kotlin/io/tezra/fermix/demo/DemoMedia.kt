package io.tezra.fermix.demo

import io.tezra.fermix.protocol.MediaRef
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

// The blobs the demo's rows name: two photos, a link preview's thumbnail and a log, each made here as the demo
// starts, from fixed numbers, so nothing is read from a file or the network and every run has the same bytes.
// A blob's ref is its SHA-256 in hex, as a daemon's are (PROTOCOL.md "Media downloads").

private const val PHOTO_WIDTH = 640
private const val PHOTO_HEIGHT = 427
private const val THUMB_WIDTH = 240
private const val THUMB_HEIGHT = 160
private const val LOG_LINES = 60

private const val BYTE = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val PNG_BIT_DEPTH = 8
private const val PNG_COLOUR_RGB = 2
private const val RGB_BYTES = 3
private const val IHDR_BYTES = 13
private const val CHUNK_OVERHEAD = 12

// The pictures' colours, 0xRRGGBB, and their shapes.
private const val NIGHT = 0x16285C
private const val DAWN = 0xF69654
private const val SHORE = 0x181C24
private const val SUN = 0xFFD68C
private const val SUN_RADIUS = 40
private const val HORIZON_SHARE = 0.6f
private const val SUN_ACROSS = 0.66f
private const val SKY_TOP = 0xB0CEE6
private const val SKY_LOW = 0xE2ECF4
private const val WALL = 0x6E7074
private const val WALL_HEIGHT = 18
private const val WALL_STEP = 80
private const val WALL_NOTCH = 4
private const val SEA_FAR = 0x28608C
private const val SEA_NEAR = 0x0E3052
private const val PAPER = 0xF4F4F0
private const val INK = 0x3C4048
private const val MARGIN = 24
private const val LINE_PITCH = 18
private const val LINE_HEIGHT = 8

/** The PNG signature (ISO/IEC 15948, 5.2). */
private val PNG_SIGNATURE = "89504e470d0a1a0a".hexToByteArray()

/** A blob the demo's rows can name. */
enum class DemoBlobName { SUNRISE, HARBOUR, PREVIEW, EXPORT_LOG }

/** A blob the demo holds: its bytes, which it owns from its making on, and how a row names it. */
class DemoBlob(
    val kind: String,
    val mime: String,
    val filename: String,
    private val bytes: ByteArray,
    val caption: String? = null,
) {
    val ref: String = sha256(bytes).toHexString()
    val size: Long get() = bytes.size.toLong()

    fun bytes(): ByteArray = bytes.copyOf()

    /** A copy of its bytes from [from] up to [to]: one chunk of a download. */
    fun slice(
        from: Int,
        to: Int,
    ): ByteArray = bytes.copyOfRange(from, to)

    /** The row's `media_refs[]` entry for it. */
    fun mediaRef(): MediaRef = MediaRef(ref, kind, mime, size, sha256 = ref, filename = filename, caption = caption)
}

/** The demo's own blobs, made once. */
fun demoBlobs(): Map<DemoBlobName, DemoBlob> =
    mapOf(
        DemoBlobName.SUNRISE to
            DemoBlob(
                "image",
                "image/png",
                "sunrise.png",
                png(PHOTO_WIDTH, PHOTO_HEIGHT, ::sunrise),
            ),
        DemoBlobName.HARBOUR to
            DemoBlob(
                "image",
                "image/png",
                "harbour.png",
                png(PHOTO_WIDTH, PHOTO_HEIGHT, ::harbour),
            ),
        DemoBlobName.PREVIEW to
            DemoBlob(
                "image",
                "image/png",
                "task.png",
                png(THUMB_WIDTH, THUMB_HEIGHT, ::preview),
            ),
        DemoBlobName.EXPORT_LOG to DemoBlob("document", "text/plain", "nightly-export.log", exportLog()),
    )

/** A sky going from night blue to dawn orange over a dark shore, with the sun on the horizon. */
private fun sunrise(
    x: Int,
    y: Int,
): Int {
    val horizon = (PHOTO_HEIGHT * HORIZON_SHARE).toInt()
    val sunX = (PHOTO_WIDTH * SUN_ACROSS).toInt()
    val inSun = (x - sunX) * (x - sunX) + (y - horizon) * (y - horizon) < SUN_RADIUS * SUN_RADIUS
    return when {
        y >= horizon -> SHORE
        inSun -> SUN
        else -> mix(NIGHT, DAWN, y.toFloat() / horizon)
    }
}

/** A harbour: a pale sky, a grey breakwater and the sea darkening towards the shore. */
private fun harbour(
    x: Int,
    y: Int,
): Int {
    val sea = PHOTO_HEIGHT / 2
    val wall = sea - WALL_HEIGHT + if (x / WALL_STEP % 2 == 0) 0 else WALL_NOTCH
    return when {
        y < wall -> mix(SKY_TOP, SKY_LOW, y.toFloat() / wall)
        y < sea -> WALL
        else -> mix(SEA_FAR, SEA_NEAR, (y - sea).toFloat() / sea)
    }
}

/** A link preview's thumbnail: a page's lines of ink on paper. */
private fun preview(
    x: Int,
    y: Int,
): Int {
    val onLine = y >= MARGIN && y < THUMB_HEIGHT - MARGIN && y % LINE_PITCH < LINE_HEIGHT
    val inMargins = x >= MARGIN && x < THUMB_WIDTH - MARGIN
    return if (onLine && inMargins) INK else PAPER
}

private fun mix(
    from: Int,
    to: Int,
    at: Float,
): Int {
    fun channel(shift: Int): Int {
        val a = from shr shift and BYTE
        val b = to shr shift and BYTE
        return (a + (b - a) * at.coerceIn(0f, 1f)).toInt() shl shift
    }
    return channel(RED_SHIFT) or channel(GREEN_SHIFT) or channel(0)
}

/** An RGB PNG of [width] × [height] whose pixel at (x, y) is [pixel]'s 0xRRGGBB. */
internal fun png(
    width: Int,
    height: Int,
    pixel: (Int, Int) -> Int,
): ByteArray {
    val header =
        ByteBuffer
            .allocate(IHDR_BYTES)
            .putInt(width)
            .putInt(height)
            .put(PNG_BIT_DEPTH.toByte())
            .put(PNG_COLOUR_RGB.toByte())
            .put(0)
            .put(0)
            .put(0)
            .array()
    return PNG_SIGNATURE + chunk("IHDR", header) + chunk("IDAT", deflated(width, height, pixel)) +
        chunk("IEND", ByteArray(0))
}

/** The image's rows, each after its filter byte (none), deflated as zlib data. */
private fun deflated(
    width: Int,
    height: Int,
    pixel: (Int, Int) -> Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    DeflaterOutputStream(out).use { zlib ->
        val row = ByteArray(1 + width * RGB_BYTES)
        repeat(height) { y -> zlib.write(fillRow(row, y, pixel)) }
    }
    return out.toByteArray()
}

/** [row] after its filter byte filled with line [y]'s pixels. */
private fun fillRow(
    row: ByteArray,
    y: Int,
    pixel: (Int, Int) -> Int,
): ByteArray {
    val width = (row.size - 1) / RGB_BYTES
    repeat(width) { x -> writePixel(row, 1 + x * RGB_BYTES, pixel(x, y)) }
    return row
}

private fun writePixel(
    row: ByteArray,
    at: Int,
    colour: Int,
) {
    row[at] = (colour shr RED_SHIFT and BYTE).toByte()
    row[at + 1] = (colour shr GREEN_SHIFT and BYTE).toByte()
    row[at + 2] = (colour and BYTE).toByte()
}

private fun chunk(
    type: String,
    data: ByteArray,
): ByteArray {
    val typed = type.encodeToByteArray() + data
    val crc = CRC32().also { it.update(typed) }.value.toInt()
    return ByteBuffer
        .allocate(CHUNK_OVERHEAD + data.size)
        .putInt(data.size)
        .put(typed)
        .putInt(crc)
        .array()
}

/**
 * The failed export's log: its pages read, a page a second, then the timeout, each line at its seconds since the
 * export started, as no time of day could agree with every time the demo's start gives its row.
 */
private fun exportLog(): ByteArray {
    val pages = (1..LOG_LINES).joinToString("\n") { "+$it s read page $it" }
    val end = "+$LOG_LINES s export timed out after $LOG_LINES s (the job's default); 0 rows written"
    return "+0 s nightly-export started\n$pages\n$end\n".encodeToByteArray()
}
