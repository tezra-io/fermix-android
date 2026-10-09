package io.tezra.fermix.onboarding

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.ReticleMotion
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The scan's hint while it searches, under the reticle. */
private const val HINT = "Point at the code on your computer"

/** The window's width, as the rule's qualifiers give it. */
private val WINDOW = 412.dp

/** The canon's reticle (ScanMotion): 240 dp across, its corners drawn 3 dp wide, and 28 dp above the hint. */
private val RETICLE_HALF = 120.dp
private val RETICLE_EDGE = 118.5.dp
private val RETICLE_TO_HINT = 28.dp

/** The middle of a top corner's arm, from the reticle's centre, at its own size. */
private val ARM_MIDDLE = 90.dp

/** How far up and down from where an edge is expected its pixels are looked for. */
private val EDGE_SLACK = 6.dp

/** A red past which a pixel is the reticle's white, and not the camera's dark under it. */
private const val WHITE_RED = 0xC0

/** The frames a back swipe is held for: the reticle's settle and more. */
private const val HELD_FRAMES = 30

/** The frames the reticle takes to settle in and fade in whole, its 300 ms and one more. */
private const val SETTLE_FRAMES = 20

/**
 * The scan as the way back comes into it, in NavDisplay as the app draws it (the M51 update's 7.2 and 7.4): a back
 * swipe from Connecting draws the scan searching, its reticle settling in for its next visit, never locked on the code
 * it found before; letting the swipe go moves nothing; and the scan going out to Connecting stands locked on its code.
 * The reticle's place is read off the frames Robolectric's native graphics draw: its top edge, white on the camera's
 * dark, at the 66 % a lock holds it to or at the 100 % to 102 % of the search.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ScanReturnTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val main = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val owner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = PromptRegistry(onAnswer = {})
        }

    @Before
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @After
    fun mainBack() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    /** A ViewModel on Scan, past the gate, its ceremonies [starter]'s fakes, kept in [viewModels]. */
    private fun scanningModel(starter: FakeStarter): OnboardingViewModel {
        val store = instanceStore(folder.root, CoroutineScope(main))
        val parts =
            OnboardingParts(
                gate = { GateResult.Ok },
                pairings = starter,
                identity = PhoneIdentity(PHONE, "Google Pixel 9 Pro", "0.1.0"),
                instances = store,
                network = MutableStateFlow(NetworkFacts.NONE),
                pairingDispatcher = main,
                pairingWait = MutableStateFlow(null),
                handover = FakeHandover(store.instances),
                notifications = { _, _ -> },
            )
        val model =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { OnboardingViewModel(parts) } })
                .get(OnboardingViewModel::class)
        model.getStarted()
        model.scan()
        return model
    }

    private fun show(model: OnboardingViewModel) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                FermixTheme(darkTheme = false) {
                    OnboardingTransitions { changes ->
                        val stack by model.stack.collectAsState()
                        NavDisplay(
                            backStack = listOf<NavKey>(OnboardingKey.Welcome) + stack,
                            onBack = model::back,
                            entryProvider =
                                entryProvider { onboardingEntries(this, model, NO_CAMERA, FakeClip(null), changes) },
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    /** [count] frames of the screens' clock, and as long on the ViewModel's. */
    private fun frames(count: Int) =
        repeat(count) {
            rule.waitForIdle()
            rule.mainClock.advanceTimeByFrame()
            main.scheduler.advanceTimeBy(16L)
            main.scheduler.runCurrent()
        }

    /** Whether the frame on screen draws the reticle's top edge at [scale] of its size, about its centre. */
    private fun edgeAt(scale: Float): Boolean {
        val hint = rule.onNodeWithText(HINT).getUnclippedBoundsInRoot()
        val centre = hint.top - RETICLE_TO_HINT - RETICLE_HALF
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        return image.whiteIn(x = WINDOW / 2 - ARM_MIDDLE * scale, y = centre - RETICLE_EDGE * scale)
    }

    /** Whether any pixel of the column at [x], within [EDGE_SLACK] of [y], is the reticle's white. */
    private fun Bitmap.whiteIn(
        x: Dp,
        y: Dp,
    ): Boolean {
        val px = with(rule.density) { x.roundToPx() }
        val from = with(rule.density) { (y - EDGE_SLACK).roundToPx() }
        val to = with(rule.density) { (y + EDGE_SLACK).roundToPx() }
        return (from..to).any { py -> (getPixel(px, py) shr 16 and 0xFF) > WHITE_RED }
    }

    private fun scanDrawn(): Boolean = rule.onAllNodesWithText(HINT).fetchSemanticsNodes().isNotEmpty()

    private fun locked(): Boolean = edgeAt(ReticleMotion.LOCKED)

    private fun searching(): Boolean = edgeAt(1f) || edgeAt(1.02f)

    /**
     * [model] on Connecting from a Fermix code the scan found, the scan going out standing locked on the code as long
     * as it is drawn whole, and the change into Connecting over.
     */
    private fun foundAndGone(model: OnboardingViewModel) {
        frames(HELD_FRAMES)
        assertTrue("the scan searching before the code", searching() && !locked())
        model.found(readLink(linkText()) as LinkOutcome.Link)
        frames(12)
        assertTrue("the reticle locked on the code", locked())
        var waited = 0
        while (model.stack.value.last() == OnboardingKey.Scan && waited < HELD_FRAMES) {
            frames(1)
            waited++
        }
        assertEquals(OnboardingKey.Connecting, model.stack.value.last())
        repeat(2) { frame ->
            frames(1)
            assertTrue("frame $frame of the scan going out: locked", locked())
        }
        frames(HELD_FRAMES)
    }

    @Test
    fun `a back swipe from Connecting draws the scan searching, never locked, and letting it go moves nothing`() {
        val model = scanningModel(FakeStarter())
        show(model)
        foundAndGone(model)
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        // Halfway, the change's 150 ms: Connecting has faded out, and the scan comes in, its reticle settling in, from
        // the frame NavDisplay first draws it.
        val drawn = mutableListOf<Int>()
        for (frame in 0 until HELD_FRAMES) {
            frames(1)
            if (!scanDrawn()) continue
            drawn += frame
            assertFalse("frame $frame of the swipe draws the reticle locked", locked())
            if (frame >= drawn.first() + SETTLE_FRAMES) assertTrue("frame $frame: searching", searching())
        }
        assertTrue("the swipe drew the scan from frame ${drawn.firstOrNull()}", drawn.size > SETTLE_FRAMES + 2)
        rule.runOnUiThread { dispatcher.onBackPressed() }
        for (frame in 0 until HELD_FRAMES) {
            frames(1)
            assertFalse("frame $frame after letting go draws the reticle locked", locked())
            assertTrue("frame $frame after letting go: searching", searching())
        }
        assertEquals(OnboardingKey.Scan, model.stack.value.last())
    }
}

/** A camera the screens may use, which shows nothing and reads nothing. */
private val NO_CAMERA = ScanCamera(allowed = { true }, preview = { _, _ -> })
