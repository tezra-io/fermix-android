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
import kotlin.random.Random

private const val LENGTH = 1_000

/**
 * A mark's moment on a test clock (the M51 update's sections 4 and 6): its one clock runs once, on the frames, and the
 * idle follows, counting the time it is drawn and stopping at its bound; a moment that played, or one under reduced
 * motion, stands at its end, made there; and once the moment's screen leaves, or while reduced motion holds the mark
 * still, nothing asks for a frame. The rule's clock advances only when told, so the idle, an infinite animation, runs
 * as it does on a phone; the idle's own loop runs on a frame clock of the test's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class MarkMomentTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private var shown by mutableStateOf(true)
    private val heard = mutableListOf<Float>()
    private var moment: MarkMoment? = null

    /** The moment's clock as its first composition read it, before any frame or effect ran. */
    private var firstRead: Float? = null

    /** A moment of [LENGTH] ms, [played] or not, at the animator duration [scale]. */
    private fun show(
        played: Boolean,
        scale: Float = 1f,
    ): MarkMoment {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FermixTheme {
                if (shown) moment = rememberMarkMoment(LENGTH, played, Random(1)) { heard += it }
                if (firstRead == null) firstRead = moment?.ms
            }
        }
        rule.mainClock.advanceTimeByFrame()
        return checkNotNull(moment)
    }

    /** Whether anything in the composition waits for a frame: an animation, or the idle. */
    private fun framesAsked(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }

    @Test
    fun `the clock runs once from 0 to its length on the frames, then the idle breathes`() {
        val moment = show(played = false)
        rule.mainClock.advanceTimeBy(LENGTH / 2L)
        assertTrue("${moment.ms}", moment.ms > 0f && moment.ms < LENGTH)
        rule.mainClock.advanceTimeBy(LENGTH.toLong())
        assertEquals(LENGTH.toFloat(), moment.ms)
        assertEquals(heard.sorted(), heard)
        assertEquals(LENGTH.toFloat(), heard.last())
        rule.mainClock.advanceTimeBy(500L)
        assertTrue("${moment.idle}", moment.idle.ms > 0f)
        assertTrue(framesAsked())
    }

    @Test
    fun `a moment that played stands at its end, tells its clock that end once, and idles`() {
        val moment = show(played = true)
        assertEquals(LENGTH.toFloat(), moment.ms)
        rule.mainClock.advanceTimeBy(500L)
        assertEquals(listOf(LENGTH.toFloat()), heard)
        assertTrue("${moment.idle}", moment.idle.ms > 0f)
    }

    @Test
    fun `under reduced motion the first frame is the end, told once, and nothing breathes or asks for a frame`() {
        val moment = show(played = false, scale = 0f)
        assertEquals(LENGTH.toFloat(), moment.ms)
        assertEquals(listOf(LENGTH.toFloat()), heard)
        rule.mainClock.advanceTimeBy(5_000L)
        assertEquals(MarkIdle.Still, moment.idle)
        assertFalse(framesAsked())
    }

    @Test
    fun `under reduced motion the moment is made at its end, so its first composition reads its last frame`() {
        show(played = false, scale = 0f)
        assertEquals(LENGTH.toFloat(), firstRead)
    }

    @Test
    fun `once the moment's screen leaves, nothing asks for another frame`() {
        show(played = true)
        rule.mainClock.advanceTimeBy(500L)
        // The control: the idle is running, and waits on the next frame.
        assertTrue(framesAsked())
        shown = false
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertFalse(framesAsked())
    }

    @Test
    fun `the idle counts the time it is drawn, so after a gap in the frames it breathes on from where it was`() =
        runBlocking {
            // A second of frames, ten minutes with none (the screen out of sight, the phone asleep), a second more.
            val clock = SteppedClock(frames(0L, 61) + frames(700_960L, 61))
            val moment = MarkMoment(LENGTH.toFloat(), atEnd = true)
            val idle = launch(clock) { moment.play(reduced = false, random = Random(1)) {} }
            try {
                yield()
                assertTrue("the idle stopped after the gap", idle.isActive)
                // 60 frames, the gap counted as one long frame's 100 ms, 60 frames.
                assertEquals(60 * FRAME_MILLIS + 100f + 60 * FRAME_MILLIS, moment.idle.ms)
            } finally {
                idle.cancel()
            }
        }

    @Test
    fun `the idle stops at its bound, the mark resting and asking for no more frames`() =
        runBlocking {
            val bound = (IDLE_MAX_MILLIS / FRAME_MILLIS).toInt()
            val clock = SteppedClock(frames(0L, bound + 10))
            val moment = MarkMoment(LENGTH.toFloat(), atEnd = true)
            val idle = launch(clock) { moment.play(reduced = false, random = Random(1)) {} }
            try {
                yield()
                assertTrue("the idle still asks for frames", idle.isCompleted)
                assertTrue(clock.unasked > 0)
                assertEquals(MarkIdle.Still, moment.idle)
            } finally {
                idle.cancel()
            }
        }
}
