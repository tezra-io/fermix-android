package io.tezra.fermix.design

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val MARK = "mark"

/**
 * The two-dot mark's one-off motions (design section 13.10, item 1): Welcome's dots assemble and Paired's
 * merge once, and the screen drawn again from its saved state, after a rotation or a fold or on the way
 * back from Name, shows them where they arrived, as the SAS's digits land once. The screen leaves and comes
 * back under a SaveableStateHolder, as Navigation 3 keeps an entry's state; Robolectric's native graphics
 * draw the frames.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TwoDotMarkTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private fun drawn(): Bitmap = rule.onNodeWithTag(MARK).captureToImage().asAndroidBitmap()

    /** [motion] played to its end, the screen gone and drawn again: its first frame is the end. */
    private fun assertArrivesOnce(motion: MarkMotion) {
        var shown by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val saved = rememberSaveableStateHolder()
            FermixTheme {
                if (shown) saved.SaveableStateProvider(MARK) { TwoDotMark(motion, Modifier.testTag(MARK)) }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        val moving = drawn()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val arrived = drawn()
        // The control: the motion is drawn, so a replay would show.
        assertFalse("$motion did not move", moving.sameAs(arrived))
        shown = false
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        shown = true
        // The change reaches the composition, and one frame draws the mark again: a replay would have only begun.
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        assertTrue("$motion played again", arrived.sameAs(drawn()))
    }

    @Test
    fun `Welcome's dots assemble once, and drawn again they stand together`() = assertArrivesOnce(MarkMotion.ASSEMBLE)

    @Test
    fun `Paired's dots merge once, and drawn again they stand merged`() = assertArrivesOnce(MarkMotion.MERGE)
}
