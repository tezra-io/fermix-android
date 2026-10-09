package io.tezra.fermix.design

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.awaitCancellation

/** One frame of a phone's display, in ms. */
internal const val FRAME_MILLIS = 16L

/**
 * A frame clock that gives frames at [times], in ms, then gives none: a loop on it, the idle's or another, waits on it
 * until the test ends it.
 */
internal class SteppedClock(
    times: List<Long>,
) : MonotonicFrameClock {
    private val left = ArrayDeque(times)

    /** How many of the frames the loop has not asked for. */
    val unasked: Int get() = left.size

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        val time = left.removeFirstOrNull() ?: awaitCancellation()
        return onFrame(time * 1_000_000L)
    }
}

/** Frames [FRAME_MILLIS] apart from [from], [count] of them. */
internal fun frames(
    from: Long,
    count: Int,
): List<Long> = List(count) { from + it * FRAME_MILLIS }
