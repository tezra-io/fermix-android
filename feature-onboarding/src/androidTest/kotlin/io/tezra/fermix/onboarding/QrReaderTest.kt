package io.tezra.fermix.onboarding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import zxingcpp.BarcodeReader
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/**
 * A camera frame of [bitmap]'s luminance, upright: YUV_420_888 with its Y plane alone, which is all zxing-cpp
 * reads of a frame, as the analysis hands it one. [closed] says whether the analysis closed it.
 */
private class Frame(
    private val bitmap: Bitmap,
) : ImageProxy {
    var closed = false

    private val luminance: ByteBuffer =
        ByteBuffer.allocateDirect(bitmap.width * bitmap.height).apply {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (pixel in pixels) put(((Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3).toByte())
            rewind()
        }

    override fun close() {
        closed = true
    }

    override fun getCropRect(): Rect = Rect(0, 0, bitmap.width, bitmap.height)

    override fun setCropRect(rect: Rect?): Unit = error("the analysis never crops a frame")

    override fun getFormat(): Int = ImageFormat.YUV_420_888

    override fun getHeight(): Int = bitmap.height

    override fun getWidth(): Int = bitmap.width

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> =
        arrayOf(
            object : ImageProxy.PlaneProxy {
                override fun getRowStride(): Int = bitmap.width

                override fun getPixelStride(): Int = 1

                override fun getBuffer(): ByteBuffer = luminance
            },
        )

    // ImageInfo's other members are CameraX's own, typed with its internals: a proxy answers the rotation alone.
    override fun getImageInfo(): ImageInfo =
        Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
            check(method.name == "getRotationDegrees") { "the analysis asked a frame's info for ${method.name}" }
            0
        } as ImageInfo

    @ExperimentalGetImage
    override fun getImage(): Image? = null
}

/**
 * The scan's reader on the device, the analysis's own [qrReader], through zxing-cpp's native library as the
 * APK packs it. The codes in assets/codes were drawn by zxing-cpp's own writer (its Python binding), 4 px a
 * module with the quiet zone: [linkText] as a QR code dark on light, the same inverted as a dark terminal
 * draws `fermix pair`'s, and the same link as a Data Matrix; and "FERMIX" as a Micro QR code, of the QR
 * family but not the model `fermix pair` draws. The scan must take neither of the last two, read alone or
 * as the analysis reads a camera's frame ([qrAnalyzer]).
 */
@RunWith(AndroidJUnit4::class)
class QrReaderTest {
    private fun code(name: String): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val decoded = assets.open("codes/$name").use { BitmapFactory.decodeStream(it) }
        return checkNotNull(decoded) { "codes/$name is not an image" }
    }

    private fun read(name: String): List<String?> = qrReader().read(code(name)).map { it.text }

    @Test
    fun the_scans_reader_takes_qr_codes_of_model_2_alone() {
        assertEquals(setOf(BarcodeReader.Format.QR_CODE_MODEL_2), qrReader().options.formats)
    }

    @Test
    fun a_pairing_code_reads_as_its_link() {
        val read = read("pairing_qr.png")
        assertEquals(listOf(linkText()), read)
        assertTrue(readLink(read.single().orEmpty()) is LinkOutcome.Link)
    }

    @Test
    fun a_dark_terminals_code_light_on_dark_reads_too() {
        assertEquals(listOf(linkText()), read("pairing_qr_dark_terminal.png"))
    }

    @Test
    fun the_same_link_as_a_data_matrix_reads_as_nothing() {
        assertEquals(emptyList<String?>(), read("pairing_datamatrix.png"))
    }

    @Test
    fun a_micro_qr_code_which_the_whole_qr_family_reads_reads_as_nothing() {
        val family = BarcodeReader(BarcodeReader.Options(formats = setOf(BarcodeReader.Format.QR_CODE)))
        assertEquals(listOf("FERMIX"), family.read(code("micro_qr.png")).map { it.text })
        assertEquals(emptyList<String?>(), read("micro_qr.png"))
    }

    @Test
    fun the_analysis_reads_a_frames_pairing_code_and_no_other_kind_and_closes_each_frame() {
        val read = mutableListOf<String>()
        val analyzer = qrAnalyzer { read += it }
        val frames = listOf("pairing_datamatrix.png", "micro_qr.png", "pairing_qr.png").map { Frame(code(it)) }
        for (frame in frames) analyzer.analyze(frame)
        assertEquals(listOf(linkText()), read)
        assertEquals(listOf(true, true, true), frames.map { it.closed })
    }
}
