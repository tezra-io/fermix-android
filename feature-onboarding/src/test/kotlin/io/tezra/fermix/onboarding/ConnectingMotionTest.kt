package io.tezra.fermix.onboarding

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.MarkEasing
import io.tezra.fermix.design.MarkPose
import io.tezra.fermix.design.Narrow
import io.tezra.fermix.design.Search
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** How near the eyes' place has to be to where the table has them, in the mark's units, with frames 16 ms apart. */
private const val NEAR = 0.1f

private const val REACHING_LINE = "Reaching suj-mbp…"
private const val CHECKING_LINE = "Checking it's really your machine…"

/** The steps' row, tagged for its picture. */
private const val STEPS = "steps"

/** A pixel's alpha past which the step's fill counts as drawn there. */
private const val DRAWN_ALPHA = 128

/** How near the line's opacity read off a frame is to the table's: its ink's 8 bits, and a little more. */
private const val LINE_OPACITY = 0.02f

/** A pixel at xhdpi, and a little more: how near a line's place is to its curve's. */
private val A_PIXEL = 0.6.dp

/** A host name long enough to make Reaching's line the longest, two lines at twice the font size. */
private const val LONG_HOST = "build-server-under-the-stairs"

/**
 * Connecting's motion on a test clock (the M51 update's 7.4): the eyes sweep while it reaches and tries Tailscale, come
 * back and narrow at Checking, open and blink once at Securing, and hold still at the centre under Remove animations;
 * the search asks for no frames once its work is over or its screen has left. The line changes by a vertical fade
 * through, or simply changes under Remove animations, in a box as tall as its tallest line, so the mark holds still;
 * the current step is a 20 dp pill.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ConnectingMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val rig = MotionRig(rule)
    private var phase by mutableStateOf(ConnectingPhase.REACHING)
    private var eyes: () -> MarkPose = { MarkPose.Rest }

    private fun showEyes(scale: Float = 1f) = rig.show(scale) { eyes = rememberConnectingEyes(phase) }

    private fun to(next: ConnectingPhase) {
        phase = next
        rig.frameAfterWrite()
    }

    private fun assertNear(
        expected: Float,
        actual: Float,
    ) = assertTrue("$actual at ${rig.at} ms, not $expected", abs(expected - actual) < NEAR)

    @Test
    fun `the eyes sweep 2-6 units each way, 380 ms a sweep, through Reaching and on through Trying Tailscale`() {
        showEyes()
        assertEquals(0f, eyes().eyeX)
        // The search's first frame is the screen's second, so its sweeps end a frame after the table's.
        rig.advanceTo(Search.SWEEP_MILLIS + 16L)
        assertNear(-Search.REACH, eyes().eyeX)
        rig.advanceTo(2L * Search.SWEEP_MILLIS + 16L)
        assertNear(Search.REACH, eyes().eyeX)
        val before = eyes().eyeX
        to(ConnectingPhase.TRYING_TAILSCALE)
        assertTrue("the search went on from ${eyes().eyeX}, not $before", abs(eyes().eyeX - before) < NEAR)
        rig.advanceTo(3L * Search.SWEEP_MILLIS + 16L)
        assertNear(-Search.REACH, eyes().eyeX)
        assertEquals(1f, eyes().squint)
    }

    @Test
    fun `at Checking the eyes come back from where they were to the centre and narrow to 72 percent, in 300 ms`() {
        showEyes()
        rig.advanceTo(Search.SWEEP_MILLIS / 2L)
        val from = eyes().eyeX
        assertTrue("the eyes at $from mid-sweep", from < -1f)
        to(ConnectingPhase.CHECKING)
        assertTrue("the eyes jumped to ${eyes().eyeX}", eyes().eyeX < -1f)
        rig.advanceTo(rig.at + Narrow.MILLIS / 2L)
        val mid = eyes()
        assertTrue("mid-way at ${mid.eyeX}, ${mid.squint}", mid.eyeX in from..0f && mid.squint in Narrow.SQUINT..1f)
        rig.advanceTo(rig.at + Narrow.MILLIS)
        assertEquals(MarkPose(squint = Narrow.SQUINT), eyes())
        // The search is over, and nothing else here moves.
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `at Securing the eyes open to full in 200 ms and blink once`() {
        showEyes()
        to(ConnectingPhase.CHECKING)
        rig.advanceTo(rig.at + Narrow.MILLIS + 16L)
        to(ConnectingPhase.SECURING)
        val start = rig.at
        // Frame by frame, the eyes close once, nearly shut, and open again.
        val blinks = mutableListOf<Float>()
        while (rig.at < start + 250L) {
            rig.frames(1)
            blinks += eyes().blink
        }
        val shut = blinks.indexOf(blinks.min())
        assertTrue("the blink at ${blinks.min()}", blinks.min() < 0.15f)
        assertEquals(blinks.take(shut + 1), blinks.take(shut + 1).sortedDescending())
        assertEquals(blinks.drop(shut), blinks.drop(shut).sorted())
        assertEquals(MarkPose.Rest, eyes())
        rig.advanceTo(start + 1_000L)
        assertEquals(MarkPose.Rest, eyes())
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `at Securing the eyes open from where Checking narrowed them`() {
        showEyes()
        to(ConnectingPhase.CHECKING)
        rig.advanceTo(rig.at + Narrow.MILLIS + 16L)
        to(ConnectingPhase.SECURING)
        assertNear(Narrow.SQUINT, eyes().squint)
        rig.advanceTo(rig.at + 64L)
        assertTrue("opening at ${eyes().squint}", eyes().squint > Narrow.SQUINT + NEAR && eyes().squint < 1f)
    }

    @Test
    fun `under Remove animations the eyes hold still at the centre through every phase, asking for no frame`() {
        showEyes(scale = 0f)
        for (next in ConnectingPhase.entries) {
            to(next)
            repeat(4) {
                rig.frames(5)
                assertEquals("$next", MarkPose.Rest, eyes())
            }
            assertFalse("$next asks for frames", rig.framesAsked())
        }
    }

    @Test
    fun `a rotation at Checking shows the eyes checking, not narrowing again`() {
        showEyes()
        to(ConnectingPhase.CHECKING)
        rig.advanceTo(rig.at + Narrow.MILLIS + 16L)
        rig.restore()
        assertEquals(MarkPose(squint = Narrow.SQUINT), eyes())
    }

    @Test
    fun `a rotation mid-narrowing shows the eyes checking, not opening to narrow again`() {
        showEyes()
        to(ConnectingPhase.CHECKING)
        // A fifth of the way, emphasized decelerate has narrowed the eyes most of the way already.
        rig.advanceTo(rig.at + Narrow.MILLIS / 5L)
        assertTrue("mid-way at ${eyes().squint}", eyes().squint > Narrow.SQUINT + NEAR / 2)
        rig.restore()
        assertEquals(MarkPose(squint = Narrow.SQUINT), eyes())
        rig.frames(10)
        assertEquals(MarkPose(squint = Narrow.SQUINT), eyes())
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `the search asks for no more frames once its screen has left`() {
        showEyes()
        rig.frames(10)
        assertTrue(rig.framesAsked())
        rig.remove()
        assertFalse(rig.framesAsked())
    }

    private fun showConnecting(
        scale: Float = 1f,
        fontScale: Float? = null,
        host: String = HOST,
    ) = rig.show(scale, fontScale) {
        ConnectingAt(pose = { MarkPose.Rest }, phase = phase, host = host)
    }

    private fun top(text: String): Dp = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun shown(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the old line rises 8 dp as it fades out in 90 ms, and the new one rises from 8 dp below after it`() {
        showConnecting()
        val rest = top(REACHING_LINE)
        to(ConnectingPhase.CHECKING)
        rig.advanceTo(rig.at + 45L)
        val leaving = top(REACHING_LINE)
        assertTrue("the old line at $leaving, from $rest", leaving < rest && leaving > rest - 8.dp)
        val coming = top(CHECKING_LINE)
        assertTrue("the new line at $coming, from $rest", coming > rest + 7.dp && coming <= rest + 8.dp)
        val live =
            rule
                .onNodeWithText(
                    CHECKING_LINE,
                ).fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(LiveRegionMode.Polite, live)
        rig.advanceTo(rig.at + 150L)
        val rising = top(CHECKING_LINE)
        assertTrue("the new line at $rising", rising > rest && rising < rest + 8.dp)
        rig.advanceTo(rig.at + 300L)
        assertEquals(rest, top(CHECKING_LINE))
        assertFalse(shown(REACHING_LINE))
    }

    @Test
    fun `the old line rises on emphasized accelerate as it goes, and the new one on emphasized decelerate`() {
        showConnecting()
        val rest = top(REACHING_LINE)
        to(ConnectingPhase.CHECKING)
        // The change's clock reads 0 on the frame after the one that composes it: 7.4's "the old line rises 8 dp and
        // fades out in 90 ms (emphasized accelerate), the new one rises from 8 dp below ... over 210 ms after 90 ms
        // (emphasized decelerate)", each line's place within a pixel of its curve on every frame.
        for (frame in 0 until 24) {
            val ms = maxOf(0L, 16L * (frame - 1))
            val gone = MarkEasing.EmphasizedAccelerate.transform((ms / 90f).coerceIn(0f, 1f))
            val come = MarkEasing.EmphasizedDecelerate.transform(((ms - 90f) / 210f).coerceIn(0f, 1f))
            if (ms < 90L) assertNear(rest - 8.dp * gone, top(REACHING_LINE), "the old line at $ms ms")
            assertNear(rest + 8.dp * (1f - come), top(CHECKING_LINE), "the new line at $ms ms")
            rig.frames(1)
        }
    }

    private fun assertNear(
        expected: Dp,
        actual: Dp,
        what: String,
    ) = assertEquals(what, expected.value, actual.value, A_PIXEL.value)

    /**
     * The most ink read in the line's band, from 10 dp over its place at rest, [rest], to 44 dp under it, which holds
     * the line wherever its 8 dp rise has it and nothing else: over a clear window a pixel's alpha, over a white one
     * how grey it is.
     */
    private fun inkIn(rest: Dp): Float {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val from = with(rule.density) { (rest - 10.dp).roundToPx() }
        val to = with(rule.density) { (rest + 44.dp).roundToPx() }
        val pixels = IntArray(image.width * (to - from))
        image.getPixels(pixels, 0, image.width, 0, from, image.width, to - from)
        return pixels.maxOf { pixel ->
            val alpha = pixel ushr 24
            if (alpha < 255) alpha / 255f else 1f - (pixel shr 16 and 0xFF) / 255f
        }
    }

    @Test
    fun `the old line fades out in 90 ms, and the new one fades in over 210 ms after it`() {
        showConnecting()
        val rest = top(REACHING_LINE)
        val full = inkIn(rest)
        to(ConnectingPhase.CHECKING)
        // The change's clock reads 0 on the frame after the one that composes it; the old line has gone by 90 ms.
        for (frame in 0 until 24) {
            val ms = maxOf(0L, 16L * (frame - 1))
            val expected = if (ms < 90L) leavingOpacity(ms) else comingOpacity(ms)
            assertEquals("the line at $ms ms", expected, inkIn(rest) / full, LINE_OPACITY)
            rig.frames(1)
        }
    }

    @Test
    fun `under Remove animations the line simply changes, one line on every frame`() {
        showConnecting(scale = 0f)
        val rest = top(REACHING_LINE)
        phase = ConnectingPhase.CHECKING
        rule.waitForIdle()
        for (frame in 0 until 6) {
            rig.frames(1)
            val lines = listOf(REACHING_LINE, CHECKING_LINE).filter(::shown)
            assertEquals("frame $frame", 1, lines.size)
            assertEquals(rest, top(lines.single()))
        }
        assertEquals(listOf(CHECKING_LINE), listOf(REACHING_LINE, CHECKING_LINE).filter(::shown))
    }

    @Test
    fun `at twice the font size, with the longest line, the mark and the steps hold still from phase to phase`() {
        showConnecting(fontScale = 2f, host = LONG_HOST)
        val mark = rule.onNodeWithTag(MARK_KEY).getUnclippedBoundsInRoot()
        val steps = rule.onNodeWithTag(STEPS_KEY).getUnclippedBoundsInRoot()
        val line = rule.onNodeWithText("Reaching $LONG_HOST…").getUnclippedBoundsInRoot()
        // Taller than the reference player's box, two lines at the usual size.
        assertTrue("the longest line is ${line.bottom - line.top} tall", line.bottom - line.top > 64.dp)
        for (next in ConnectingPhase.entries) {
            to(next)
            for (frame in 0 until 40) {
                assertEquals("$next, frame $frame", mark, rule.onNodeWithTag(MARK_KEY).getUnclippedBoundsInRoot())
                assertEquals(
                    "$next, frame $frame",
                    steps.top,
                    rule.onNodeWithTag(STEPS_KEY).getUnclippedBoundsInRoot().top,
                )
                rig.frames(1)
            }
        }
    }

    private var current by mutableIntStateOf(0)

    private fun showSteps(scale: Float = 1f) =
        rig.show(scale) { Box(modifier = Modifier.testTag(STEPS)) { StepDots(current = current) } }

    /** Each step's width along the row's middle, in px, and the colour at its middle. */
    private fun steps(): List<Pair<Int, Int>> {
        val image: Bitmap = rule.onNodeWithTag(STEPS).captureToImage().asAndroidBitmap()
        val y = image.height / 2
        val runs = mutableListOf<Pair<Int, Int>>()
        var x = 0
        while (x < image.width) {
            if (android.graphics.Color.alpha(image.getPixel(x, y)) < DRAWN_ALPHA) {
                x++
                continue
            }
            val start = x
            while (x < image.width && android.graphics.Color.alpha(image.getPixel(x, y)) >= DRAWN_ALPHA) x++
            runs += (x - start) to image.getPixel((start + x) / 2, y)
        }
        return runs
    }

    @Test
    fun `the current step is a 20 dp pill and the others 8 dp dots, done grey, current ink and to come hairline`() {
        current = 1
        showSteps()
        rig.frames(30)
        val colors = FermixColors.Light
        val expected =
            listOf(
                16 to colors.textSecondary.toArgb(),
                40 to colors.ink.toArgb(),
                16 to colors.hairline.toArgb(),
            )
        assertEquals(expected, steps())
    }

    @Test
    fun `the pill's width moves to the next step on fastSpatial, and snaps under Remove animations`() {
        showSteps()
        rig.frames(30)
        current = 1
        rig.frameAfterWrite()
        rig.frames(2)
        val (first, second) = steps().map { it.first }
        assertTrue("widths $first and $second mid-move", first in 17..39 && second in 17..39)
        rig.frames(30)
        assertEquals(listOf(16, 40, 16), steps().map { it.first })
    }

    @Test
    fun `under Remove animations the pill moves to the next step at once`() {
        showSteps(scale = 0f)
        current = 1
        rig.frameAfterWrite()
        assertEquals(listOf(16, 40, 16), steps().map { it.first })
    }
}
