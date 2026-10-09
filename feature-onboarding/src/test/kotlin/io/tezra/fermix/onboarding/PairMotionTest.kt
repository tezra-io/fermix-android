package io.tezra.fermix.onboarding

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.CopyCheck
import io.tezra.fermix.design.PairBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val DIAGRAM = "diagram"

/** The diagram's parts along its row, as the canon spaces them: their start and end, in dp. */
private val THE_PHONE = 0.dp to 40.dp
private val FIRST_LINK = 50.dp to 86.dp
private val THE_LOCK = 96.dp to 120.dp
private val SECOND_LINK = 130.dp to 166.dp
private val THE_COMPUTER = 176.dp to 216.dp

/**
 * Pair's motion on a test clock (the M51 update's 7.4): the diagram builds once, the phone, then the computer, the
 * link drawing from the one to the other, the lock popping last, and stands built after a rotation, on the way back
 * from Scan and under Remove animations; Copy shows its check for 1,500 ms, fading 200 ms either way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class PairMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val rig = MotionRig(rule)
    private var build: () -> Float = { 0f }

    @Test
    fun `the diagram's clock runs once to 900 ms, and stands there after a rotation or the way back from Scan`() {
        rig.show { build = rememberPairBuild() }
        assertEquals(0f, build())
        rig.advanceTo(PairBuild.CLOCK_MILLIS + 16L)
        assertEquals(PairBuild.CLOCK_MILLIS.toFloat(), build())
        assertFalse(rig.framesAsked())
        // The back stack keeps Pair's saved state under Scan, as a rotation does.
        rig.restore()
        assertEquals(PairBuild.CLOCK_MILLIS.toFloat(), build())
    }

    @Test
    fun `a rotation or the way back from Scan mid-build, at 500 ms, finds the diagram built`() {
        rig.show { build = rememberPairBuild() }
        rig.advanceTo(500L)
        assertTrue("built to ${build()}", build() in 400f..600f)
        rig.restore()
        assertEquals(PairBuild.CLOCK_MILLIS.toFloat(), build())
        rig.frames(10)
        assertEquals(PairBuild.CLOCK_MILLIS.toFloat(), build())
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `under Remove animations the diagram stands built from the first frame`() {
        rig.show(scale = 0f) { build = rememberPairBuild() }
        assertEquals(PairBuild.CLOCK_MILLIS.toFloat(), build())
    }

    private var at by mutableFloatStateOf(0f)

    private fun image(ms: Float): Bitmap {
        at = ms
        rig.frameAfterWrite()
        return rule.onNodeWithTag(DIAGRAM).captureToImage().asAndroidBitmap()
    }

    /** Whether anything is drawn in [part] of the row, against the row with nothing drawn, [blank]. */
    private fun Bitmap.drawn(
        part: Pair<Dp, Dp>,
        blank: Bitmap,
    ): Boolean {
        val from = with(rule.density) { part.first.roundToPx() }
        val to = with(rule.density) { part.second.roundToPx() }
        return (from until to).any { x -> (0 until height).any { y -> getPixel(x, y) != blank.getPixel(x, y) } }
    }

    @Test
    fun `the phone comes first, then the computer, the link draws from the one to the other, and the lock pops last`() {
        rig.show { Box(modifier = Modifier.testTag(DIAGRAM)) { PairDiagram(build = { at }) } }
        val blank = image(0f)
        val parts = listOf(THE_PHONE, FIRST_LINK, THE_LOCK, SECOND_LINK, THE_COMPUTER)
        val expected =
            mapOf(
                60f to listOf(true, false, false, false, false),
                200f to listOf(true, false, false, false, true),
                420f to listOf(true, true, false, true, true),
                700f to listOf(true, true, true, true, true),
            )
        for ((ms, drawn) in expected) {
            val now = image(ms)
            assertEquals("at $ms ms", drawn, parts.map { now.drawn(it, blank) })
        }
    }

    private var copies by mutableIntStateOf(0)
    private var check: () -> Float = { 0f }

    @Test
    fun `Copy's check fades in over 200 ms, stands until 1,500 ms and fades back out over 200 ms`() {
        rig.show { check = rememberCopyCheck(copies) }
        assertEquals(0f, check())
        copies++
        rig.frameAfterWrite()
        val start = rig.at
        rig.advanceTo(start + CopyCheck.FADE_MILLIS / 2L)
        assertTrue("the check at ${check()}", check() in 0.1f..0.9f)
        rig.advanceTo(start + CopyCheck.FADE_MILLIS + 16L)
        assertEquals(1f, check())
        rig.advanceTo(start + CopyCheck.SHOWN_MILLIS - 16L)
        assertEquals(1f, check())
        rig.advanceTo(start + CopyCheck.SHOWN_MILLIS + CopyCheck.FADE_MILLIS / 2L)
        assertTrue("the check at ${check()}", check() in 0.1f..0.9f)
        rig.advanceTo(start + CopyCheck.SHOWN_MILLIS + CopyCheck.FADE_MILLIS + 32L)
        assertEquals(0f, check())
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `under Remove animations Copy's check stands 1,500 ms with no fade`() {
        rig.show(scale = 0f) { check = rememberCopyCheck(copies) }
        copies++
        rig.frameAfterWrite()
        val start = rig.at
        rig.frames(1)
        assertEquals(1f, check())
        rig.advanceTo(start + CopyCheck.SHOWN_MILLIS - 16L)
        assertEquals(1f, check())
        rig.advanceTo(start + CopyCheck.SHOWN_MILLIS + 16L)
        assertEquals(0f, check())
    }
}
