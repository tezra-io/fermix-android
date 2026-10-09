package io.tezra.fermix.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.BellSwing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sign

/**
 * Notifications' motion on a test clock (the M51 update's 7.4): the bell swings once from its top, 0, 14, -10, 6, -3
 * and 0 degrees between 300 and 1,000 ms, and not again after a rotation, nor at all under Remove animations; a grant
 * cross-fades it to a check over 200 ms, or shows it at once under Remove animations; Not now ends onboarding at once.
 * The wait after a grant is the ViewModel's (EntryWaitsTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class NotificationsMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val rig = MotionRig(rule)
    private var bell: () -> Float = { 0f }

    @Test
    fun `the bell swings once, 0, 14, -10, 6, -3 and 0 degrees between 300 and 1,000 ms`() {
        rig.show { bell = rememberBellSwing() }
        val angles = mutableListOf<Pair<Long, Float>>()
        while (rig.at < BellSwing.CLOCK_MILLIS + 100L) {
            angles += rig.at to bell()
            rig.frames(1)
        }
        assertTrue(angles.filter { it.first < 300L }.all { it.second == 0f })
        // The swings' ends, each the furthest the bell goes between two crossings of the upright.
        val swings = angles.map { it.second }.filter { it != 0f }
        val ends = swings.fold(listOf<Float>()) { found, angle -> furthest(found, angle) }
        assertEquals(4, ends.size)
        for ((end, table) in ends.zip(listOf(14f, -10f, 6f, -3f))) {
            assertTrue(
                "swung to $end, not $table",
                abs(end - table) < 0.5f,
            )
        }
        assertEquals(0f, bell())
        assertFalse(rig.framesAsked())
        rig.restore()
        repeat(80) {
            assertEquals(0f, bell())
            rig.frames(1)
        }
    }

    /** [found] with [angle]: a new swing's end when it lies the other side of the upright, or a further one. */
    private fun furthest(
        found: List<Float>,
        angle: Float,
    ): List<Float> =
        when {
            found.isEmpty() || found.last().sign != angle.sign -> found + angle
            abs(angle) > abs(found.last()) -> found.dropLast(1) + angle
            else -> found
        }

    @Test
    fun `a rotation mid-swing shows the bell hanging still, and it does not swing again`() {
        rig.show { bell = rememberBellSwing() }
        rig.advanceTo(500L)
        assertTrue("the bell at ${bell()} degrees", bell() != 0f)
        rig.restore()
        repeat(80) {
            assertEquals(0f, bell())
            rig.frames(1)
        }
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `under Remove animations the bell never swings`() {
        rig.show(scale = 0f) { bell = rememberBellSwing() }
        repeat(80) {
            assertEquals(0f, bell())
            rig.frames(1)
        }
    }

    private var granted by mutableStateOf(false)
    private var check: () -> Float = { 0f }

    @Test
    fun `a grant cross-fades the bell to a check over 200 ms`() {
        rig.show { check = rememberBellCheck(granted) }
        assertEquals(0f, check())
        granted = true
        rig.frameAfterWrite()
        rig.advanceTo(rig.at + BellSwing.CHECK_MILLIS / 2L)
        assertTrue("the check at ${check()}", check() in 0.1f..0.9f)
        rig.advanceTo(rig.at + BellSwing.CHECK_MILLIS)
        assertEquals(1f, check())
    }

    @Test
    fun `under Remove animations a grant shows the check at once`() {
        rig.show(scale = 0f) { check = rememberBellCheck(granted) }
        granted = true
        rig.frameAfterWrite()
        assertEquals(1f, check())
    }

    private var ended: Long? = null

    private fun showScreen() = rig.show { NotificationsScreen(onAllow = {}, onNotNow = { ended = rig.now() }) }

    @Test
    fun `Not now ends onboarding at once`() {
        showScreen()
        rule.onNodeWithText("Not now").performClick()
        // The click's own frame.
        assertTrue("ended at $ended ms", checkNotNull(ended) <= 16L)
    }
}
