package io.tezra.fermix.onboarding

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.PairBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FRAME = "frame"

/** xhdpi: two pixels a dp. */
private const val PX_PER_DP = 2

/** From the copy button's centre: its 48 dp target's edge, where the command card's edge is too, in dp. */
private const val TARGET_RADIUS = 24

/**
 * Pair's copy button, focused with the window out of touch mode, in light mode: its ring is dark mode's ink, which
 * reads on the command card (13.5 : 1) and not on light mode's canvas (1.3 : 1), so it lies on the card alone, 2 dp
 * outside the button's 40 dp disc, inside its 48 dp target, which is as tall as the card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class PairRingTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    @Test
    fun `the copy button's ring lies on the command card alone, none of it on light mode's canvas`() {
        rule.setContent {
            FermixTheme(darkTheme = false) {
                CompositionLocalProvider(LocalInputModeManager provides KeyboardMode) {
                    Box(Modifier.testTag(FRAME).fillMaxSize().background(FermixColors.Light.canvas)) {
                        PairAt(
                            deviceName = "Pixel 9",
                            actions = PairActions({}, {}, {}, {}, {}),
                            build = { PairBuild.CLOCK_MILLIS.toFloat() },
                            check = { 0f },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val copy = rule.activity.getString(R.string.onboarding_copy)
        rule.onNodeWithContentDescription(copy).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val centre =
            rule
                .onNodeWithContentDescription(copy)
                .fetchSemanticsNode()
                .boundsInRoot.center
        val image: Bitmap = rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()
        val x = centre.x.toInt()
        val y = centre.y.toInt()
        val ring = FermixColors.Dark.ink.toArgb()
        // The stroke 22 to 24 dp from the centre, above the disc, at its end, below it.
        assertEquals(ring, image.getPixel(x, y - 23 * PX_PER_DP))
        assertEquals(ring, image.getPixel(x + 23 * PX_PER_DP, y))
        assertEquals(ring, image.getPixel(x, y + 23 * PX_PER_DP))
        // Past the card's top and bottom, the canvas, with no ring on it.
        for (out in 1..2 * TARGET_RADIUS / 3) {
            val above = image.getPixel(x, y - TARGET_RADIUS * PX_PER_DP - out)
            val below = image.getPixel(x, y + TARGET_RADIUS * PX_PER_DP + out)
            assertNotEquals("$out px above the card", ring, above)
            assertNotEquals("$out px below the card", ring, below)
        }
    }
}

/** The window's input mode, held out of touch mode, as a d-pad or a keyboard leaves it. */
private object KeyboardMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
}
