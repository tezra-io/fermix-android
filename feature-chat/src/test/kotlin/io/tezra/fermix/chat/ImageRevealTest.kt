package io.tezra.fermix.chat

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FRAME = "frame"
private val INK = Color.Black.toArgb()

/**
 * An image's reveal as it draws (the owner, 2026-10-10), a black square standing for the image over a white
 * placeholder: one the daemon has just brought comes in from unseen, part seen on the way, whole by 400 ms, and then
 * draws with no layer left; one the phone held already, as a chat opens or a cell scrolls back to it, and any under
 * reduce-motion, is whole as its cell first draws it. No render effect is ever set: a blur's crashed the emulator's
 * renderer (Task 23's review).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ImageRevealTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private var shown by mutableStateOf<MediaImage.Shown?>(null)
    private var share: (() -> Float)? = null

    private fun show(reduced: Boolean = false) {
        val scale = if (reduced) 0f else 1f
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FermixTheme {
                Box(Modifier.testTag(FRAME).size(40.dp).background(Color.White)) {
                    val reveal = rememberReveal(shown, held = null)
                    share = reveal
                    if (shown != null) Box(Modifier.fillMaxSize().reveal(reveal).background(Color.Black))
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    /** A change of the test's drawn: told to the composition, then the frame that composes and draws it. */
    private fun drawn() {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun middle(): Int {
        val image: Bitmap = rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()
        return image.getPixel(image.width / 2, image.height / 2)
    }

    /** How dark the middle is, from 0 for the placeholder to 255 for the image whole. */
    private fun seen(): Int = 255 - (middle() and 0xFF)

    @Test
    fun `an image the daemon has just brought comes in from unseen, part seen on the way, whole by 400 ms`() {
        show()
        shown = MediaImage.Shown(ImageBitmap(1, 1), fresh = true)
        drawn()
        val first = seen()
        assertEquals("as it is first drawn", 0, first)
        assertNotNull("its layer while it comes in", share)
        rule.mainClock.advanceTimeBy(64L)
        val partway = seen()
        assertTrue("part of the way: $partway after $first", partway in first + 1..250)
        rule.mainClock.advanceTimeBy(400L)
        assertEquals(INK, middle())
        assertNull("no layer once it is whole", share)
    }

    @Test
    fun `under reduce-motion the image is whole as its cell first draws it`() {
        show(reduced = true)
        shown = MediaImage.Shown(ImageBitmap(1, 1), fresh = true)
        drawn()
        assertEquals(INK, middle())
        assertNull(share)
    }

    @Test
    fun `an image the phone held already, as a chat opens or a cell scrolls back, is drawn plainly at once`() {
        show()
        shown = MediaImage.Shown(ImageBitmap(1, 1), fresh = false)
        drawn()
        assertEquals(INK, middle())
        assertNull("no layer for it", share)
    }

    @Test
    fun `the image settles from a little large to its own size as it comes in`() {
        assertEquals(REVEAL_SCALE, revealScale(0f))
        assertEquals((1f + REVEAL_SCALE) / 2f, revealScale(0.5f), 1e-6f)
        assertEquals(1f, revealScale(1f))
        assertTrue("larger at the start", REVEAL_SCALE > 1f)
    }
}
