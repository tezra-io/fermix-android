package io.tezra.fermix.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zxingcpp.BarcodeReader

/**
 * The scan's reader takes QR codes of Model 2, the model `fermix pair` draws, and nothing else (design
 * section 12.1), dark on light and inverted, as a dark terminal draws `fermix pair`'s. zxing-cpp's QR_CODE
 * is the whole family, Micro QR and rMQR among it, each with a detector of its own run on every frame. The
 * options are plain data; the instrumented QrReaderTest reads real codes through the analyzer's own
 * [qrReader], and camera frames of them through [qrAnalyzer], as a JVM cannot load the native library.
 */
class QrReaderOptionsTest {
    @Test
    fun `the reader takes QR codes of Model 2 alone`() {
        assertEquals(setOf(BarcodeReader.Format.QR_CODE_MODEL_2), qrReaderOptions().formats)
    }

    @Test
    fun `the reader tries an inverted code, and tries harder than the binding's default`() {
        val options = qrReaderOptions()
        assertTrue(options.tryInvert, "a dark terminal's code is light on dark")
        assertTrue(options.tryHarder, "the quick default misses a code seen at an angle")
    }
}
