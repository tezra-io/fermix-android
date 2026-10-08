package io.tezra.fermix.design

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FRAME = "frame"

/** Room around the mark, in mark units: at 0 ms the drop's dot reaches 21 units above the box, its centre 13.5. */
private const val ROOM = 30f

/** The mark is drawn 100 dp across at 2 px a dp, so a mark unit is two pixels. */
private const val PX_PER_UNIT = 2f

/**
 * The mark as it draws a pose (the M51 update's 2.1 and section 6), on Robolectric's native graphics in light mode: the
 * body in the ink with the visor cut out, the eyes in the ink, the corners never past half an eye's height, the happy
 * arcs, the visor opening about its centre, what the drop and the hop draw outside the mark's box, and a pose read as
 * the mark draws, so a moving pose draws it again without composing it again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class FermixMarkTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private var pose by mutableStateOf(MarkPose.Rest)

    /** Whether the rule holds the frame yet: it takes its content once, and a test then changes the pose. */
    private var shown = false

    private val ink = FermixColors.Light.ink.toArgb()
    private val canvas = FermixColors.Light.canvas.toArgb()

    /** The mark 100 dp across, with [ROOM] units of the canvas on every side, as the frame's image. */
    private fun drawn(): Bitmap {
        if (!shown) {
            shown = true
            rule.setContent {
                FermixTheme(darkTheme = false) {
                    val colors = LocalFermixColors.current
                    Box(Modifier.testTag(FRAME).background(colors.canvas).padding((ROOM * PX_PER_UNIT / 2f).dp)) {
                        FermixMark(pose = { pose }, size = 100.dp)
                    }
                }
            }
        }
        rule.waitForIdle()
        return rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()
    }

    /** The pixel at ([x], [y]) in mark units. */
    private fun Bitmap.at(
        x: Float,
        y: Float,
    ): Int = getPixel(((x + ROOM) * PX_PER_UNIT).toInt(), ((y + ROOM) * PX_PER_UNIT).toInt())

    private fun Bitmap.at(point: Offset): Int = at(point.x, point.y)

    /** How many times the composition has applied changes: a pose read only as the mark draws applies none. */
    private fun changes(): Long = Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    @Test
    fun `at rest the body is the ink, the visor is cut out of it, and the eyes are the ink`() {
        val image = drawn()
        assertEquals(ink, image.at(50f, 80f))
        assertEquals(ink, image.at(25f, 20f))
        // The visor between the eyes and above them, and outside the outline.
        assertEquals(canvas, image.at(48.5f, 47.8f))
        assertEquals(canvas, image.at(MarkGeometry.leftEye.centre.x, 35f))
        assertEquals(canvas, image.at(3f, 10f))
        assertEquals(ink, image.at(MarkGeometry.leftEye.centre))
        assertEquals(ink, image.at(MarkGeometry.rightEye.centre))
    }

    @Test
    fun `the eyes' corners are never more than half their height`() {
        assertEquals(2.2f, eyeCorner(15.42f))
        assertEquals(2.2f, eyeCorner(4.4f))
        assertEquals(1.5f, eyeCorner(3f))
        assertEquals(0.005f, eyeCorner(0.01f))
        val blink = MarkPose.Rest.copy(blink = 0.1f)
        assertEquals(1.542f, eyeHeight(MarkGeometry.leftEye, blink), 1e-4f)
        assertEquals(0.01f, eyeHeight(MarkGeometry.leftEye, MarkPose.Rest.copy(happy = 1f)))
    }

    @Test
    fun `a blink shrinks the eye's height about its centre`() {
        pose = MarkPose.Rest.copy(blink = 0.1f)
        val image = drawn()
        val eye = MarkGeometry.leftEye
        assertEquals(ink, image.at(eye.centre))
        assertEquals(canvas, image.at(eye.centre.x, eye.centre.y - eye.height / 4f))
        assertEquals(canvas, image.at(eye.centre.x, eye.centre.y + eye.height / 4f))
    }

    @Test
    fun `a new pose draws the mark again without composing it again`() {
        drawn()
        val composed = changes()
        pose = MarkPose.Rest.copy(blink = 0.1f)
        val image = drawn()
        val eye = MarkGeometry.leftEye
        // The control: the blink is drawn.
        assertEquals(canvas, image.at(eye.centre.x, eye.centre.y - eye.height / 4f))
        assertEquals(composed, changes())
    }

    @Test
    fun `the happy eyes are arcs, the rectangles gone`() {
        pose = MarkPose.Rest.copy(happy = 1f)
        val image = drawn()
        for (eye in listOf(MarkGeometry.leftEye, MarkGeometry.rightEye)) {
            // The quadratic's top, halfway: its ends' and its control's mean, cy + 2 and cy - 6.2, is cy - 2.1.
            assertEquals(ink, image.at(eye.centre.x, eye.centre.y - 2.1f))
            assertEquals(canvas, image.at(eye.centre.x, eye.centre.y + 1f))
            assertEquals(canvas, image.at(eye.centre.x, eye.centre.y + eye.height / 3f))
        }
    }

    @Test
    fun `the visor opens about its centre, and shut it cuts nothing out`() {
        val centre = MarkGeometry.visorCentre
        // 14 units above the centre lies inside the open visor, whose top is 18 above it, and outside a half-open one.
        pose = MarkPose.Rest.copy(eyeLeft = 0f, eyeRight = 0f)
        assertEquals(canvas, drawn().at(centre.x, centre.y - 14f))
        pose = pose.copy(visorOpen = 0.5f)
        val half = drawn()
        assertEquals(ink, half.at(centre.x, centre.y - 14f))
        assertEquals(canvas, half.at(centre.x, centre.y - 6f))
        pose = pose.copy(visorOpen = 0f)
        assertEquals(ink, drawn().at(centre))
    }

    @Test
    fun `the drop's dot draws above the box, never clipped to it`() {
        pose = dropAt(0f)
        val image = drawn()
        // At 0 ms the dot's lowest point is 6 units above the box, its centre 13.5.
        assertEquals(ink, image.at(50f, -13.5f))
        assertEquals(canvas, image.at(50f, 50f))
    }

    @Test
    fun `the hop's top lifts the body 10 units, over the box's top`() {
        pose = MarkPose.Rest
        val standing = drawn()
        pose = hopAt(250f)
        val top = drawn()
        // The outline's top-most point is 2.4 units down; at the hop's top, and stretched, it is past the box.
        assertEquals(canvas, standing.at(40.7f, -1f))
        assertEquals(ink, top.at(40.7f, -1f))
        assertTrue(top.at(50f, 96f) == canvas)
    }
}
