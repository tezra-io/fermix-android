package io.tezra.fermix.design

import android.provider.Settings
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.github.takahirom.roborazzi.RoborazziActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val LENGTH = 400

/**
 * A screen's moment and a loop on a test clock (the M51 update's 7.4 and 7.5): a moment's one clock runs once from 0
 * to its length and stops there, made at its end when it played or under Remove animations; a loop counts the time
 * it runs while its work goes on and stops when the work ends, when its screen leaves, under Remove animations, and
 * at its bound; and once either stops, nothing asks for a frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class MomentTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private var shown by mutableStateOf(true)
    private var running by mutableStateOf(true)
    private val heard = mutableListOf<Float>()
    private var moment: Moment? = null
    private var loop: Loop? = null

    /** The moment's clock as its first composition read it. */
    private var firstRead: Float? = null

    private fun animations(scale: Float) {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        rule.mainClock.autoAdvance = false
    }

    private fun showMoment(
        played: Boolean,
        scale: Float = 1f,
    ): Moment {
        animations(scale)
        rule.setContent {
            FermixTheme {
                if (shown) moment = rememberMoment(LENGTH, played) { heard += it }
                if (firstRead == null) firstRead = moment?.ms
            }
        }
        rule.mainClock.advanceTimeByFrame()
        return checkNotNull(moment)
    }

    private fun showLoop(scale: Float = 1f): Loop {
        animations(scale)
        rule.setContent {
            FermixTheme { if (shown) loop = rememberLoop(running) }
        }
        rule.mainClock.advanceTimeByFrame()
        return checkNotNull(loop)
    }

    /** Whether anything in the composition waits for a frame. */
    private fun framesAsked(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }

    @Test
    fun `a moment's clock runs once from 0 to its length on the frames, and then asks for nothing`() {
        val moment = showMoment(played = false)
        rule.mainClock.advanceTimeBy(LENGTH / 2L)
        assertTrue("${moment.ms}", moment.ms > 0f && moment.ms < LENGTH)
        rule.mainClock.advanceTimeBy(LENGTH.toLong())
        assertEquals(LENGTH.toFloat(), moment.ms)
        assertEquals(heard.sorted(), heard)
        assertEquals(LENGTH.toFloat(), heard.last())
        rule.mainClock.advanceTimeByFrame()
        assertFalse(framesAsked())
    }

    @Test
    fun `a moment that played stands at its end from its first composition, and tells its clock that end once`() {
        val moment = showMoment(played = true)
        assertEquals(LENGTH.toFloat(), firstRead)
        rule.mainClock.advanceTimeBy(1_000L)
        assertEquals(LENGTH.toFloat(), moment.ms)
        assertEquals(listOf(LENGTH.toFloat()), heard)
        assertFalse(framesAsked())
    }

    @Test
    fun `under Remove animations a moment is made at its end and moves nothing`() {
        val moment = showMoment(played = false, scale = 0f)
        assertEquals(LENGTH.toFloat(), firstRead)
        rule.mainClock.advanceTimeBy(1_000L)
        assertEquals(LENGTH.toFloat(), moment.ms)
        assertEquals(listOf(LENGTH.toFloat()), heard)
        assertFalse(framesAsked())
    }

    @Test
    fun `a moment whose screen leaves asks for no more frames`() {
        showMoment(played = false)
        rule.mainClock.advanceTimeBy(100L)
        // The control: the clock is under way.
        assertTrue(framesAsked())
        shown = false
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertFalse(framesAsked())
    }

    @Test
    fun `a loop counts the time it runs, and stops where it was when its work ends`() {
        val loop = showLoop()
        rule.mainClock.advanceTimeBy(1_000L)
        assertTrue("${loop.ms}", loop.ms in 900f..1_100f)
        assertTrue(framesAsked())
        running = false
        rule.mainClock.advanceTimeByFrame()
        val stopped = loop.ms
        rule.mainClock.advanceTimeBy(1_000L)
        assertEquals(stopped, loop.ms)
        assertFalse(framesAsked())
    }

    @Test
    fun `a loop whose screen leaves asks for no more frames`() {
        showLoop()
        rule.mainClock.advanceTimeBy(500L)
        assertTrue(framesAsked())
        shown = false
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertFalse(framesAsked())
    }

    @Test
    fun `under Remove animations a loop never runs`() {
        val loop = showLoop(scale = 0f)
        rule.mainClock.advanceTimeBy(2_000L)
        assertEquals(0f, loop.ms)
        assertFalse(framesAsked())
    }

    @Test
    fun `a loop counts the time it is drawn, a frame's 100 ms at most, so after a gap it runs on from where it was`() =
        runBlocking {
            // A second of frames, ten minutes with none (the screen out of sight, the phone asleep), a second more.
            val clock = SteppedClock(frames(0L, 61) + frames(700_960L, 61))
            val loop = Loop()
            val running = launch(clock) { loop.run() }
            try {
                yield()
                assertTrue("the loop stopped after the gap", running.isActive)
                assertEquals(60 * FRAME_MILLIS + 100f + 60 * FRAME_MILLIS, loop.ms)
            } finally {
                running.cancel()
            }
        }

    @Test
    fun `a loop stops at its bound, asking for no more frames`() =
        runBlocking {
            val bound = (IDLE_MAX_MILLIS / FRAME_MILLIS).toInt()
            val clock = SteppedClock(frames(0L, bound + 10))
            val loop = Loop()
            val running = launch(clock) { loop.run() }
            try {
                yield()
                assertTrue("the loop still asks for frames", running.isCompleted)
                assertTrue(clock.unasked > 0)
                assertEquals(IDLE_MAX_MILLIS, loop.ms)
            } finally {
                running.cancel()
            }
        }
}
