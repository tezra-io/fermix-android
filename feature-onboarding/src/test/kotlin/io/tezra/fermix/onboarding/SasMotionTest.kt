package io.tezra.fermix.onboarding

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val CODE = "code"

/** Long past the last digit landing: six digits 40 ms apart, each on a spring. */
private const val SETTLED_MILLIS = 2_000L

/**
 * The SAS under Android's "Remove animations" (design sections 13.1 and 13.8): the code stands still from
 * its first frame, where with motion on its digits land one after another. Robolectric's native graphics
 * draw the frames, as for the two-dot mark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class SasMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private fun drawn(): Bitmap = rule.onNodeWithTag(CODE).captureToImage().asAndroidBitmap()

    /** The code at its first frame and once settled, at the animator duration scale [scale]. */
    private fun frames(scale: Float): Pair<Bitmap, Bitmap> {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        rule.mainClock.autoAdvance = false
        rule.setContent { FermixTheme { SasCode(PREVIEW_SAS, Modifier.testTag(CODE)) } }
        rule.mainClock.advanceTimeByFrame()
        val first = drawn()
        rule.mainClock.advanceTimeBy(SETTLED_MILLIS)
        return first to drawn()
    }

    @Test
    fun `with motion on, the digits land one after another`() {
        val (first, settled) = frames(scale = 1f)
        assertFalse("the code's first frame is already the code", first.sameAs(settled))
    }

    @Test
    fun `with animations removed, the code's first frame is the code`() {
        val (first, settled) = frames(scale = 0f)
        assertTrue("the digits moved under reduce-motion", first.sameAs(settled))
    }
}
