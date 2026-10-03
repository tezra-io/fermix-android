package io.tezra.fermix.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** The space between the last line's words and a stamp floated beside them (the canon's `.ts` margin). */
private val STAMP_GAP = 10.dp

/** The last laid-out line of the words, as their text last reported it. */
private class LastLine {
    var result: TextLayoutResult? = null
}

/**
 * Where a stamp of [stampWidth] goes after words whose last line is [lastLineWidth] wide, in a bubble at most
 * [maxWidth] wide: on that line, [gap] after it, when it fits and the line runs the bubble's way; else on a
 * line of its own.
 */
fun stampFloats(
    lastLineWidth: Int,
    stampWidth: Int,
    gap: Int,
    maxWidth: Int,
    sameDirection: Boolean,
): Boolean = sameDirection && lastLineWidth + gap + stampWidth <= maxWidth

/**
 * Words with a stamp floated on their last line (the canon's `.ts{float:right}`), at its end when it fits
 * (stampFloats), else under the words at the end. [words] reports its layout through the callback it is given.
 */
@Composable
internal fun StampedText(
    words: @Composable (onLayout: (TextLayoutResult) -> Unit) -> Unit,
    stamp: @Composable () -> Unit,
) {
    val last = remember { LastLine() }
    Layout(content = {
        words { last.result = it }
        stamp()
    }) { measurables, constraints ->
        require(measurables.size == 2) { "the words and the stamp" }
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val text = measurables[0].measure(loose)
        val mark = measurables[1].measure(loose)
        val laid = last.result
        val lineWidth = laid?.let { lastLineWidth(it) } ?: text.width
        val same = laid == null || runsLtr(laid) == (layoutDirection == LayoutDirection.Ltr)
        val floats = stampFloats(lineWidth, mark.width, STAMP_GAP.roundToPx(), constraints.maxWidth, same)
        val width = maxOf(text.width, if (floats) lineWidth + STAMP_GAP.roundToPx() + mark.width else mark.width)
        val height = if (floats) maxOf(text.height, mark.height) else text.height + mark.height
        layout(width, height) {
            text.placeRelative(0, 0)
            mark.placeRelative(width - mark.width, height - mark.height)
        }
    }
}

/** Whether the words' last paragraph runs left to right. */
private fun runsLtr(result: TextLayoutResult): Boolean {
    val end = (result.layoutInput.text.length - 1).coerceAtLeast(0)
    return result.getParagraphDirection(end) == ResolvedTextDirection.Ltr
}

/** The width of the last laid-out line, from its start to its end. */
private fun lastLineWidth(result: TextLayoutResult): Int {
    val line = result.lineCount - 1
    return ceil(result.getLineRight(line) - result.getLineLeft(line)).toInt()
}
