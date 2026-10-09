package io.tezra.fermix.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.max

// A screen's moments and loops that are not the mark's (the M51 update's 7.4 and 7.5): one clock a moment, which a
// screen samples its table at as it draws, and a loop's clock for the ongoing work that loops (the scan's searching,
// Connecting's eyes), each ended with its screen or its work, and still under Remove animations.

/**
 * A moment of a screen: one clock, [ms], that runs once from 0 to its length on the frames, linear, and then stands
 * there. A moment made at its end stands there from its first frame.
 */
@Stable
class Moment internal constructor(
    private val length: Float,
    atEnd: Boolean,
) {
    private val clock = Animatable(if (atEnd) length else 0f)

    /** The moment's clock, in ms: its length once it has played. */
    val ms: Float get() = clock.value

    /**
     * Runs the clock from where it stands to its end, telling [onClock] each frame's time; one at its end already, or
     * any under reduced motion, stands at its end and tells [onClock] that end.
     */
    internal suspend fun play(
        reduced: Boolean,
        onClock: (Float) -> Unit,
    ) {
        val left = length - clock.value
        if (reduced || left <= 0f) {
            clock.snapTo(length)
            onClock(length)
            return
        }
        clock.animateTo(length, tween(ceil(left).toInt(), easing = LinearEasing)) { onClock(value) }
    }
}

/**
 * A [Moment] of [length] ms for the screen it is remembered in: from 0 the first time, at its end when it [played]
 * already or reduced motion is on, which only the first composition reads, so the first frame drawn under reduced
 * motion is the last. [onClock] hears each frame's time on the clock, and the end of a moment that stands there.
 */
@Composable
fun rememberMoment(
    length: Int,
    played: Boolean,
    onClock: (Float) -> Unit = {},
): Moment {
    require(length > 0) { "A moment lasts longer than 0 ms, not $length." }
    val reduced = LocalReducedMotion.current
    val moment = remember { Moment(length.toFloat(), atEnd = played || reduced) }
    val latest by rememberUpdatedState(onClock)
    LaunchedEffect(moment, reduced) { moment.play(reduced) { latest(it) } }
    return moment
}

/**
 * A loop's clock (the M51 update's 7.5: loops only for ongoing work): [ms] is the time it has run, drawn frame by
 * frame, each frame moving it on by its time since the last, [IDLE_STEP_MAX_MILLIS] at most, so time out of sight
 * counts for nothing; it runs [IDLE_MAX_MILLIS] at most, the idle's bound, and then stands.
 */
@Stable
class Loop internal constructor() {
    var ms: Float by mutableFloatStateOf(0f)
        private set

    /**
     * Runs the loop one frame at a time until its caller is cancelled or it reaches its bound. Its frames are an
     * infinite animation's, which Compose's tests stop while their clock runs on its own.
     */
    internal suspend fun run() {
        var last = withInfiniteAnimationFrameMillis { it }
        while (ms < IDLE_MAX_MILLIS) {
            val frame = withInfiniteAnimationFrameMillis { it }
            ms = (ms + (frame - last).coerceIn(0L, IDLE_STEP_MAX_MILLIS)).coerceAtMost(IDLE_MAX_MILLIS)
            last = frame
        }
    }
}

/**
 * A [Loop] that runs while [running], its work going on, and stops where it was once it is not, or its screen leaves;
 * under reduced motion it never runs, and stands at 0.
 */
@Composable
fun rememberLoop(running: Boolean): Loop {
    val reduced = LocalReducedMotion.current
    val loop = remember { Loop() }
    val runs = running && !reduced
    LaunchedEffect(loop, runs) { if (runs) loop.run() }
    return loop
}

/**
 * The least alpha rising words have. Compose takes a layer at alpha 0 for transparent and leaves what it holds out of
 * the tree TalkBack reads, so words not yet risen would be out of its reach until they rise; at a thousandth they draw
 * nothing a screen shows, an 8-bit alpha of 0, and TalkBack reaches them from the first frame.
 */
const val LEAST_ALPHA = 0.001f

/**
 * What fades in and rises [rise] into place as [shown], read as it is drawn, goes from 0 to 1: before it starts it
 * draws nothing, though TalkBack reaches it, and once it is 1 it stands.
 */
fun Modifier.risingIn(
    shown: () -> Float,
    rise: Dp,
): Modifier =
    graphicsLayer {
        val now = shown()
        alpha = max(now, LEAST_ALPHA)
        translationY = (1f - now) * rise.toPx()
    }
