package io.tezra.fermix.onboarding

import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** One frame of the test clock. */
private const val FRAME_MILLIS = 16L

/** The frames a cut under Remove animations is watched for, longer than NavDisplay takes to settle one. */
private const val CUT_FRAMES = 8

/** The frames a change is watched for, its 300 ms and a little more. */
private const val CHANGE_FRAMES = 24

/** How near an opacity read off a frame is to the table's: a pixel's 8 bits, and a little more. */
private const val OPACITY = 0.01f

/** A whole back swipe's steps, a tenth of the way each. */
private const val SWIPE_STEPS = 10

/** The window's width, as the rule's qualifiers give it. */
private val WINDOW = 412.dp

private val FAILURE = OnboardingKey.Failure(FailureCase.EXPIRED)

/** Each stand-in's block, at its own place along the top: its side, its gap, and the ink it is drawn in. */
private val BLOCK = 40.dp
private val BLOCK_GAP = 64.dp
private val INK = Color.Black

/** The stand-ins, in the order their blocks sit along the top. */
private val STAND_INS = listOf("Welcome", "Pair", "Scan", "Failure")

/** Each stand-in's band, its opacity's probe: a stripe of ink across the window's middle, at a height of its own. */
private val BAND = 60.dp
private val BAND_GAP = 80.dp
private val BANDS_TOP = 300.dp
private val BAND_START = 60.dp
private val BAND_WIDTH = 292.dp

/**
 * Onboarding's screen changes in Navigation 3's NavDisplay on a test clock (the M51 update's 7.2), each screen a
 * stand-in tagged with its name and carrying the metadata the entries carry: the shared axis, its incoming screen from
 * the end side going forward and from the start side going back, mirrored right to left; fade through, which slides
 * nothing and scales its incoming screen up; a back swipe that scrubs the pop, the shared axis or fade through, and
 * settles back when cancelled; and under Remove animations every change a cut, one screen drawn on every frame. Each
 * stand-in draws a block of ink at a place of its own, so a frame's image tells which screens it draws; Robolectric's
 * native graphics draw the frames.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ScreenChangesTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val stack = mutableStateListOf<NavKey>(OnboardingKey.Welcome)
    private var backs = 0

    private fun show(
        scale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                FermixTheme(darkTheme = false) {
                    OnboardingTransitions { changes ->
                        NavDisplay(
                            backStack = stack.toList(),
                            onBack = {
                                backs++
                                stack.removeAt(stack.lastIndex)
                            },
                            entryProvider = entryProvider { standIns(changes) },
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun EntryProviderScope<NavKey>.standIns(changes: ScreenChanges) {
        entry<OnboardingKey.Welcome>(metadata = changes::metadataFor) { StandIn("Welcome") }
        entry<OnboardingKey.Pair>(metadata = changes::metadataFor) { StandIn("Pair") }
        entry<OnboardingKey.Scan>(metadata = changes::metadataFor) { StandIn("Scan") }
        entry<OnboardingKey.Failure>(metadata = changes::metadataFor) { StandIn("Failure") }
    }

    @Composable
    private fun StandIn(name: String) {
        val place = BLOCK + BLOCK_GAP * STAND_INS.indexOf(name)
        Box(modifier = Modifier.fillMaxSize().testTag(name)) {
            Box(modifier = Modifier.offset(x = place, y = BLOCK).size(BLOCK).background(INK))
            Box(modifier = Modifier.offset(x = BAND_START, y = bandTop(name)).size(BAND_WIDTH, BAND).background(INK))
        }
    }

    private fun bandTop(name: String): Dp = BANDS_TOP + BAND_GAP * STAND_INS.indexOf(name)

    /**
     * How opaque [name] is drawn, from its band's middle: wide enough that the shared axis's 30 dp leaves the window's
     * centre on it, and near enough the window's middle that fade through's 92 % does too.
     */
    private fun opacity(
        image: Bitmap,
        name: String,
    ): Float {
        val x = with(rule.density) { (WINDOW / 2).roundToPx() }
        val y = with(rule.density) { (bandTop(name) + BAND / 2).roundToPx() }
        val pixel = image.getPixel(x, y)
        val alpha = pixel ushr 24
        // Over a clear window the ink keeps its colour and takes the alpha; over a white one it greys.
        return if (alpha < 255) alpha / 255f else 1f - (pixel shr 16 and 0xFF) / 255f
    }

    /** Each frame's opacity of [names], from the next frame on, for [count] frames. */
    private fun opacities(
        count: Int,
        vararg names: String,
    ): List<List<Float>> =
        List(count) {
            rule.waitForIdle()
            val image = rule.onRoot().captureToImage().asAndroidBitmap()
            rule.mainClock.advanceTimeByFrame()
            names.map { name -> opacity(image, name) }
        }

    /** The stand-ins whose blocks the frame on screen draws, each in full ink at its place's centre. */
    private fun drawn(): List<String> {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        return STAND_INS.filter { image.inkAt(BLOCK * 1.5f + BLOCK_GAP * STAND_INS.indexOf(it)) }
    }

    private fun Bitmap.inkAt(x: Dp): Boolean {
        val px = with(rule.density) { x.roundToPx() }
        val py = with(rule.density) { (BLOCK * 1.5f).roundToPx() }
        return getPixel(px, py) == INK.toArgb()
    }

    /** [name]'s bounds in the window's px as drawn, scale and all. */
    private fun drawnBounds(name: String): Rect = rule.onNodeWithTag(name).fetchSemanticsNode().boundsInRoot

    /** [keys] shown, the change under way from the next frame. */
    private fun go(vararg keys: NavKey) {
        stack.clear()
        stack.addAll(keys)
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun frames(count: Int) = rule.mainClock.advanceTimeBy(count * FRAME_MILLIS)

    private fun left(name: String): Dp = rule.onNodeWithTag(name).getUnclippedBoundsInRoot().left

    private fun shown(name: String): Boolean = rule.onAllNodes(hasTestTag(name)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `forward, the outgoing screen slides towards the start and the incoming one comes from the end`() {
        show()
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        frames(2)
        val leaving = left("Welcome")
        assertTrue("Welcome at $leaving", leaving < 0.dp && leaving > -30.dp)
        frames(5)
        val coming = left("Pair")
        assertTrue("Pair at $coming", coming > 0.dp && coming < 30.dp)
        frames(60)
        assertEquals(0.dp, left("Pair"))
        assertTrue(!shown("Welcome"))
    }

    @Test
    fun `back, the shared axis is mirrored`() {
        show()
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        frames(60)
        go(OnboardingKey.Welcome)
        frames(2)
        val leaving = left("Pair")
        assertTrue("Pair at $leaving", leaving > 0.dp && leaving < 30.dp)
        frames(5)
        val coming = left("Welcome")
        assertTrue("Welcome at $coming", coming < 0.dp && coming > -30.dp)
    }

    @Test
    fun `right to left, going forward the incoming screen comes from the left`() {
        show(direction = LayoutDirection.Rtl)
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        frames(2)
        val leaving = left("Welcome")
        assertTrue("Welcome at $leaving", leaving > 0.dp && leaving < 30.dp)
        frames(5)
        val coming = left("Pair")
        assertTrue("Pair at $coming", coming < 0.dp && coming > -30.dp)
    }

    @Test
    fun `into Scan and into a failure the screens fade through, scaling up and sliding nothing`() {
        show()
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        frames(60)
        go(OnboardingKey.Welcome, OnboardingKey.Pair, OnboardingKey.Scan)
        frames(2)
        assertEquals(0.dp, left("Pair"))
        frames(5)
        assertFadingThrough("Scan")
        frames(60)
        go(OnboardingKey.Welcome, OnboardingKey.Pair, OnboardingKey.Scan, FAILURE)
        frames(7)
        assertFadingThrough("Failure")
    }

    /** [name] coming in by fade through: scaled down from the window's size about its centre, and sliding nothing. */
    private fun assertFadingThrough(name: String) {
        val window = with(rule.density) { WINDOW.toPx() }
        val bounds = drawnBounds(name)
        assertTrue("$name is ${bounds.width} px across", bounds.width < window && bounds.width > window * 0.92f)
        assertEquals(window / 2, bounds.center.x, 1f)
    }

    @Test
    fun `under Remove animations every change is a cut, one screen drawn whole on every frame`() {
        show(scale = 0f)
        val changes =
            listOf(
                "Pair" to listOf(OnboardingKey.Welcome, OnboardingKey.Pair),
                "Scan" to listOf(OnboardingKey.Welcome, OnboardingKey.Pair, OnboardingKey.Scan),
                "Failure" to listOf(OnboardingKey.Welcome, OnboardingKey.Pair, OnboardingKey.Scan, FAILURE),
                "Pair" to listOf(OnboardingKey.Welcome, OnboardingKey.Pair),
                "Welcome" to listOf(OnboardingKey.Welcome),
            )
        for ((to, keys) in changes) {
            stack.clear()
            stack.addAll(keys)
            assertCut(to)
        }
        // A back swipe scrubs the same cut: Welcome shows as it starts, a cancelled one cuts back to Pair, and one let
        // go stays on Welcome.
        stack.add(OnboardingKey.Pair)
        assertCut("Pair")
        swipe(progress = 0.5f)
        assertCut("Welcome")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        assertCut("Pair")
        swipe(progress = 0.5f)
        assertCut("Welcome")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertCut("Welcome")
        assertEquals(1, backs)
    }

    /** A back swipe from the left edge, started and taken to [progress]. */
    private fun swipe(progress: Float) {
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(
                BackEventCompat(400f * progress, 400f, progress, BackEventCompat.EDGE_LEFT),
            )
        }
    }

    /** A back swipe from the left edge, started and taken to [progress] a tenth of the way a frame, as fingers go. */
    private fun swipeSlowly(progress: Float) {
        val dispatcher = rule.activity.onBackPressedDispatcher
        val start = BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)
        rule.runOnUiThread { dispatcher.dispatchOnBackStarted(start) }
        val steps = (progress * SWIPE_STEPS).roundToInt()
        for (step in 1..steps) {
            val now = progress * step / steps
            rule.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(400f * now, 400f, now, BackEventCompat.EDGE_LEFT))
            }
            rule.waitForIdle()
            rule.mainClock.advanceTimeByFrame()
        }
    }

    /** Frame by frame, one screen drawn at a time, [to] from the first frame it is drawn, and nothing moved. */
    private fun assertCut(to: String) {
        var first: Int? = null
        for (frame in 0 until CUT_FRAMES) {
            rule.waitForIdle()
            val now = drawn()
            assertTrue("frame $frame towards $to draws $now", now.size == 1)
            if (now.single() == to && first == null) first = frame
            if (first != null) assertEquals("frame $frame draws $now", listOf(to), now)
            rule.mainClock.advanceTimeByFrame()
        }
        assertTrue("$to never drawn", first != null)
        val window = with(rule.density) { WINDOW.toPx() }
        assertEquals(Rect(0f, 0f, window, with(rule.density) { 915.dp.toPx() }), drawnBounds(to))
    }

    @Test
    fun `a back swipe scrubs the shared axis, and a cancelled one settles back where it was`() {
        show()
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        frames(60)
        swipe(progress = 0.5f)
        rule.waitForIdle()
        frames(2)
        // Halfway through the swipe Pair has gone part of the way towards the end, and Welcome is coming in.
        val scrubbed = left("Pair")
        assertTrue("Pair at $scrubbed", scrubbed > 0.dp && scrubbed < 30.dp)
        assertTrue(shown("Welcome"))
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        rule.waitForIdle()
        frames(60)
        assertEquals(0.dp, left("Pair"))
        assertTrue(!shown("Welcome"))
        assertEquals(0, backs)
        assertEquals(listOf<NavKey>(OnboardingKey.Welcome, OnboardingKey.Pair), stack.toList())
    }

    @Test
    fun `a back swipe out of Scan or out of a failure scrubs fade through, scaling up and sliding nothing`() {
        show()
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        frames(60)
        go(OnboardingKey.Welcome, OnboardingKey.Pair, OnboardingKey.Scan)
        frames(60)
        assertScrubbedThrough(from = "Scan", to = "Pair")
        go(OnboardingKey.Welcome, OnboardingKey.Pair, OnboardingKey.Scan, FAILURE)
        frames(60)
        assertScrubbedThrough(from = "Failure", to = "Scan")
        assertEquals(0, backs)
    }

    /**
     * A back swipe from [from] to [to] taken halfway, the pop's 150 ms, as 7.2's fade through has it there: [to] scaled
     * up from 92 % about its centre, nothing sliding, [from] faded out and [to] part way in; then cancelled, settling
     * back on [from].
     */
    private fun assertScrubbedThrough(
        from: String,
        to: String,
    ) {
        swipe(progress = 0.5f)
        rule.waitForIdle()
        frames(2)
        rule.waitForIdle()
        assertFadingThrough(to)
        assertEquals(0.dp, left(from))
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        assertEquals("$from halfway", leavingOpacity(150L), opacity(image, from), OPACITY)
        assertEquals("$to halfway", comingOpacity(150L), opacity(image, to), OPACITY)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        rule.waitForIdle()
        frames(60)
        assertTrue("$to after the cancel", !shown(to))
        assertEquals(0.dp, left(from))
    }

    /**
     * Frame by frame from the one after the change is composed, the outgoing screen [from] and the incoming one [to] as
     * opaque as 7.2's fades have them: the change's clock reads 0 on the second of those frames, its first.
     */
    private fun assertFades(
        from: String,
        to: String,
    ) {
        for ((frame, seen) in opacities(CHANGE_FRAMES, from, to).withIndex()) {
            val ms = maxOf(0L, FRAME_MILLIS * (frame - 1))
            assertEquals("$from at $ms ms", leavingOpacity(ms), seen[0], OPACITY)
            assertEquals("$to at $ms ms", comingOpacity(ms), seen[1], OPACITY)
        }
    }

    @Test
    fun `the shared axis fades the outgoing screen out in 90 ms, and the incoming one in over 210 ms after it`() {
        show()
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        assertFades(from = "Welcome", to = "Pair")
        go(OnboardingKey.Welcome)
        assertFades(from = "Pair", to = "Welcome")
    }

    @Test
    fun `fade through fades on the same times, into Scan and back out of it`() {
        show()
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        frames(60)
        go(OnboardingKey.Welcome, OnboardingKey.Pair, OnboardingKey.Scan)
        assertFades(from = "Pair", to = "Scan")
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        assertFades(from = "Scan", to = "Pair")
    }

    @Test
    fun `an entry's metadata is the same each time NavDisplay asks for it`() {
        val changes = ScreenChanges(reduced = { false }, axisShift = { 0 })
        for (key in listOf(OnboardingKey.Welcome, OnboardingKey.Pair, OnboardingKey.Scan, FAILURE)) {
            assertEquals("$key", changes.metadataFor(key), changes.metadataFor(key))
        }
    }

    @Test
    fun `a back swipe let go halfway or at its very end finishes the change, never starting it again`() {
        show()
        for (progress in listOf(0.5f, 1f)) {
            go(OnboardingKey.Welcome, OnboardingKey.Pair)
            frames(60)
            swipeSlowly(progress)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            var last = Float.NEGATIVE_INFINITY to 0f
            repeat(CHANGE_FRAMES) {
                rule.waitForIdle()
                val image = rule.onRoot().captureToImage().asAndroidBitmap()
                val now = left("Welcome").value to opacity(image, "Welcome")
                assertTrue("let go at $progress: Welcome from $last to $now", now.first >= last.first - 0.5f)
                assertTrue("let go at $progress: Welcome from $last to $now", now.second >= last.second - OPACITY)
                last = now
                rule.mainClock.advanceTimeByFrame()
            }
            assertEquals(0f to 1f, last)
            assertEquals(listOf<NavKey>(OnboardingKey.Welcome), stack.toList())
        }
    }

    @Test
    fun `Welcome is a root, where back leaves the app, and above it back is the stack's`() {
        show()
        assertEquals(false, backTaken())
        go(OnboardingKey.Welcome, OnboardingKey.Pair)
        assertEquals(true, backTaken())
    }

    private fun backTaken(): Boolean {
        var taken = false
        rule.runOnUiThread { taken = rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        return taken
    }
}
