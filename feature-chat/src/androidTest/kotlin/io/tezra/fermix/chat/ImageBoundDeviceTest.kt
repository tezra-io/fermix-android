package io.tezra.fermix.chat

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** The bomb's side, in pixels: 400 megapixels, past the app's bound, in a file of some 50 KB. */
private const val SIDE = 20_000

/** How long a refusal may take: it reads the image's header alone, never a pixel. */
private const val REFUSAL_MILLIS = 2_000L

/** A PNG's eight-byte signature. */
private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

/** IHDR's length: its width and height, four bytes each, then its five one-byte fields. */
private const val IHDR_BYTES = 13

/** IHDR's fields past the size: one bit a pixel, grey, deflate, no filter method of its own, not interlaced. */
private val ONE_BIT_GREY = byteArrayOf(1, 0, 0, 0, 0)

/**
 * An image another app lands whose few bytes decode to a huge bitmap, as a paste's, the keyboard's or a share's copy
 * the chat made under its cache: the tray draws no thumbnail of it and Send makes no JPEG of it, each refused as the
 * image's header names its size, past [MAX_IMAGE_PIXELS], before a pixel is decoded, so neither holds a thread for
 * as long as the other app's pixels would take.
 */
class ImageBoundDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun an_image_past_the_apps_pixel_bound_draws_no_thumbnail_and_makes_no_jpeg_and_neither_decodes_it() {
        val png = File(context.cacheDir, "landed-bomb.png")
        val jpeg = File(context.cacheDir, "landed-bomb.jpg")
        try {
            blackPng(png, SIDE)
            val uri = png.toURI().toString()
            val picked = Picked(uri, uri, PickedKind.IMAGE, "image/png", png.name, png.length(), PickedFrom.SHARE)
            assertTrue("${SIDE.toLong() * SIDE} pixels are past the bound", SIDE.toLong() * SIDE > MAX_IMAGE_PIXELS)
            val drawing = SystemClock.elapsedRealtime()
            val thumbnail = runBlocking { trayThumbnail(context, picked) }
            assertNull("the tray drew a thumbnail of the bomb", thumbnail)
            assertWithin("the tray's refusal", drawing)
            val making = SystemClock.elapsedRealtime()
            val pipeline = PhoneMedia(context, { _, _ -> })
            val made = runCatching { runBlocking { pipeline.prepare(picked, false, jpeg, SEND_LIMIT_BYTES) } }
            assertTrue("Send made a JPEG of the bomb: $made", made.exceptionOrNull() is IOException)
            assertWithin("Send's refusal", making)
        } finally {
            png.delete()
            jpeg.delete()
        }
    }

    private fun assertWithin(
        what: String,
        started: Long,
    ) {
        val took = SystemClock.elapsedRealtime() - started
        assertTrue("$what took $took ms, past $REFUSAL_MILLIS ms", took <= REFUSAL_MILLIS)
    }
}

/** A black PNG of [side]×[side] one-bit grey pixels into [into]: one IDAT of rows of zeros, deflated. */
private fun blackPng(
    into: File,
    side: Int,
) {
    val header = ByteBuffer.allocate(IHDR_BYTES)
    header.putInt(side)
    header.putInt(side)
    header.put(ONE_BIT_GREY)
    DataOutputStream(into.outputStream().buffered()).use { out ->
        out.write(PNG_SIGNATURE)
        chunk(out, "IHDR", header.array())
        chunk(out, "IDAT", zeroRows(side))
        chunk(out, "IEND", ByteArray(0))
    }
}

/** [side] rows of [side] one-bit pixels, each row its filter byte and its zeros, as one zlib stream. */
private fun zeroRows(side: Int): ByteArray {
    val row = ByteArray(1 + (side + 7) / 8)
    val deflated = ByteArrayOutputStream()
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    try {
        DeflaterOutputStream(deflated, deflater).use { out -> repeat(side) { out.write(row) } }
    } finally {
        deflater.end()
    }
    return deflated.toByteArray()
}

/** One PNG chunk: its length, its [type], its [data] and the CRC of the type and the data. */
private fun chunk(
    out: DataOutputStream,
    type: String,
    data: ByteArray,
) {
    val named = type.encodeToByteArray()
    val crc = CRC32()
    crc.update(named)
    crc.update(data)
    out.writeInt(data.size)
    out.write(named)
    out.write(data)
    out.writeInt(crc.value.toInt())
}
