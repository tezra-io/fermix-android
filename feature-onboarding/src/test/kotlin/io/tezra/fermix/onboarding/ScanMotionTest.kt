package io.tezra.fermix.onboarding

import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.HintShake
import io.tezra.fermix.design.ReticleMotion
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

private const val HINT = "Point at the code on your computer"
private const val NOT_FERMIX = "That's not a Fermix pairing code."
private val SCAN_ACTIONS = ScanActions({}, {}, {}, {}, {})

/**
 * Scan's motion on a test clock (the M51 update's 7.4): the reticle settles from 108 % as it fades in, once a visit,
 * as the camera is allowed, breathes between 100 % and 102 % while it searches, and on a Fermix code stops breathing,
 * locks onto it at 66 % and flashes; a refused code's hint cross-fades and shakes once. Under Remove animations the
 * reticle stands still, locks at once and never flashes, and the hint simply changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ScanMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val rig = MotionRig(rule)
    private var found by mutableStateOf(false)
    private var refused by mutableStateOf(false)
    private var cameraAllowed by mutableStateOf(true)
    private var visit by mutableIntStateOf(0)
    private var reticle: () -> ReticlePose = { ReticlePose.Rest }
    private var shake: () -> Float = { 0f }

    private fun showReticle(scale: Float = 1f) =
        rig.show(scale) {
            reticle =
                rememberReticle(shown = cameraAllowed, found = found, visit = visit)
        }

    @Test
    fun `the reticle settles from 108 percent as it fades in, over 300 ms`() {
        showReticle()
        assertTrue("from ${reticle()}", reticle().scale > 1.07f && reticle().shown < 0.05f)
        rig.advanceTo(ReticleMotion.ENTER_MILLIS / 2L)
        assertTrue("mid-way ${reticle()}", reticle().scale in 1f..1.07f && reticle().shown in 0.5f..0.99f)
        rig.advanceTo(ReticleMotion.ENTER_MILLIS.toLong())
        assertEquals(1f, reticle().shown)
        // Settled, breathing as it has from the first frame, as the reference player has it.
        assertTrue("settled ${reticle()}", reticle().scale in 1f..1.02f)
    }

    @Test
    fun `on a first pairing the reticle settles in once the camera is allowed, not while it is asked for`() {
        // The rationale and the system's prompt stand in the reticle's place for a while first.
        cameraAllowed = false
        showReticle()
        rig.advanceTo(1_500L)
        cameraAllowed = true
        rig.frameAfterWrite()
        // The frame that first draws it: 7.4's start, at 108 % and not yet faded in.
        assertEquals(ReticleMotion.ENTER_FROM, reticle().scale, 0.001f)
        assertEquals(0f, reticle().shown)
        val start = rig.at
        rig.advanceTo(start + ReticleMotion.ENTER_MILLIS / 2L)
        assertTrue("mid-way ${reticle()}", reticle().scale in 1f..1.07f && reticle().shown in 0.5f..0.99f)
        rig.advanceTo(start + ReticleMotion.ENTER_MILLIS + 16L)
        assertEquals(1f, reticle().shown)
        assertTrue("settled ${reticle()}", reticle().scale in 1f..1.02f)
    }

    @Test
    fun `each visit settles the reticle in anew as the scan comes back, and a rotation within one does not`() {
        showReticle()
        rig.advanceTo(400L)
        assertEquals(1f, reticle().shown)
        // Back to the scan from Connecting, a failure or Verify's Cancel: its next visit, its entry composed anew from
        // what it saved as it went.
        visit = 1
        rig.restore()
        assertEquals(ReticleMotion.ENTER_FROM, reticle().scale, 0.001f)
        assertEquals(0f, reticle().shown)
        rig.advanceTo(rig.at + ReticleMotion.ENTER_MILLIS / 2L)
        assertTrue("mid-way ${reticle()}", reticle().shown in 0.5f..0.99f)
        rig.advanceTo(rig.at + ReticleMotion.ENTER_MILLIS)
        assertEquals(1f, reticle().shown)
        rig.restore()
        repeat(10) {
            assertEquals(1f, reticle().shown)
            rig.frames(1)
        }
    }

    @Test
    fun `a rotation mid-settle finds the reticle settled`() {
        showReticle()
        rig.advanceTo(ReticleMotion.ENTER_MILLIS / 2L)
        assertTrue("mid-way ${reticle()}", reticle().shown < 0.99f)
        rig.restore()
        repeat(10) {
            assertEquals(1f, reticle().shown)
            assertTrue("settled ${reticle()}", reticle().scale in 1f..1.02f)
            rig.frames(1)
        }
    }

    @Test
    fun `Remove animations turned on mid-breath stands the reticle at 100 percent`() {
        showReticle()
        rig.advanceTo(816L)
        assertTrue("breathing at ${reticle().scale}", reticle().scale > 1.019f)
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        rig.frameAfterWrite()
        repeat(5) {
            assertEquals(ReticlePose.Rest, reticle())
            rig.frames(1)
        }
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `while it searches the reticle breathes between 100 and 102 percent, 1,600 ms a breath`() {
        showReticle()
        rig.advanceTo(ReticleMotion.ENTER_MILLIS.toLong())
        val breath = mutableListOf<Pair<Long, Float>>()
        repeat(200) {
            rig.frames(1)
            breath += rig.at to reticle().scale
        }
        val (deepest, most) = breath.maxBy { it.second }
        val (shallowest, least) = breath.minBy { it.second }
        assertTrue("breathed in to $most", abs(most - 1.02f) < 0.0005f)
        assertTrue("breathed out to $least", abs(least - 1f) < 0.0005f)
        assertTrue("$deepest ms from $shallowest", abs(abs(deepest - shallowest) - 800L) <= 32L)
    }

    @Test
    fun `on a Fermix code the breath stops, the reticle locks to 66 percent in 180 ms, and flashes to 14 percent`() {
        showReticle()
        rig.advanceTo(1_000L)
        found = true
        rig.frameAfterWrite()
        val start = rig.at
        var brightest = 0f
        var brightestAt = 0L
        while (rig.at < start + 300L) {
            rig.frames(1)
            if (reticle().flash > brightest) {
                brightest = reticle().flash
                brightestAt = rig.at - start
            }
            if (rig.at - start >=
                180L
            ) {
                assertTrue("${reticle().scale} at ${rig.at - start}", abs(reticle().scale - 0.66f) < 0.01f)
            }
        }
        assertTrue(
            "flashed to $brightest at $brightestAt ms",
            abs(brightest - 0.14f) < 0.01f && brightestAt in 40L..80L,
        )
        assertEquals(0f, reticle().flash)
        rig.frames(20)
        assertEquals(0.66f, reticle().scale, 0.001f)
        assertFalse("still asking for frames", rig.framesAsked())
    }

    @Test
    fun `a rotation mid-lock finds the reticle locked at 66 percent with its flash over`() {
        showReticle()
        rig.advanceTo(1_000L)
        found = true
        rig.frameAfterWrite()
        rig.advanceTo(rig.at + 48L)
        assertTrue("flashing at ${reticle().flash}", reticle().flash > 0.05f)
        rig.restore()
        repeat(30) {
            assertEquals(ReticlePose(scale = ReticleMotion.LOCKED), reticle())
            rig.frames(1)
        }
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `under Remove animations the reticle stands still, locks at once and never flashes`() {
        showReticle(scale = 0f)
        assertEquals(ReticlePose.Rest, reticle())
        rig.frames(50)
        assertEquals(ReticlePose.Rest, reticle())
        assertFalse(rig.framesAsked())
        found = true
        rig.frameAfterWrite()
        repeat(20) {
            assertEquals(ReticlePose(scale = ReticleMotion.LOCKED), reticle())
            rig.frames(1)
        }
        assertFalse(rig.framesAsked())
    }

    @Test
    fun `the reticle asks for no more frames once its screen has left`() {
        showReticle()
        rig.frames(40)
        assertTrue(rig.framesAsked())
        rig.remove()
        assertFalse(rig.framesAsked())
    }

    private fun showShake(scale: Float = 1f) = rig.show(scale) { shake = rememberShake(refused) }

    @Test
    fun `a refused code shakes the hint once, three cycles dying away over 300 ms`() {
        showShake()
        rig.frames(10)
        assertEquals(0f, shake())
        refused = true
        rig.frameAfterWrite()
        val start = rig.at
        val offsets = mutableListOf<Float>()
        while (rig.at < start + HintShake.MILLIS + 32L) {
            offsets += shake()
            rig.frames(1)
        }
        val turns = offsets.filter { it != 0f }.zipWithNext().count { (a, b) -> a.sign != b.sign }
        assertTrue("turned $turns times", turns in 4..6)
        assertTrue(offsets.all { abs(it) <= 1f })
        assertEquals(0f, shake())
        rig.restore()
        repeat(10) {
            assertEquals(0f, shake())
            rig.frames(1)
        }
    }

    @Test
    fun `a rotation mid-shake stands the hint still, and it does not shake again`() {
        showShake()
        refused = true
        rig.frameAfterWrite()
        rig.advanceTo(rig.at + 96L)
        assertTrue("shaken by ${shake()}", shake() != 0f)
        rig.restore()
        repeat(25) {
            assertEquals(0f, shake())
            rig.frames(1)
        }
    }

    @Test
    fun `under Remove animations the hint does not shake`() {
        showShake(scale = 0f)
        refused = true
        rig.frameAfterWrite()
        repeat(25) {
            assertEquals(0f, shake())
            rig.frames(1)
        }
    }

    private fun showScan(scale: Float = 1f) =
        rig.show(scale) {
            ScanScreen(state = ScanUi(refused = refused, torchOn = null, found = found), actions = SCAN_ACTIONS)
        }

    private fun shown(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the hint cross-fades to the refusal over 200 ms, which TalkBack is read as it comes`() {
        showScan()
        refused = true
        rig.frameAfterWrite()
        rig.advanceTo(rig.at + 100L)
        assertTrue(shown(HINT) && shown(NOT_FERMIX))
        val live =
            rule
                .onNodeWithText(NOT_FERMIX)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(LiveRegionMode.Polite, live)
        rig.advanceTo(rig.at + 150L)
        assertFalse(shown(HINT))
        assertTrue(shown(NOT_FERMIX))
    }

    @Test
    fun `under Remove animations the hint simply changes`() {
        showScan(scale = 0f)
        refused = true
        rig.frameAfterWrite()
        assertFalse(shown(HINT))
        assertTrue(shown(NOT_FERMIX))
    }
}
