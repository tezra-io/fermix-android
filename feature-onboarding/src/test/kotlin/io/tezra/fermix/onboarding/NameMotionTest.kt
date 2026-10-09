package io.tezra.fermix.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.NameMotion
import io.tezra.fermix.design.Tint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val AVATAR = "avatar"

/** Name's own motion (the M51 update's 7.4): the avatar's tint cross-fades over 200 ms, or changes at once. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class NameMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val rig = MotionRig(rule)
    private var tint by mutableStateOf(Tint.Ocean)

    private fun showAvatar(scale: Float = 1f) = rig.show(scale) { Avatar(tint, Modifier.testTag(AVATAR)) }

    /** The colour at the avatar's centre. */
    private fun centre(): Int {
        val image = rule.onNodeWithTag(AVATAR).captureToImage().asAndroidBitmap()
        return image.getPixel(image.width / 2, image.height / 2)
    }

    @Test
    fun `the avatar's tint cross-fades to a new one over 200 ms`() {
        showAvatar()
        assertEquals(Tint.Ocean.color.toArgb(), centre())
        tint = Tint.Clay
        rig.frameAfterWrite()
        rig.advanceTo(rig.at + NameMotion.TINT_MILLIS / 2L)
        val mid = centre()
        assertTrue(
            "mid-way at ${Integer.toHexString(mid)}",
            mid != Tint.Ocean.color.toArgb() && mid != Tint.Clay.color.toArgb(),
        )
        rig.advanceTo(rig.at + NameMotion.TINT_MILLIS)
        assertEquals(Tint.Clay.color.toArgb(), centre())
    }

    @Test
    fun `under Remove animations the avatar takes its new tint at once`() {
        showAvatar(scale = 0f)
        tint = Tint.Clay
        rig.frameAfterWrite()
        assertEquals(Tint.Clay.color.toArgb(), centre())
    }
}
