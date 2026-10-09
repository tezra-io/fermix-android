package io.tezra.fermix.onboarding

import android.provider.Settings
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.ScreenChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.random.Random

/** One frame of the test clock. */
private const val FRAME_MILLIS = 16L

/** The frames a change of the mark's is watched for, its spring settled within them. */
private const val MOVE_FRAMES = 30

/** The change's first frames, on one of which the mark's spring starts. */
private const val START_FRAMES = 3

/** The marks' sizes, in the window's px at xhdpi: Connecting's and Paired's 88 dp, Verify's 56. */
private const val LARGE = 176f
private const val SMALL = 112f

/**
 * The one Fermix mark through the flow (the M51 update's 7.3), in NavDisplay as the app draws it, on a test clock:
 * Connecting's mark shrinks into Verify's as Verify comes in, and grows back into Paired's, each between the two sizes
 * mid-change, and under Remove animations it is at the new size on the change's first frame. The mark says nothing to
 * a screen reader, before, during and after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class SharedMarkTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val stack = mutableStateListOf<NavKey>(OnboardingKey.Connecting)

    private fun show(scale: Float = 1f) {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FermixTheme(darkTheme = false) {
                OnboardingTransitions { changes ->
                    NavDisplay(
                        backStack = stack.toList(),
                        onBack = { stack.removeAt(stack.lastIndex) },
                        entryProvider =
                            entryProvider {
                                entry<OnboardingKey.Connecting>(metadata = changes::metadataFor) {
                                    ConnectingScreen(ConnectingPhase.SECURING, HOST, Random(1))
                                }
                                entry<OnboardingKey.Verify>(metadata = changes::metadataFor) {
                                    VerifyScreen(VerifyUi(PREVIEW_SAS, 102, PHONE), onCancel = {}, random = Random(1))
                                }
                                entry<OnboardingKey.Paired>(metadata = changes::metadataFor) {
                                    PairedScreen(HOST, onContinue = {}, random = Random(1))
                                }
                            },
                    )
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    /** [key] in place of the screen on top, as the ceremony moves the stack on. */
    private fun go(key: OnboardingKey) {
        stack[stack.lastIndex] = key
        rule.waitForIdle()
    }

    private fun frames(count: Int) = rule.mainClock.advanceTimeBy(count * FRAME_MILLIS)

    private fun marks(): List<SemanticsNode> = rule.onAllNodesWithTag(MARK_KEY).fetchSemanticsNodes()

    /** The width of each mark drawn, in px. */
    private fun widths(): List<Float> = marks().map { it.size.width.toFloat() }

    @Test
    fun `the mark shrinks from Connecting into Verify, and grows back into Paired, between the two sizes as it goes`() {
        show()
        assertEquals(listOf(LARGE), widths())
        go(OnboardingKey.Verify)
        frames(8)
        assertBetween(widths())
        frames(60)
        assertEquals(listOf(SMALL), widths())
        go(OnboardingKey.Paired)
        frames(8)
        assertBetween(widths())
        frames(90)
        assertEquals(listOf(LARGE), widths())
    }

    @Test
    fun `the mark shrinks on the standard scheme's defaultSpatial spring, frame by frame`() {
        show()
        go(OnboardingKey.Verify)
        val seen = List(MOVE_FRAMES) { frame -> frame to widths().also { rule.mainClock.advanceTimeByFrame() } }
        // Until it lands: the screen going out draws its own mark again for a frame as the change ends, unseen.
        val landed = seen.indexOfFirst { (_, now) -> now.isNotEmpty() && now.all { it == SMALL } }
        assertTrue("never landed: $seen", landed > START_FRAMES)
        // 7.3: "on defaultSpatial", the standard scheme's 0.9 and 700 (ScreenChange.position). The spring starts on one
        // of the change's first frames; on that start, every mark drawn is within a pixel of it on every frame.
        val fits =
            (0..START_FRAMES).map { start ->
                seen.subList(start, landed + 1).maxOf { (frame, now) ->
                    val expected = ScreenChange.position.valueAt((frame - start) * FRAME_MILLIS.toFloat(), LARGE, SMALL)
                    now.maxOfOrNull { abs(it - expected) } ?: Float.MAX_VALUE
                }
            }
        assertTrue("off the spring by ${fits.min()} px at best, of $fits: $seen", fits.min() <= 1f)
        assertTrue(seen.any { (_, now) -> now.any { it > SMALL + 8f && it < LARGE - 8f } })
    }

    private fun assertBetween(now: List<Float>) {
        assertTrue("marks $now", now.isNotEmpty())
        for (width in now) assertTrue("a mark $width px across", width > SMALL && width < LARGE)
    }

    @Test
    fun `under Remove animations the mark is at Verify's size on the change's first frame`() {
        show(scale = 0f)
        go(OnboardingKey.Verify)
        for (frame in 0 until 8) {
            rule.mainClock.advanceTimeByFrame()
            val now = widths()
            assertTrue("frame $frame: $now", now.all { it == LARGE || it == SMALL })
        }
        assertEquals(listOf(SMALL), widths())
    }

    @Test
    fun `the mark says nothing to a screen reader, moving or not`() {
        show()
        assertSilent()
        go(OnboardingKey.Verify)
        frames(8)
        assertSilent()
    }

    private fun assertSilent() {
        for (mark in marks()) {
            assertEquals("", labelOf(mark.config))
            assertTrue(mark.children.isEmpty())
        }
    }
}
