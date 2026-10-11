package io.tezra.fermix.design

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
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

private const val FRAME = "frame"

/** The frame the mark is centred in: the halo at its widest, 1.08 of 28 dp, fits with room on every side. */
private val FRAME_SIDE = 40.dp

/** Pixels a dp at xhdpi. */
private const val PX_PER_DP = 2f

/** How far a channel may be from the blend: the gradient's rounding, and the half pixel layout may move the mark. */
private const val CHANNEL_SLACK = 3

/** The halo's radius at rest, in dp: the breath at its middle, which reduce-motion holds. */
private const val RADIUS_DP = 14f

/** Distances above the mark's centre, in dp, clear of its dots (their tops 4.3 dp up at the canon's pose). */
private val IN_HALO = listOf(5f, 7f, 10f, 12f)

/**
 * The halo under the thinking card's two-dot mark as it draws (the owner, 2026-10-10), on Robolectric's native
 * graphics: the ink alone in either mode, over the canvas at the mode's peak and fading out by its edge, nothing
 * past it, and under reduce-motion one still image that asks for no frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TwoDotHaloTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    /** The mark in [dark] or light mode under reduce-motion, centred in its frame, as the frame's image. */
    private fun drawn(dark: Boolean): Bitmap {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FermixTheme(darkTheme = dark) {
                Box(
                    modifier =
                        Modifier
                            .testTag(FRAME)
                            .size(FRAME_SIDE)
                            .background(LocalFermixColors.current.canvas),
                    contentAlignment = Alignment.Center,
                ) { TwoDotMark(toolRunning = true) }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        return captured()
    }

    private fun captured(): Bitmap = rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()

    /** The pixel [dp] straight above the frame's centre, where the mark's is. */
    private fun Bitmap.above(dp: Float): Int = getPixel(width / 2, (height / 2f - dp * PX_PER_DP).toInt())

    /** [canvas] under [ink] at [alpha], as ARGB. */
    private fun blend(
        canvas: Color,
        ink: Color,
        alpha: Float,
    ): Int =
        Color(
            red = canvas.red + (ink.red - canvas.red) * alpha,
            green = canvas.green + (ink.green - canvas.green) * alpha,
            blue = canvas.blue + (ink.blue - canvas.blue) * alpha,
        ).toArgb()

    private fun assertNear(
        what: String,
        expected: Int,
        actual: Int,
    ) {
        val near =
            listOf(16, 8, 0).all { shift ->
                abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) <= CHANNEL_SLACK
            }
        assertTrue("$what: ${Integer.toHexString(actual)}, not near ${Integer.toHexString(expected)}", near)
    }

    private fun assertHalo(colors: FermixColors) {
        val image = drawn(dark = colors == FermixColors.Dark)
        val peak = haloPeak(colors)
        for (dp in IN_HALO) {
            val expected = blend(colors.canvas, colors.ink, haloAlphaAt(dp / RADIUS_DP, peak))
            assertNear("$dp dp above the centre", expected, image.above(dp))
            assertTrue("$dp dp is no longer the bare canvas", image.above(dp) != colors.canvas.toArgb())
        }
        for (dp in listOf(14.5f, 17f, 19f)) {
            assertEquals("$dp dp above the centre", colors.canvas.toArgb(), image.above(dp))
        }
    }

    @Test
    fun `in light mode the halo is the ink over the canvas, at 14 percent at its middle and gone by 14 dp`() {
        assertHalo(FermixColors.Light)
    }

    @Test
    fun `in dark mode the halo is dark mode's ink, at 12,5 percent, and gone by 14 dp`() {
        assertHalo(FermixColors.Dark)
    }

    @Test
    fun `under reduce-motion the mark and its halo stand still, a tool running or not, and ask for no frame`() {
        val first = drawn(dark = false)
        rule.mainClock.advanceTimeBy(5_000L)
        assertTrue("the same image 5 s on", first.sameAs(captured()))
        assertFalse(Recomposer.runningRecomposers.value.any { it.hasPendingWork })
    }
}
