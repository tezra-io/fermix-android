package io.tezra.fermix.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.FermixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.random.Random

/**
 * Welcome's words in the order they rise, as a test finds them: the title, the Fermix wordmark, by the description
 * TalkBack reads, as it draws no text; the tagline; the actions.
 */
private val WELCOME_WORDS =
    listOf(
        hasContentDescription("Fermix"),
        hasText("Your agent. Your machine."),
        hasText("Get started"),
        hasText("Don't have Fermix yet?"),
    )

/** A pixel's alpha when it is opaque. */
private const val OPAQUE = 255.0

/** One frame of the test clock. */
private const val FRAME_MILLIS = 16L

/** A phone on its side, as Medium_Phone_API_36.1 draws it turned: its status bar and its navigation bar, 24 dp each. */
private val SYSTEM_BAR = 24.dp

/** The drop's first dot at 112 dp, in xhdpi's px²: an ellipse 5 by 7.5 of the mark's units, each 2.24 px. */
private val FIRST_DOT_AREA = PI.toFloat() * 5f * 7.5f * 2.24f * 2.24f

/**
 * The screen's view, which keeps every haptic the screen plays with the test clock's time it played at: a view group,
 * as a ripple looks for one above its view.
 */
private class HapticRecorder(
    context: Context,
    private val now: () -> Long,
) : FrameLayout(context) {
    val played = mutableListOf<Pair<Int, Long>>()

    override fun performHapticFeedback(feedbackConstant: Int): Boolean {
        played += feedbackConstant to now()
        return true
    }
}

/**
 * Welcome's drop and Paired's hop on a test clock (the M51 update's sections 3.5, 4, 5 and 7.4): the words rise in at
 * their times, and TalkBack reaches them before they do; each moment plays once and not again after the screen is
 * restored or come back to; reduced motion opens on the last frame, the mark with the words, even when it is turned on
 * mid-drop, and draws nothing more; each haptic plays once at its time, and the approval's even when Paired is restored
 * before it; and a screen that leaves asks for no more frames. The rule's clock moves only when told, a frame at a
 * time; Robolectric's native graphics draw the frames.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class MarkMomentsTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val canvas = FermixColors.Light.canvas.toArgb()
    private var shown by mutableStateOf(true)
    private lateinit var haptics: HapticRecorder

    /** How many times the screen was restored: each composes it anew from the state it saved. */
    private var restorations by mutableIntStateOf(0)

    /** Whether the screen is composed at all: a restoration disposes it, then composes it again. */
    private var composed by mutableStateOf(true)

    /** The registry the screen saves its state in, and what the last restoration handed back. */
    private var registry: SaveableStateRegistry? = null
    private var restored: Map<String, List<Any?>>? = null

    /** [screen] composed as [compose] puts it, and its first frame drawn, the clock's time then 0. */
    private fun show(
        scale: Float = 1f,
        screen: @Composable () -> Unit,
    ) {
        compose(scale, screen)
        rule.mainClock.advanceTimeByFrame()
    }

    /**
     * [screen] composed in light mode at the animator duration [scale], before the clock's first frame: its saved
     * state kept in a registry of the test's own over the activity's, which takes what the activity's takes, and kept
     * apart while it is not [shown], as the back stack keeps a screen under another. The window stays full size while
     * nothing is in it.
     */
    private fun compose(
        scale: Float,
        screen: @Composable () -> Unit,
    ) {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        haptics = HapticRecorder(rule.activity) { rule.mainClock.currentTime }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val activity = checkNotNull(LocalSaveableStateRegistry.current)
            val saving =
                remember(restorations) { SaveableStateRegistry(restored, activity::canBeSaved).also { registry = it } }
            CompositionLocalProvider(LocalView provides haptics, LocalSaveableStateRegistry provides saving) {
                FermixTheme(darkTheme = false) {
                    Box(modifier = Modifier.fillMaxSize()) { if (composed) Kept(screen) }
                }
            }
        }
    }

    /** [screen] while it is [shown], its saved state kept while it is not. */
    @Composable
    private fun Kept(screen: @Composable () -> Unit) {
        val saved = rememberSaveableStateHolder()
        if (shown) saved.SaveableStateProvider("screen") { screen() }
    }

    /**
     * The screen's state saved, the screen disposed, a frame drawn without it, and the screen composed anew from what
     * it saved, at the same place, as a rotation, a fold or the process's restoration does; its first frame drawn.
     */
    private fun restore() {
        restored = checkNotNull(registry).performSave()
        composed = false
        frameAfterWrite()
        // The control: no words are left on screen, so what comes back is composed anew.
        assertTrue(rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().isEmpty())
        restorations++
        composed = true
        frameAfterWrite()
    }

    /**
     * One frame once the test's write to the screen's state reaches the composition: the main looper runs the
     * snapshot's apply notifications, which the clock's frames alone do not, and the frame then recomposes.
     */
    private fun frameAfterWrite() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
    }

    /** Where the moment's clock is, in ms: it started at the screen's first frame. */
    private var at = 0L

    /** The clock moved on to its first frame at or after [ms]. */
    private fun advanceTo(ms: Long) = advanceFrames((ms - at + FRAME_MILLIS - 1) / FRAME_MILLIS)

    /** The clock moved on to its last frame before [ms]. */
    private fun advanceToJustBefore(ms: Long) = advanceFrames((ms - 1 - at) / FRAME_MILLIS)

    private fun advanceFrames(frames: Long) {
        rule.mainClock.advanceTimeBy(frames * FRAME_MILLIS)
        at += frames * FRAME_MILLIS
    }

    private fun screen(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    private fun words(word: SemanticsMatcher): Bitmap = rule.onNode(word).captureToImage().asAndroidBitmap()

    private fun words(text: String): Bitmap = words(hasText(text))

    /** Whether nothing is drawn: a node's image is its own drawing, clear where it draws nothing, or the canvas. */
    private fun Bitmap.blank(): Boolean {
        val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
        return pixels.all { it == canvas || Color.alpha(it) == 0 }
    }

    /**
     * How much of [image] the ink covers, in px²: each pixel, laid over the canvas where it is clear, counts for how
     * far it is from the canvas towards the ink, so the anti-aliased edge of a shape counts for what it covers.
     */
    private fun inkArea(image: Bitmap): Float {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val ink = Color.red(FermixColors.Light.ink.toArgb()).toDouble()
        val paper = Color.red(canvas).toDouble()
        return pixels
            .sumOf { pixel ->
                val opacity = Color.alpha(pixel) / OPAQUE
                val red = Color.red(pixel) * opacity + paper * (1.0 - opacity)
                ((paper - red) / (paper - ink)).coerceIn(0.0, 1.0)
            }.toFloat()
    }

    /** Remove animations turned on or off, as the setting's change reaches the screen on the next frame. */
    private fun removeAnimations(removed: Boolean) {
        val scale = if (removed) 0f else 1f
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        frameAfterWrite()
    }

    /** The window's status bar and navigation bar, [SYSTEM_BAR] each, as the platform hands them to the screen. */
    private fun systemBars() {
        val bar = with(rule.density) { SYSTEM_BAR.roundToPx() }
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, bar, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bar))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
        ViewCompat.dispatchApplyWindowInsets(composeViewOf(rule.activity), insets)
        rule.waitForIdle()
    }

    /** Whether anything in the composition waits for a frame. */
    private fun framesAsked(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }

    /** Whether TalkBack reaches [word]'s node: the platform's node for it is visible to the user. */
    private fun reachable(word: SemanticsMatcher): Boolean {
        val node = rule.onNode(word).fetchSemanticsNode()
        val composeView = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val provider = checkNotNull(composeView.getChildAt(0).accessibilityNodeProvider)
        return provider.createAccessibilityNodeInfo(node.id)?.isVisibleToUser == true
    }

    @Composable
    private fun WelcomeUnderTest() = WelcomeScreen(onGetStarted = {}, onNoFermix = {}, random = Random(1))

    @Composable
    private fun PairedUnderTest() = PairedScreen(host = HOST, onContinue = {}, random = Random(1))

    @Test
    fun `before 1,100 ms Welcome draws no word, and from 1,740 ms it draws them all`() {
        show { WelcomeUnderTest() }
        advanceToJustBefore(1_100)
        for (word in WELCOME_WORDS) assertTrue("${word.description} drawn at $at ms", words(word).blank())
        advanceTo(1_740)
        val landed = WELCOME_WORDS.map { words(it) }
        advanceTo(3_000)
        for ((word, image) in WELCOME_WORDS.zip(landed)) {
            assertFalse("${word.description} not drawn at 1,740 ms", image.blank())
            assertTrue("${word.description} still moving at 1,740 ms", image.sameAs(words(word)))
        }
    }

    @Test
    fun `Welcome's words rise in order, the title from 1,100 ms, the tagline from 1,210 and the actions from 1,320`() {
        show { WelcomeUnderTest() }
        val (title, tagline) = WELCOME_WORDS
        val actions = WELCOME_WORDS.drop(2)
        advanceTo(1_100)
        assertFalse("the title not drawn at $at ms", words(title).blank())
        for (word in listOf(tagline) + actions) assertTrue("${word.description} drawn at $at ms", words(word).blank())
        advanceTo(1_210)
        assertFalse("the tagline not drawn at $at ms", words(tagline).blank())
        for (word in actions) assertTrue("${word.description} drawn at $at ms", words(word).blank())
        advanceTo(1_320)
        for (word in actions) assertFalse("${word.description} not drawn at $at ms", words(word).blank())
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-xhdpi")
    fun `on a phone on its side the drop's first dot is drawn whole and Get started stands in view`() {
        compose(scale = 1f) { WelcomeUnderTest() }
        systemBars()
        // As the screen first draws, the dot is all the ink there is: its whole ellipse, none of it cut by the top.
        assertEquals(FIRST_DOT_AREA, inkArea(screen()), FIRST_DOT_AREA * 0.05f)
        advanceTo(1_800)
        val safeBottom = rule.onRoot().getUnclippedBoundsInRoot().bottom - SYSTEM_BAR
        val bottom = rule.onNodeWithText("Get started").getUnclippedBoundsInRoot().bottom
        assertTrue("Get started ends at $bottom, under the bar from $safeBottom", bottom <= safeBottom)
    }

    @Test
    fun `TalkBack reaches Welcome's words and actions from the first frame, before they rise in`() {
        val accessibility = shadowOf(rule.activity.getSystemService(AccessibilityManager::class.java))
        accessibility.setEnabled(true)
        accessibility.setTouchExplorationEnabled(true)
        show { WelcomeUnderTest() }
        for (word in WELCOME_WORDS) {
            // The control: the words are not drawn yet.
            assertTrue("${word.description} drawn at $at ms", words(word).blank())
            assertTrue("${word.description} hidden from TalkBack at $at ms", reachable(word))
        }
    }

    @Test
    fun `the drop does not play again once Welcome is restored`() {
        show { WelcomeUnderTest() }
        val first = screen()
        advanceTo(1_800)
        val settled = screen()
        // The control: the drop plays, so a replay would show; one frame draws Welcome again, where a replay would
        // only have begun.
        assertFalse(first.sameAs(settled))
        restore()
        assertTrue("the drop played again", settled.sameAs(screen()))
        assertEquals(listOf(HapticFeedbackConstants.CLOCK_TICK), haptics.played.map { it.first })
    }

    @Test
    fun `coming back to Welcome shows the resting mark and the words at once`() {
        show { WelcomeUnderTest() }
        advanceTo(1_800)
        val settled = screen()
        shown = false
        frameAfterWrite()
        // The control: Welcome left, as it does under Pair on the back stack.
        assertTrue(rule.onAllNodes(WELCOME_WORDS.first()).fetchSemanticsNodes().isEmpty())
        shown = true
        frameAfterWrite()
        assertTrue("the drop played again", settled.sameAs(screen()))
    }

    @Test
    fun `Welcome restored before the dot lands plays no CLOCK_TICK, as no landing is drawn`() {
        show { WelcomeUnderTest() }
        advanceTo(200)
        restore()
        advanceTo(3_000)
        assertEquals(emptyList<Pair<Int, Long>>(), haptics.played)
    }

    @Test
    fun `with animations removed Welcome opens on its last frame, and nothing moves or asks for a frame`() {
        compose(scale = 0f) { WelcomeUnderTest() }
        // As the screen first draws, before the clock's first frame.
        val first = screen()
        for (word in WELCOME_WORDS) {
            assertFalse("${word.description} not drawn on the first frame", words(word).blank())
        }
        advanceTo(6_000)
        assertTrue("Welcome moved under reduced motion", first.sameAs(screen()))
        assertFalse(framesAsked())
        assertEquals(emptyList<Pair<Int, Long>>(), haptics.played)
    }

    @Test
    fun `Remove animations turned on mid-drop shows the last frame on the next one, the mark with the words`() {
        show { WelcomeUnderTest() }
        advanceTo(600)
        val dropping = screen()
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        frameAfterWrite()
        val next = screen()
        advanceTo(6_000)
        val last = screen()
        // The control: the drop was under way.
        assertFalse(dropping.sameAs(last))
        assertTrue("the mark and the words parted", next.sameAs(last))
    }

    @Test
    fun `Welcome opened with animations removed plays no CLOCK_TICK once they are back on, as nothing lands`() {
        compose(scale = 0f) { WelcomeUnderTest() }
        advanceTo(2_000)
        removeAnimations(false)
        advanceTo(4_000)
        // The control: the idle breathes, so the moment runs again with the animations on.
        assertTrue(framesAsked())
        assertEquals(emptyList<Pair<Int, Long>>(), haptics.played)
    }

    @Test
    fun `Remove animations turned on before the dot lands, then off, plays no CLOCK_TICK`() {
        show { WelcomeUnderTest() }
        advanceTo(200)
        removeAnimations(true)
        advanceTo(2_000)
        removeAnimations(false)
        advanceTo(4_000)
        assertTrue(framesAsked())
        assertEquals(emptyList<Pair<Int, Long>>(), haptics.played)
    }

    @Test
    fun `the dot lands with one CLOCK_TICK at 380 ms`() {
        show { WelcomeUnderTest() }
        val start = rule.mainClock.currentTime
        advanceToJustBefore(380)
        assertEquals(emptyList<Pair<Int, Long>>(), haptics.played)
        advanceTo(380)
        advanceTo(6_000)
        val (constant, playedAt) = haptics.played.single()
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, constant)
        assertTrue("played at ${playedAt - start} ms", playedAt - start in 380 until 380 + FRAME_MILLIS)
    }

    @Test
    fun `Paired plays PairApproved once, 90 ms into the hop, not as it is composed`() {
        show { PairedUnderTest() }
        val start = rule.mainClock.currentTime
        assertEquals(emptyList<Pair<Int, Long>>(), haptics.played)
        advanceToJustBefore(90)
        assertEquals(emptyList<Pair<Int, Long>>(), haptics.played)
        advanceTo(90)
        advanceTo(3_000)
        val (constant, playedAt) = haptics.played.single()
        assertEquals(HapticFeedbackConstants.CONFIRM, constant)
        assertTrue("played at ${playedAt - start} ms", playedAt - start in 90 until 90 + FRAME_MILLIS)
    }

    @Test
    fun `Paired's title rises in from 250 ms and has landed at 670 ms`() {
        show { PairedUnderTest() }
        val title = "Paired with $HOST"
        advanceToJustBefore(250)
        assertTrue(words(title).blank())
        advanceTo(670)
        val landed = words(title)
        advanceTo(2_000)
        assertFalse(landed.blank())
        assertTrue(landed.sameAs(words(title)))
    }

    @Test
    fun `the hop does not play again once Paired is restored`() {
        show { PairedUnderTest() }
        advanceTo(200)
        val hopping = screen()
        advanceTo(700)
        val settled = screen()
        assertFalse(hopping.sameAs(settled))
        restore()
        assertTrue("the hop played again", settled.sameAs(screen()))
        assertEquals(listOf(HapticFeedbackConstants.CONFIRM), haptics.played.map { it.first })
    }

    @Test
    fun `Paired restored before the mark leaves the ground still confirms the approval, once`() {
        show { PairedUnderTest() }
        advanceTo(32)
        restore()
        advanceTo(3_000)
        assertEquals(listOf(HapticFeedbackConstants.CONFIRM), haptics.played.map { it.first })
    }

    @Test
    fun `with animations removed Paired opens happy and still, and still confirms the approval`() {
        compose(scale = 0f) { PairedUnderTest() }
        // As the screen first draws, before the clock's first frame.
        val first = screen()
        assertFalse(words("Paired with $HOST").blank())
        advanceTo(3_000)
        assertTrue(first.sameAs(screen()))
        assertFalse(framesAsked())
        assertEquals(listOf(HapticFeedbackConstants.CONFIRM), haptics.played.map { it.first })
    }

    @Test
    fun `once Welcome leaves, nothing asks for another frame`() = assertStopsAsItLeaves { WelcomeUnderTest() }

    @Test
    fun `once Paired leaves, nothing asks for another frame`() = assertStopsAsItLeaves { PairedUnderTest() }

    private fun assertStopsAsItLeaves(screen: @Composable () -> Unit) {
        show(screen = screen)
        advanceTo(2_500)
        // The control: the idle breathes, frame after frame.
        assertTrue(framesAsked())
        shown = false
        frameAfterWrite()
        rule.mainClock.advanceTimeByFrame()
        assertTrue(rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().isEmpty())
        assertFalse(framesAsked())
    }
}
