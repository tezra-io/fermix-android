package io.tezra.fermix.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.Countdown
import io.tezra.fermix.design.LookDown
import io.tezra.fermix.design.MarkPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.random.Random

/** One second of the countdown, on the test clock. */
private const val SECOND = 1_000L

/**
 * Verify's motion on a test clock (the M51 update's 7.4): the eyes look down at the code between 200 and 500 ms and
 * stay there; the ring depletes linearly between the second's ticks, or steps under Remove animations; at 30 s and at
 * 10 s left, and then only, its stroke thickens and thins once over 300 ms and TalkBack is told the time left,
 * politely, once each, not again after a rotation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class VerifyMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val rig = MotionRig(rule)
    private var eyes: () -> MarkPose = { MarkPose.Rest }
    private var secondsLeft by mutableIntStateOf(102)
    private var ring: RingState? = null

    private fun pose(): RingPose = checkNotNull(ring).pose()

    @Test
    fun `the eyes look down 2-6 units between 200 and 500 ms, and stay there`() {
        rig.show { eyes = rememberVerifyEyes() }
        rig.advanceToJustBefore(200L)
        assertEquals(0f, eyes().eyeY)
        rig.advanceTo(350L)
        assertTrue("the eyes at ${eyes().eyeY}", eyes().eyeY > 0f && eyes().eyeY < 2.6f)
        rig.advanceTo(LookDown.CLOCK_MILLIS + 16L)
        assertEquals(MarkPose(eyeY = 2.6f), eyes())
        assertFalse(rig.framesAsked())
        rig.restore()
        assertEquals(MarkPose(eyeY = 2.6f), eyes())
    }

    @Test
    fun `a rotation mid-look stands the eyes on the code`() {
        rig.show { eyes = rememberVerifyEyes() }
        rig.advanceTo(400L)
        assertTrue("the eyes at ${eyes().eyeY}", eyes().eyeY > 0f && eyes().eyeY < 2.6f)
        rig.restore()
        assertEquals(MarkPose(eyeY = 2.6f), eyes())
        rig.frames(10)
        assertEquals(MarkPose(eyeY = 2.6f), eyes())
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `under Remove animations the eyes look at the code from the first frame`() {
        rig.show(scale = 0f) { eyes = rememberVerifyEyes() }
        assertEquals(MarkPose(eyeY = 2.6f), eyes())
    }

    private fun showRing(scale: Float = 1f) = rig.show(scale) { ring = rememberRing(secondsLeft) }

    /** The countdown ticking to [seconds] left, a second after the last tick. */
    private fun tick(seconds: Int) {
        rig.advanceTo(rig.at + SECOND - 16L)
        secondsLeft = seconds
        rig.frameAfterWrite()
    }

    @Test
    fun `the ring depletes linearly from one second to the next`() {
        showRing()
        val start = rig.at
        rig.advanceTo(start + SECOND / 2)
        val halfway = (102f - 0.5f) / PAIRING_COUNTDOWN_SECONDS
        // Within a twentieth of a second's share of the ring: the ring moves on a frame after the tick.
        val near = 0.05f / PAIRING_COUNTDOWN_SECONDS
        assertTrue("the ring at ${pose().left}, not $halfway", abs(pose().left - halfway) < near)
        tick(101)
        assertTrue("the ring at ${pose().left}", abs(pose().left - 101f / PAIRING_COUNTDOWN_SECONDS) < 0.002f)
        rig.advanceTo(rig.at + SECOND / 4)
        assertTrue("the ring at ${pose().left}", pose().left < 101f / PAIRING_COUNTDOWN_SECONDS)
    }

    @Test
    fun `the ring asks for frames as it depletes through a second, and for none once Verify has left`() {
        showRing()
        rig.advanceTo(SECOND / 2)
        assertTrue(rig.framesAsked())
        rig.remove()
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `under Remove animations the ring steps from second to second`() {
        showRing(scale = 0f)
        rig.advanceTo(SECOND / 2)
        assertEquals(102f / PAIRING_COUNTDOWN_SECONDS, pose().left)
        tick(101)
        assertEquals(101f / PAIRING_COUNTDOWN_SECONDS, pose().left)
    }

    @Test
    fun `at 30 s and at 10 s left the stroke thickens and thins once over 300 ms, and then only`() {
        secondsLeft = 32
        showRing()
        val thick = mutableMapOf<Int, Float>()
        for (seconds in 31 downTo 8) {
            tick(seconds)
            var most = 0f
            repeat(30) {
                rig.frames(1)
                most = maxOf(most, pose().thick)
            }
            thick[seconds] = most
        }
        for ((seconds, most) in thick) {
            if (seconds in Countdown.marks) {
                assertTrue("$seconds s thickened to $most", most > 0.95f)
            } else {
                assertEquals("$seconds s", 0f, most)
            }
        }
    }

    @Test
    fun `the pulse comes back to 3 dp by 300 ms, and plays no more after a rotation at 30 s`() {
        secondsLeft = 31
        showRing()
        tick(30)
        val start = rig.at
        rig.advanceTo(start + 150L)
        assertTrue(pose().thick > 0.9f)
        rig.advanceTo(start + Countdown.PULSE_MILLIS + 16L)
        assertEquals(0f, pose().thick)
        assertTrue(checkNotNull(ring).announcing)
        rig.restore()
        rig.frames(10)
        assertEquals(0f, pose().thick)
        assertFalse(checkNotNull(ring).announcing)
    }

    @Test
    fun `under Remove animations the stroke never thickens, and the time left is still told`() {
        secondsLeft = 31
        showRing(scale = 0f)
        tick(30)
        repeat(20) {
            rig.frames(1)
            assertEquals(0f, pose().thick)
        }
        assertTrue(checkNotNull(ring).announcing)
    }

    private fun showVerify() =
        rig.show { VerifyScreen(VerifyUi(PREVIEW_SAS, secondsLeft, PHONE), onCancel = {}, random = Random(1)) }

    /** What the countdown tells TalkBack at [clock]: its live region and its description, when it has them. */
    private fun told(clock: String): Pair<LiveRegionMode?, String?> {
        val config = rule.onNodeWithText(clock).fetchSemanticsNode().config
        val said = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        return config.getOrNull(SemanticsProperties.LiveRegion) to said
    }

    @Test
    fun `TalkBack is told the time left politely at 30 s and at 10 s, once each, and at no other second`() {
        secondsLeft = 33
        showVerify()
        val told = mutableMapOf<Int, Pair<LiveRegionMode?, String?>>()
        for (seconds in 32 downTo 8) {
            tick(seconds)
            told[seconds] = told(clock(seconds))
        }
        assertEquals(LiveRegionMode.Polite to "30 seconds left", told[30])
        assertEquals(LiveRegionMode.Polite to "10 seconds left", told[10])
        for ((seconds, said) in told.filterKeys { it !in Countdown.marks }) {
            assertEquals(
                "$seconds s",
                null to null,
                said,
            )
        }
    }

    @Test
    fun `a rotation at 30 s tells TalkBack nothing more`() {
        secondsLeft = 31
        showVerify()
        tick(30)
        assertEquals(LiveRegionMode.Polite, told("0:30").first)
        rig.restore()
        assertNull(told("0:30").first)
    }
}
