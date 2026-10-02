package io.tezra.fermix.onboarding

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.tezra.fermix.design.FermixTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Long past the code's last digit landing: six digits 40 ms apart, each on a spring. */
private const val SETTLED_MILLIS = 2_000L

private const val SCALE = "animator_duration_scale"

/**
 * Verify under Android's "Remove animations", set as the owner sets it (design sections 13.1 and 13.8): the
 * code stands still from its first frame, where with animations on its digits land one after another. The
 * device's setting is put back after each test.
 */
class ReduceMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var kept = ""

    @Before
    fun keepScale() {
        kept = shell("settings get global $SCALE").trim()
    }

    @After
    fun restoreScale() {
        if (kept == "null") shell("settings delete global $SCALE") else shell("settings put global $SCALE $kept")
    }

    /** The code at Verify's first frame and once settled, at the animator duration scale [scale]. */
    private fun frames(scale: String): Pair<Bitmap, Bitmap> {
        shell("settings put global $SCALE $scale")
        rule.mainClock.autoAdvance = false
        rule.setContent { FermixTheme { VerifyScreen(VerifyUi(TEST_SAS, 102, PHONE), onCancel = {}) } }
        rule.mainClock.advanceTimeByFrame()
        val code = rule.onNodeWithContentDescription(SPOKEN_TEST_SAS)
        val first = code.captureToImage().asAndroidBitmap()
        rule.mainClock.advanceTimeBy(SETTLED_MILLIS)
        return first to code.captureToImage().asAndroidBitmap()
    }

    @Test
    fun with_animations_on_the_digits_land_one_after_another() {
        val (first, settled) = frames(scale = "1")
        assertFalse("the code's first frame is already the code", first.sameAs(settled))
    }

    @Test
    fun with_animations_removed_the_code_stands_still_from_its_first_frame() {
        val (first, settled) = frames(scale = "0")
        assertTrue("the digits moved under reduce-motion", first.sameAs(settled))
    }
}
