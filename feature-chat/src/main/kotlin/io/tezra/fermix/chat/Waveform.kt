package io.tezra.fermix.chat

import java.util.Locale
import kotlin.math.sqrt

/** The bars of a voice note's waveform (design section 8.5: "40-bar waveform from sampled amplitude"). */
const val WAVE_BARS = 40

/** The loudest sample MediaRecorder.getMaxAmplitude reports. */
const val MAX_AMPLITUDE = 32_767

/** A bar's least height, so a silent stretch still draws a line. */
const val QUIET_LEVEL = 0.08f

/**
 * The level every bar of a note stands at until its bars are known: recorded by this process, or read from its
 * file with its length (ChatPlayback.length, noteLevels).
 */
const val EVEN_LEVEL = 0.35f

/**
 * A sample's bar height, 0 to 1: the square root of its share of [MAX_AMPLITUDE], so speech, which sits low on
 * the linear scale, fills the bar; at least [QUIET_LEVEL].
 */
fun levelOf(amplitude: Int): Float {
    val share = amplitude.coerceIn(0, MAX_AMPLITUDE).toFloat() / MAX_AMPLITUDE
    return sqrt(share).coerceAtLeast(QUIET_LEVEL)
}

/**
 * [levels] as [count] bars: each bar the loudest of its share of the samples; fewer samples than bars stretch,
 * and none draw quiet bars.
 */
fun barsOf(
    levels: List<Float>,
    count: Int = WAVE_BARS,
): List<Float> {
    require(count > 0) { "a waveform of no bar" }
    if (levels.isEmpty()) return List(count) { QUIET_LEVEL }
    return List(count) { bar ->
        val from = bar * levels.size / count
        val to = maxOf(from + 1, (bar + 1) * levels.size / count)
        levels.subList(from, minOf(to, levels.size)).max()
    }
}

/** The recording row's live bars: the newest [count] samples, quiet bars before them while there are fewer. */
fun liveBars(
    levels: List<Float>,
    count: Int = WAVE_BARS,
): List<Float> {
    val newest = levels.takeLast(count)
    return List(count - newest.size) { QUIET_LEVEL } + newest
}

/** A note's bars for its bubble: those recorded or read from its file, an even line until they are known. */
fun noteBars(recorded: List<Float>?): List<Float> = recorded ?: List(WAVE_BARS) { EVEN_LEVEL }

/** A duration as the timer and the bubble show it: "0:11", "12:05". */
fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / MILLIS_PER_SECOND
    return String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
