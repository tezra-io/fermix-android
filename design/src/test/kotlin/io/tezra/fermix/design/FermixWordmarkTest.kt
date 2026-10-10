package io.tezra.fermix.design

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.github.takahirom.roborazzi.RoborazziActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FRAME = "frame"

/** The wordmark 116 dp tall, its whole box: a unit of the file a dp, two pixels. */
private val UNIT_A_DP = 116.dp
private const val PX_PER_UNIT = 2f

/** The bar's height, as the Chats list draws it, and the box's width at it: 396 units of 116. */
private val BAR = 24.dp
private val BAR_WIDTH = 81.931.dp

/** A pixel, in dp: layout rounds a size to whole pixels. */
private const val PIXEL_DP = 0.5f

/**
 * Above and below a dot's centre, a pixel wholly in its 4.7 units (4.0 to 4.5 from the centre) and one wholly past
 * them (5.0 to 5.5), so a dot drawn at another radius fails.
 */
private val JUST_INSIDE = Offset(0f, 4.2f)
private val JUST_OUTSIDE = Offset(0f, 5.2f)

/**
 * The wordmark as it draws (the owner, 2026-10-10), on Robolectric's native graphics: the letters in the mode's ink,
 * cut by the even-odd rule, the two eye-dots in the signal, the file's margin kept round them, the box's width its
 * height's 396 / 116, and one node TalkBack reads as "Fermix".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class FermixWordmarkTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private var height by mutableStateOf(UNIT_A_DP)

    /** The wordmark [height] tall on the canvas of [dark] or light mode, as the frame's image; set once a test. */
    private fun drawn(dark: Boolean): Bitmap {
        rule.setContent {
            FermixTheme(darkTheme = dark) {
                Box(Modifier.testTag(FRAME).background(LocalFermixColors.current.canvas)) {
                    FermixWordmark(height = height)
                }
            }
        }
        rule.waitForIdle()
        return rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()
    }

    /** The pixel at the file's point ([x], [y]), in its units, the viewBox's top left at the frame's. */
    private fun Bitmap.at(
        x: Float,
        y: Float,
    ): Int =
        getPixel(
            ((x - WordmarkGeometry.LEFT) * PX_PER_UNIT).toInt(),
            ((y - WordmarkGeometry.TOP) * PX_PER_UNIT).toInt(),
        )

    /** Points inside a letter: F's stem, E's foot, R's stem and bowl, M's stem, the I's stem, and an arm of the X. */
    private val inLetters =
        listOf(8f to 50f, 105f to 92f, 146f to 60f, 188f to 26f, 217f to 50f, 302f to 70f, 334f to 5f)

    /**
     * Points the letters leave clear: the R's counter, which the even-odd rule cuts, the notch of the M, the gap
     * between the I's dots and its stem, between the two dots, the X's middle, and the margin on every side.
     */
    private val clear =
        listOf(
            167f to 26f,
            245f to 20f,
            302f to 35f,
            302.5f to 21f,
            354f to 50f,
            -3f to 50f,
            200f to -5f,
            200f to 105f,
            387f to 50f,
        )

    private fun assertDrawn(colors: FermixColors) {
        val image = drawn(dark = colors == FermixColors.Dark)
        for ((x, y) in inLetters) assertEquals("($x, $y)", colors.ink.toArgb(), image.at(x, y))
        for ((x, y) in clear) assertEquals("($x, $y)", colors.canvas.toArgb(), image.at(x, y))
        for (dot in WordmarkGeometry.dots) {
            val centre = dot + WordmarkGeometry.dotsAt
            for (point in listOf(centre, centre + JUST_INSIDE, centre - JUST_INSIDE)) {
                assertEquals("the dot at $centre, at $point", colors.signal.toArgb(), image.at(point.x, point.y))
            }
            for (point in listOf(centre + JUST_OUTSIDE, centre - JUST_OUTSIDE)) {
                assertEquals("past the dot at $centre, at $point", colors.canvas.toArgb(), image.at(point.x, point.y))
            }
        }
    }

    @Test
    fun `in light mode the letters are the ink, the dots the signal, and the counter and the margin clear`() {
        assertDrawn(FermixColors.Light)
    }

    @Test
    fun `in dark mode the letters are dark mode's ink and the dots the same signal`() {
        assertDrawn(FermixColors.Dark)
    }

    @Test
    fun `the box is the file's, its height with the margin and its width 396 of 116 of it`() {
        drawn(dark = false)
        val box = rule.onNodeWithContentDescription("Fermix").getUnclippedBoundsInRoot()
        assertEquals(UNIT_A_DP.value, box.height.value, 0.01f)
        assertEquals(WordmarkGeometry.WIDTH, box.width.value, 0.01f)
        height = BAR
        rule.waitForIdle()
        val bar = rule.onNodeWithContentDescription("Fermix").getUnclippedBoundsInRoot()
        assertEquals(BAR.value, bar.height.value, 0.01f)
        // Laid out in whole pixels: 163.86 px is 164.
        assertEquals(BAR_WIDTH.value, bar.width.value, PIXEL_DP)
    }

    @Test
    fun `TalkBack reads it as one node described Fermix, with no text and nothing under it`() {
        drawn(dark = false)
        val merged = rule.onAllNodesWithContentDescription("Fermix").fetchSemanticsNodes()
        val unmerged = rule.onAllNodesWithContentDescription("Fermix", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, merged.size)
        assertEquals(1, unmerged.size)
        val node = merged.single()
        assertEquals(listOf("Fermix"), node.config[SemanticsProperties.ContentDescription])
        assertEquals(false, node.config.contains(SemanticsProperties.Text))
        assertEquals(0, node.children.size)
    }
}
