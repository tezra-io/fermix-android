package io.tezra.fermix.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import kotlin.math.max
import kotlin.math.min

/**
 * The left and right edges of what [ringedContent] marks, which Compose hands up through the layouts that hold it,
 * each moved by where that layout placed it, to the [contentFocusRing] around them.
 */
private val RingedLeft = VerticalAlignmentLine(::min)
private val RingedRight = VerticalAlignmentLine(::max)

/**
 * [focusRing] for a control wider than what it draws, an agent's message in the 88 % of its row its parts may grow
 * to: the ring follows what [ringedContent] marks inside it, so a short reply's ring lies 2 dp past its bubble while
 * the control, what a tap, a long-press and TalkBack reach, keeps its own width. With nothing marked, it rings the
 * control's bounds, as [focusRing] does.
 */
fun Modifier.contentFocusRing(
    shape: RoundedCornerShape,
    on: RingOn = RingOn.Surface,
): Modifier = ring(shape, on, within = false, shown = false, aroundContent = true)

/** Marks what it modifies as what a [contentFocusRing] around it rings, from its left edge to its right. */
fun Modifier.ringedContent(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height, mapOf(RingedLeft to 0, RingedRight to placeable.width)) {
            placeable.place(0, 0)
        }
    }

/** The left and right edges, in [placeable]'s pixels, of what [ringedContent] marks in it; null where nothing is. */
internal fun ringedSpan(placeable: Placeable): Pair<Int, Int>? {
    val left = placeable[RingedLeft]
    val right = placeable[RingedRight]
    if (left == AlignmentLine.Unspecified || right == AlignmentLine.Unspecified) return null
    return left to right
}
