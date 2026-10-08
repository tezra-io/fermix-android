package io.tezra.fermix.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlin.math.ceil
import kotlin.random.Random

/**
 * A moment of the mark on its screen (the M51 update's section 6): one clock, [ms], that runs once from 0 to its
 * length on the frames, linear, for the screen to sample its tables at, and then the idle of section 4, [idle], until
 * the screen leaves, or for [IDLE_MAX_MILLIS] of it drawn at most. A moment made [atEnd] stands at its end from its
 * first frame.
 */
@Stable
class MarkMoment internal constructor(
    private val length: Float,
    atEnd: Boolean,
) {
    private val clock = Animatable(if (atEnd) length else 0f)

    /** The moment's clock, in ms: its length once it has played. */
    val ms: Float get() = clock.value

    /** Where the idle is; still until the clock has run, and under reduced motion. */
    var idle: MarkIdle by mutableStateOf(MarkIdle.Still)
        private set

    /**
     * Runs the clock from where it stands to its end, telling [onClock] each frame's time, then breathes with blinks
     * [random] draws. A moment at its end already, or any under reduced motion, stands at its end and tells [onClock]
     * that end, for what it would have played on the way; under reduced motion nothing breathes or blinks.
     */
    internal suspend fun play(
        reduced: Boolean,
        random: Random,
        onClock: (Float) -> Unit,
    ) {
        val left = length - clock.value
        if (reduced || left <= 0f) {
            idle = MarkIdle.Still
            clock.snapTo(length)
            onClock(length)
        } else {
            clock.animateTo(length, tween(ceil(left).toInt(), easing = LinearEasing)) { onClock(value) }
        }
        if (!reduced) breathe(random)
    }

    /**
     * The idle, one frame at a time, until the screen leaves and cancels it or [IDLE_MAX_MILLIS] of it has been drawn,
     * when the mark stands at rest and asks for no more frames. Each frame moves it on by the time since the last, a
     * frame's at most ([MarkIdle.after]), so time out of sight counts for nothing. Its frames are an infinite
     * animation's, which Compose's tests stop while their clock runs on its own.
     */
    private suspend fun breathe(random: Random) {
        var last = withInfiniteAnimationFrameMillis { it }
        var shown = MarkIdle(ms = 0f, blinks = Blinks.first(random))
        while (shown.ms < IDLE_MAX_MILLIS) {
            val frame = withInfiniteAnimationFrameMillis { it }
            shown = shown.after(frame - last, random)
            last = frame
            idle = shown
        }
        idle = MarkIdle.Still
    }
}

/**
 * A [MarkMoment] of [length] ms for the screen it is remembered in: from 0 the first time, at its end when it [played]
 * already or reduced motion is on, which only the first composition reads, so the first frame drawn under reduced
 * motion is the last. [onClock] hears each frame's time on the clock, and the end of a moment that stands there, for a
 * haptic on it. The idle's blinks come when [random] draws. A moment of no length is the idle alone.
 */
@Composable
fun rememberMarkMoment(
    length: Int,
    played: Boolean,
    random: Random,
    onClock: (Float) -> Unit = {},
): MarkMoment {
    require(length >= 0) { "A moment lasts 0 ms or more, not $length." }
    val reduced = LocalReducedMotion.current
    val moment = remember { MarkMoment(length.toFloat(), atEnd = played || reduced) }
    val latest by rememberUpdatedState(onClock)
    LaunchedEffect(moment, reduced) { moment.play(reduced, random) { latest(it) } }
    return moment
}
