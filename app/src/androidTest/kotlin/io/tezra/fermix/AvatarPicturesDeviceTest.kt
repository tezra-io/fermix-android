package io.tezra.fermix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.EndlessProvider.TRICKLE_URI
import io.tezra.fermix.data.AVATAR_SIDE_PX
import io.tezra.fermix.data.AvatarPictures
import io.tezra.fermix.data.MAX_IMAGE_PIXELS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** The photo's raw size, as the camera wrote it before its EXIF says to turn it. */
private const val RAW_WIDTH = 400
private const val RAW_HEIGHT = 300

/** The bomb's side, in pixels: 400 megapixels, past the app's bound, in a file of some 50 KB. */
private const val BOMB_SIDE = 20_000

/** How long a refusal may take: it reads the image's header alone, never a pixel. */
private const val REFUSAL_MILLIS = 2_000L

/** How far a JPEG's channel may stray from the colour it was given. */
private const val JPEG_SLACK = 24

/** The JPEG quality the test's own photo is saved at. */
private const val CAMERA_QUALITY = 95

/** The time bound the trickle's write is given here, the app's own being a minute. */
private const val TRICKLE_WAIT_MILLIS = 1_000L

/** How long past its bound a write may take to give up: its hop back from the thread it waits on. */
private const val GIVE_UP_SLACK_MILLIS = 1_500L

/** The side the conversations' icon reads a picture at on a 2.625 density phone: its 72 dp. */
private const val ICON_SIDE = 189

/** A PNG's eight-byte signature, IHDR's length, and its fields past the size: one bit a pixel, grey. */
private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
private const val IHDR_BYTES = 13
private val ONE_BIT_GREY = byteArrayOf(1, 0, 0, 0, 0)

/** An instance's id, 64 hex digits. */
private val ID = "cd".repeat(32)

/**
 * The avatar's photo made on the phone's own ImageDecoder (the owner, 2026-10-10; android.jar's stubs have none, and
 * Robolectric's native decoder is not the phone's): a camera's photo that its EXIF says to turn comes out an upright
 * 256 square cut from its centre, a JPEG with no EXIF, so the photo's place and camera stay behind; a photo whose
 * header names more than the app's pixel bound is refused before a pixel is decoded, and the picture before stays; a
 * picture read back draws on a bitmap's own canvas, as the conversations' icon draws it; and a photo whose provider
 * trickles a byte at a time and never ends (the test APK's EndlessProvider, another app's) is given up at the write's
 * time bound, the picture before kept and no part of a write left. The decoder, which owns the stream, keeps reading
 * the trickle on its io thread until the provider ends it (the bound README names: the thread a stalled provider
 * holds), and what it makes then is put away, never renamed in. Each is read through the platform's ContentResolver,
 * as the Photo Picker's pick is.
 */
@RunWith(AndroidJUnit4::class)
class AvatarPicturesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "avatar-test")
    private val pictures = AvatarPictures(directory)
    private val made = mutableListOf<File>()

    @After
    fun cleanUp() {
        made.forEach { it.delete() }
        directory.deleteRecursively()
    }

    private fun source(file: File): ImageDecoder.Source =
        ImageDecoder.createSource(context.contentResolver, Uri.fromFile(file))

    @Test
    fun a_photo_its_exif_turns_is_made_an_upright_256_square_from_its_centre_with_no_exif() {
        val photo = turnedPhoto()
        runBlocking { pictures.write(ID, source(photo)) }
        val file = pictures.fileOf(ID)
        val square = checkNotNull(BitmapFactory.decodeFile(file.path)) { "the picture does not decode" }
        assertEquals(AVATAR_SIDE_PX to AVATAR_SIDE_PX, square.width to square.height)
        // Raw, the photo's top is green and its foot blue; turned a quarter clockwise, as its EXIF says, its left is
        // blue and its right green. The square from its centre keeps both halves.
        val middle = AVATAR_SIDE_PX / 2
        assertNear("the left, upright", Color.BLUE, square.getPixel(AVATAR_SIDE_PX / 4, middle))
        assertNear("the right, upright", Color.GREEN, square.getPixel(AVATAR_SIDE_PX * 3 / 4, middle))
        val exif = ExifInterface(file.path)
        assertNull("the GPS stayed behind", exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull("the camera stayed behind", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertTrue("no part is left", directory.list().orEmpty().all { it == file.name })
    }

    @Test
    fun a_photo_past_the_pixel_bound_is_refused_before_a_pixel_is_decoded_and_the_picture_before_stays() {
        runBlocking { pictures.write(ID, source(turnedPhoto())) }
        val before = pictures.fileOf(ID).readBytes()
        val bomb = File(context.cacheDir, "avatar-bomb.png").also { made += it }
        blackPng(bomb, BOMB_SIDE)
        assertTrue(
            "${BOMB_SIDE.toLong() * BOMB_SIDE} pixels are past the bound",
            BOMB_SIDE.toLong() * BOMB_SIDE > MAX_IMAGE_PIXELS,
        )
        val started = SystemClock.elapsedRealtime()
        val refusal = runCatching { runBlocking { pictures.write(ID, source(bomb)) } }.exceptionOrNull()
        val took = SystemClock.elapsedRealtime() - started
        assertTrue("the bomb was taken: $refusal", refusal is IOException)
        assertTrue("the refusal took $took ms, past $REFUSAL_MILLIS ms", took <= REFUSAL_MILLIS)
        assertArrayEquals(before, pictures.fileOf(ID).readBytes())
        assertEquals(listOf(pictures.fileOf(ID).name), directory.list().orEmpty().toList())
    }

    @Test
    fun a_photo_whose_provider_never_ends_is_given_up_at_the_bound_the_picture_before_kept_and_no_part_left() {
        val bounded = AvatarPictures(directory, waitMillis = TRICKLE_WAIT_MILLIS)
        runBlocking { bounded.write(ID, source(turnedPhoto())) }
        val before = bounded.fileOf(ID).readBytes()
        val trickle = ImageDecoder.createSource(context.contentResolver, TRICKLE_URI)
        val started = SystemClock.elapsedRealtime()
        val refusal = runCatching { runBlocking { bounded.write(ID, trickle) } }.exceptionOrNull()
        val took = SystemClock.elapsedRealtime() - started
        assertTrue("the trickle was taken: $refusal", refusal is IOException)
        assertTrue("given up after $took ms", took in TRICKLE_WAIT_MILLIS..TRICKLE_WAIT_MILLIS + GIVE_UP_SLACK_MILLIS)
        assertArrayEquals(before, bounded.fileOf(ID).readBytes())
        assertEquals(listOf(bounded.fileOf(ID).name), directory.list().orEmpty().toList())
    }

    @Test
    fun a_picture_read_back_draws_on_a_bitmaps_own_canvas_as_the_conversations_icon_draws_it() {
        runBlocking { pictures.write(ID, source(turnedPhoto())) }
        val read = checkNotNull(pictures.read(ID, ICON_SIDE)) { "no picture was read" }
        val icon = Bitmap.createBitmap(ICON_SIDE, ICON_SIDE, Bitmap.Config.ARGB_8888)
        try {
            assertNotEquals("a hardware bitmap throws on a bitmap's canvas", Bitmap.Config.HARDWARE, read.config)
            Canvas(icon).drawBitmap(read, 0f, 0f, null)
            assertNear("the icon's left", Color.BLUE, icon.getPixel(ICON_SIDE / 4, ICON_SIDE / 2))
            assertNear("the icon's right", Color.GREEN, icon.getPixel(ICON_SIDE * 3 / 4, ICON_SIDE / 2))
        } finally {
            icon.recycle()
            read.recycle()
        }
    }

    /**
     * A camera's photo as a JPEG file: [RAW_WIDTH] × [RAW_HEIGHT], its top half green and its foot blue, its EXIF
     * saying to turn it a quarter clockwise, and naming a place and a camera.
     */
    private fun turnedPhoto(): File {
        val bitmap = Bitmap.createBitmap(RAW_WIDTH, RAW_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.GREEN
        canvas.drawRect(0f, 0f, RAW_WIDTH.toFloat(), RAW_HEIGHT / 2f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, RAW_HEIGHT / 2f, RAW_WIDTH.toFloat(), RAW_HEIGHT.toFloat(), paint)
        val file = File(context.cacheDir, "avatar-photo.jpg").also { made += it }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, CAMERA_QUALITY, it)) }
        bitmap.recycle()
        val exif = ExifInterface(file.path)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        exif.setAttribute(ExifInterface.TAG_MAKE, "Test camera")
        exif.saveAttributes()
        return file
    }
}

/** [actual]'s channels each within [JPEG_SLACK] of [expected]'s, or [what] fails. */
private fun assertNear(
    what: String,
    expected: Int,
    actual: Int,
) {
    val near =
        listOf(Color::red, Color::green, Color::blue).all { channel ->
            kotlin.math.abs(channel(expected) - channel(actual)) <= JPEG_SLACK
        }
    assertTrue("$what is ${Integer.toHexString(actual)}, not ${Integer.toHexString(expected)}", near)
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
