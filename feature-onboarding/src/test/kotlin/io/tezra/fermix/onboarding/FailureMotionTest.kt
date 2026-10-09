package io.tezra.fermix.onboarding

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FailureEntrance
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.failureDiscAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The haptic a refusal plays: `REJECT`. */
private const val REJECT = HapticFeedbackConstants.REJECT

/**
 * A failure's motion on a test clock (the M51 update's 7.4): the disc settles from 94 % between 90 and 350 ms, and a
 * refusal's `REJECT` plays as it lands, at 200 ms, once; the security event has no scale, plays its `REJECT` at once,
 * and its red edge draws across from the start side between 100 and 400 ms. Under Remove animations the screen stands
 * at its end and the haptic plays at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class FailureMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val rig = MotionRig(rule)
    private var entrance: () -> Float = { 0f }

    @Test
    fun `the entrance's clock runs once to 400 ms, and stands there after a rotation`() {
        rig.show { entrance = rememberFailureEntrance(FailureCase.EXPIRED) }
        assertEquals(0f, entrance())
        rig.advanceTo(FailureEntrance.CLOCK_MILLIS + 16L)
        assertEquals(FailureEntrance.CLOCK_MILLIS.toFloat(), entrance())
        assertFalse(rig.framesAsked())
        rig.restore()
        assertEquals(FailureEntrance.CLOCK_MILLIS.toFloat(), entrance())
    }

    @Test
    fun `a rotation mid-entrance stands the screen entered, the security edge drawn whole`() {
        rig.show { entrance = rememberFailureEntrance(FailureCase.WRONG_MACHINE) }
        rig.advanceTo(250L)
        assertTrue("entered to ${entrance()}", entrance() in 200f..300f)
        rig.restore()
        assertEquals(FailureEntrance.CLOCK_MILLIS.toFloat(), entrance())
        rig.frames(10)
        assertEquals(FailureEntrance.CLOCK_MILLIS.toFloat(), entrance())
        assertFalse(rig.framesAsked())
        assertEquals(1, rig.played().size)
    }

    @Test
    fun `a refusal rotated before its disc lands settles it at once, and plays its REJECT then, once`() {
        rig.show { entrance = rememberFailureEntrance(FailureCase.DENIED) }
        rig.advanceTo(96L)
        assertEquals(emptyList<Pair<Int, Long>>(), rig.played())
        rig.restore()
        assertEquals(FailureEntrance.CLOCK_MILLIS.toFloat(), entrance())
        rig.frames(30)
        val played = rig.played()
        assertEquals(listOf(REJECT), played.map { it.first })
        assertTrue("played at ${played.single().second} ms", played.single().second <= 144L)
    }

    @Test
    fun `the disc settles from 94 percent, and the security event's has no scale`() {
        for (ms in listOf(0f, 90f, 200f, 350f, 400f)) {
            assertEquals(failureDiscAt(ms), discScale(FailureCase.DENIED, ms))
            assertEquals(1f, discScale(FailureCase.WRONG_MACHINE, ms))
        }
        assertEquals(0.94f, discScale(FailureCase.EXPIRED, 0f))
    }

    @Test
    fun `a refusal's REJECT plays as the disc lands, at 200 ms, once`() {
        rig.show { FailureScreen(FailureCase.DENIED, HOST, onAction = {}) }
        rig.advanceTo(FailureEntrance.CLOCK_MILLIS + 100L)
        val played = rig.played()
        assertEquals(listOf(REJECT), played.map { it.first })
        assertTrue("played at ${played.single().second} ms", played.single().second in 200L..216L)
        rig.restore()
        rig.frames(40)
        assertEquals(1, rig.played().size)
    }

    @Test
    fun `the security event plays its REJECT at once, as today`() {
        rig.show { FailureScreen(FailureCase.WRONG_MACHINE, HOST, onAction = {}) }
        rig.frames(20)
        val played = rig.played()
        assertEquals(listOf(REJECT), played.map { it.first })
        assertTrue("played at ${played.single().second} ms", played.single().second <= 16L)
    }

    @Test
    fun `under Remove animations a refusal's REJECT plays at once`() {
        rig.show(scale = 0f) { FailureScreen(FailureCase.DENIED, HOST, onAction = {}) }
        rig.frames(2)
        val played = rig.played()
        assertEquals(listOf(REJECT), played.map { it.first })
        assertTrue("played at ${played.single().second} ms", played.single().second <= 16L)
    }

    private var at by mutableFloatStateOf(0f)

    /** Whether the edge's red is drawn at [share] of the window's width, anywhere along the top tenth. */
    private fun redAt(share: Float): Boolean {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val red = FermixColors.Light.err.toArgb()
        val x = (image.width * share).toInt()
        return (0 until image.height / 10).any { y -> image.getPixel(x, y) == red }
    }

    private fun showEdge(direction: LayoutDirection) =
        rig.show {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                FailureAt(FailureCase.WRONG_MACHINE, HOST, onAction = {}, entrance = { at })
            }
        }

    @Test
    fun `the security event's red edge draws across from the start side between 100 and 400 ms`() {
        showEdge(LayoutDirection.Ltr)
        assertFalse(redAt(0.05f))
        // 20 ms into the edge's 300, emphasized decelerate has drawn about half of it.
        at = 120f
        rig.frameAfterWrite()
        assertTrue(redAt(0.05f))
        assertFalse(redAt(0.95f))
        at = 400f
        rig.frameAfterWrite()
        assertTrue(redAt(0.05f) && redAt(0.95f))
    }

    @Test
    fun `right to left, the edge draws from the right`() {
        showEdge(LayoutDirection.Rtl)
        at = 120f
        rig.frameAfterWrite()
        assertTrue(redAt(0.95f))
        assertFalse(redAt(0.05f))
    }
}
