package io.tezra.fermix.onboarding

import android.content.Context
import android.provider.Settings
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.MarkEasing

/** One frame of the rig's clock. */
private const val RIG_FRAME_MILLIS = 16L

/** What goes, [ms] into a screen change or a line's: 7.2's "fades out in 90 ms (emphasized accelerate)". */
internal fun leavingOpacity(ms: Long): Float =
    1f - MarkEasing.EmphasizedAccelerate.transform((ms / 90f).coerceIn(0f, 1f))

/** What comes: 7.2's "fades in over 210 ms after a 90 ms delay", on 7.5's emphasized decelerate. */
internal fun comingOpacity(ms: Long): Float =
    MarkEasing.EmphasizedDecelerate.transform(((ms - 90f) / 210f).coerceIn(0f, 1f))

/**
 * The screen's view, which keeps every haptic the screen plays with the test clock's time it played at: a view group,
 * as a ripple looks for one above its view.
 */
internal class PlayedHaptics(
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
 * A screen's motion on [rule]'s clock, which moves only when told, a frame at a time (the M51 update's 7.4): the screen
 * composed in light mode at an animator duration scale, its haptics kept with their times, its saved state kept in a
 * registry of the rig's own so that it can be restored, and the clock's time since the screen's first frame, [at].
 */
internal class MotionRig(
    private val rule: AndroidComposeTestRule<*, RoborazziActivity>,
) {
    lateinit var haptics: PlayedHaptics
        private set

    /** Where the clock is, in ms since the screen's first frame. */
    var at = 0L
        private set

    /** The test clock's time at the screen's first frame. */
    private var start = 0L

    private var restorations by mutableIntStateOf(0)
    private var composed by mutableStateOf(true)
    private var registry: SaveableStateRegistry? = null
    private var restored: Map<String, List<Any?>>? = null

    /**
     * [screen] composed at the animator duration [scale] and, when given, the font [fontScale], and its first frame
     * drawn, the clock's time then 0.
     */
    fun show(
        scale: Float = 1f,
        fontScale: Float? = null,
        screen: @Composable () -> Unit,
    ) {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        haptics = PlayedHaptics(rule.activity) { rule.mainClock.currentTime }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val activity = checkNotNull(LocalSaveableStateRegistry.current)
            val saving =
                remember(restorations) { SaveableStateRegistry(restored, activity::canBeSaved).also { registry = it } }
            val density = LocalDensity.current
            val scaled = fontScale?.let { Density(density.density, it) } ?: density
            CompositionLocalProvider(
                LocalView provides haptics,
                LocalSaveableStateRegistry provides saving,
                LocalDensity provides scaled,
            ) {
                FermixTheme(darkTheme = false) { Box(modifier = Modifier.fillMaxSize()) { if (composed) screen() } }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        at = 0L
        start = rule.mainClock.currentTime
    }

    /** The screen's state saved, the screen disposed, and composed anew from what it saved, as a rotation does. */
    fun restore() {
        restored = checkNotNull(registry).performSave()
        composed = false
        frameAfterWrite()
        restorations++
        composed = true
        frameAfterWrite()
    }

    /** The screen taken away, as its screen leaves the back stack. */
    fun remove() {
        composed = false
        frameAfterWrite()
    }

    /**
     * One frame once the test's write to the screen's state reaches the composition: the main looper runs the
     * snapshot's apply notifications, which the clock's frames alone do not, and the frame then recomposes.
     */
    fun frameAfterWrite() {
        rule.waitForIdle()
        frames(1)
    }

    /** The clock moved on to its first frame at or after [ms]. */
    fun advanceTo(ms: Long) = frames((ms - at + RIG_FRAME_MILLIS - 1) / RIG_FRAME_MILLIS)

    /** The clock moved on to its last frame before [ms]. */
    fun advanceToJustBefore(ms: Long) = frames((ms - 1 - at) / RIG_FRAME_MILLIS)

    /** The clock moved on [count] frames. */
    fun frames(count: Long) {
        rule.mainClock.advanceTimeBy(count * RIG_FRAME_MILLIS)
        at += count * RIG_FRAME_MILLIS
    }

    /** The test clock's time since the screen's first frame, as a callback reads it mid-advance. */
    fun now(): Long = rule.mainClock.currentTime - start

    /** The haptics played, each with its time since the screen's first frame. */
    fun played(): List<Pair<Int, Long>> = haptics.played.map { (use, time) -> use to time - start }

    /** Whether anything in the composition waits for a frame. */
    fun framesAsked(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }
}
