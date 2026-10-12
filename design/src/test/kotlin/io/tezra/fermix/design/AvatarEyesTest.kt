package io.tezra.fermix.design

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

private const val DISC = "disc"

/** How far an edge's antialiased pixel may stray from a colour and still be it, per channel. */
private const val SLACK = 24

/**
 * The avatar's eyes (the owner, 2026-10-11) as each style draws them on a 48 dp disc of Coral, on Robolectric's native
 * graphics: the mark's eyes in the mark's ink, the pair [EYE_SPAN] of the disc's width about its centre, so the disc's
 * centre, between the eyes, is the tint in every style but Sideways, whose look puts its right eye there. A shut eye is
 * the reference player's line through its centre, so whether an eye is open is read a quarter of its height above its
 * centre.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AvatarEyesTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private var style by mutableStateOf(EyeStyle.Open)

    /** Whether the rule holds the disc yet: it takes its content once, and a test then changes the style. */
    private var shown = false

    private val left = MarkGeometry.leftEye
    private val right = MarkGeometry.rightEye

    /** The disc drawn in [next]'s eyes, as its image. */
    private fun drawn(next: EyeStyle): Bitmap {
        style = next
        if (!shown) {
            shown = true
            rule.setContent {
                val paths = remember { MarkEyePaths() }
                Canvas(Modifier.testTag(DISC).size(48.dp)) {
                    drawCircle(Tint.Coral.color)
                    drawAvatarEyes(style, size, paths)
                }
            }
        }
        rule.waitForIdle()
        return rule.onNodeWithTag(DISC).captureToImage().asAndroidBitmap()
    }

    /** The pixel at ([x], [y]) in mark units: the eye pair, edge to edge, [EYE_SPAN] of the disc about its centre. */
    private fun Bitmap.at(
        x: Float,
        y: Float,
    ): Int {
        val from = left.centre.x - left.width / 2f
        val to = right.centre.x + right.width / 2f
        val scale = EYE_SPAN * width / (to - from)
        val column = width / 2f + (x - (from + to) / 2f) * scale
        val row = height / 2f + (y - left.centre.y) * scale
        return getPixel(column.toInt(), row.toInt())
    }

    /** A quarter of [eye]'s height above its centre: the ink while it is open, the tint once it is shut. */
    private fun Bitmap.upper(eye: MarkEye): Int = at(eye.centre.x, eye.centre.y - eye.height / 4f)

    private fun isInk(pixel: Int): Boolean = near(pixel, AVATAR_EYE_INK.toArgb())

    private fun near(
        pixel: Int,
        colour: Int,
    ): Boolean =
        listOf(16, 8, 0).all { shift -> abs((pixel shr shift and 0xFF) - (colour shr shift and 0xFF)) <= SLACK }

    @Test
    fun `in every style but a look aside the disc's centre, between the eyes, is the tint`() {
        for (each in EyeStyle.entries - EyeStyle.Sideways) {
            val image = drawn(each)
            assertEquals("$each", Tint.Coral.color.toArgb(), image.getPixel(image.width / 2, image.height / 2))
        }
    }

    @Test
    fun `open eyes are the mark's ink, both of them`() {
        val image = drawn(EyeStyle.Open)
        for (eye in listOf(left, right)) {
            assertTrue(isInk(image.upper(eye)))
            assertTrue(isInk(image.at(eye.centre.x, eye.centre.y)))
        }
    }

    @Test
    fun `a wink shuts the right eye alone, and sleepy eyes are both shut`() {
        val wink = drawn(EyeStyle.Wink)
        assertTrue(isInk(wink.upper(left)))
        assertFalse(isInk(wink.upper(right)))
        val sleepy = drawn(EyeStyle.Sleepy)
        assertFalse(isInk(sleepy.upper(left)))
        assertFalse(isInk(sleepy.upper(right)))
    }

    @Test
    fun `happy eyes are arcs above the eyes' centres, the eyes gone`() {
        val image = drawn(EyeStyle.Happy)
        for (eye in listOf(left, right)) {
            // The quadratic's top, halfway: its ends' and its control's mean, cy + 2 and cy - 6.2, is cy - 2.1.
            assertTrue(isInk(image.at(eye.centre.x, eye.centre.y - 2.1f)))
            assertFalse(isInk(image.at(eye.centre.x, eye.centre.y)))
            assertFalse(isInk(image.at(eye.centre.x, eye.centre.y + eye.height / 4f)))
        }
    }

    @Test
    fun `squinting eyes are well under half as tall as open ones, and still open`() {
        val image = drawn(EyeStyle.Squint)
        for (eye in listOf(left, right)) {
            // 40 % of 15.42 is 6.2 units: 2 above the centre is the eye, 4.5 above it the disc, where an open eye is.
            assertTrue(isInk(image.at(eye.centre.x, eye.centre.y - 2f)))
            assertFalse(isInk(image.at(eye.centre.x, eye.centre.y - 4.5f)))
            assertTrue(isInk(drawn(EyeStyle.Open).at(eye.centre.x, eye.centre.y - 4.5f)))
        }
    }

    @Test
    fun `sideways eyes stand well to the left of where open eyes stand, the right eye on the disc's centre`() {
        // The left eye's left edge is 3.63 units left of its centre, so 11 units left of it lies outside an open eye.
        val open = drawn(EyeStyle.Open)
        assertFalse(isInk(open.at(left.centre.x - AVATAR_LOOK, left.centre.y)))
        assertTrue(isInk(open.at(right.centre.x + 3f, right.centre.y)))
        val sideways = drawn(EyeStyle.Sideways)
        assertTrue(isInk(sideways.at(left.centre.x - AVATAR_LOOK, left.centre.y)))
        assertTrue(isInk(sideways.at(right.centre.x - AVATAR_LOOK, right.centre.y)))
        assertFalse(isInk(sideways.at(right.centre.x + 3f, right.centre.y)))
        assertTrue(isInk(sideways.getPixel(sideways.width / 2, sideways.height / 2)))
    }
}
