package io.tezra.fermix.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The test photo's size, a 4:3 frame. */
private const val PHOTO_WIDTH = 64
private const val PHOTO_HEIGHT = 48
private const val PHOTO_QUALITY = 90

/** A 12 MP camera's 4:3 frame, past the long-edge cap, and the size it goes up at. */
private const val LARGE_WIDTH = 4_000
private const val LARGE_HEIGHT = 3_000
private const val CAPPED_HEIGHT = 1_536

/** Where the photo says it was taken, as EXIF's rationals write it, and the camera its EXIF names. */
private const val LATITUDE = "37/1,25/1,1884/100"
private const val LONGITUDE = "122/1,5/1,240/100"
private const val MAKE = "FermixCam"

/**
 * The phone's image pipeline on a device (design section 8.5, "Images"): a photo with GPS and a camera's EXIF
 * goes as a JPEG with none of it; one past the long-edge cap goes at it; sent as a file, it goes as its own
 * bytes, EXIF and all, as the owner chose.
 */
class MediaPipelineDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pipeline = PhoneMedia(context, { message, error -> Log.w("MediaPipelineDeviceTest", message, error) })
    private val directory = File(context.cacheDir, "pipeline-test")

    @Before
    fun clean() {
        directory.deleteRecursively()
        check(directory.mkdirs()) { "$directory could not be made" }
    }

    @After
    fun end() {
        directory.deleteRecursively()
    }

    /** A [width] × [height] JPEG taken where [LATITUDE] north and [LONGITUDE] west say, by the camera [MAKE] names. */
    private fun photoWithExif(
        width: Int = PHOTO_WIDTH,
        height: Int = PHOTO_HEIGHT,
    ): File {
        val photo = File(directory, "IMG_2041.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(92, 139, 163))
            photo.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it)) }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, LATITUDE)
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, LONGITUDE)
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_MAKE, MAKE)
            saveAttributes()
        }
        return photo
    }

    private fun picked(photo: File): Picked =
        Picked(
            "p1",
            Uri.fromFile(photo).toString(),
            PickedKind.IMAGE,
            "image/jpeg",
            photo.name,
            photo.length(),
            PickedFrom.PHOTOS,
        )

    @Test
    fun an_image_goes_as_a_jpeg_with_no_exif_and_no_gps() {
        val photo = photoWithExif()
        val before = ExifInterface(photo.path)
        assertTrue("the photo holds its GPS", before.getLatLong(FloatArray(2)))
        assertEquals(MAKE, before.getAttribute(ExifInterface.TAG_MAKE))
        val into = File(directory, "prepared")
        val prepared = runBlocking { pipeline.prepare(picked(photo), asFile = false, into) }
        assertEquals(Prepared("image/jpeg", "IMG_2041.jpg"), prepared)
        val after = ExifInterface(into.path)
        assertFalse("no GPS", after.getLatLong(FloatArray(2)))
        assertNull("no latitude", after.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull("no camera", after.getAttribute(ExifInterface.TAG_MAKE))
        val decoded = checkNotNull(BitmapFactory.decodeFile(into.path)) { "the JPEG decodes" }
        assertEquals(PHOTO_WIDTH to PHOTO_HEIGHT, decoded.width to decoded.height)
        decoded.recycle()
    }

    @Test
    fun an_image_past_the_long_edge_cap_goes_at_it_with_its_aspect_kept() {
        val photo = photoWithExif(LARGE_WIDTH, LARGE_HEIGHT)
        val into = File(directory, "prepared")
        runBlocking { pipeline.prepare(picked(photo), asFile = false, into) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(into.path, bounds)
        assertEquals(LONG_EDGE_PX to CAPPED_HEIGHT, bounds.outWidth to bounds.outHeight)
    }

    @Test
    fun sent_as_a_file_an_image_goes_as_its_own_bytes() {
        val photo = photoWithExif()
        val into = File(directory, "prepared")
        val prepared = runBlocking { pipeline.prepare(picked(photo), asFile = true, into) }
        assertEquals(Prepared("image/jpeg", "IMG_2041.jpg"), prepared)
        assertArrayEquals(photo.readBytes(), into.readBytes())
    }
}
