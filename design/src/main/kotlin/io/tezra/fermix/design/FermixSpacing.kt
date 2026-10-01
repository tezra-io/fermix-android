package io.tezra.fermix.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The density of design section 13.1: a 4 dp grid, which the 2 dp step inside a group and the bubble's
 * 9 dp vertical padding leave on purpose.
 */
object FermixSpacing {
    val grid: Dp = 4.dp

    /** Between bubbles of one group. */
    val withinGroup: Dp = 2.dp

    /** Between groups. */
    val betweenGroups: Dp = 12.dp

    /** Between the column's edge and its content. */
    val gutter: Dp = 12.dp

    /** Inside a bubble: 12 × 9 dp. */
    val bubblePaddingHorizontal: Dp = 12.dp
    val bubblePaddingVertical: Dp = 9.dp

    /** The smallest touch target (section 13.8). */
    val minTarget: Dp = 48.dp

    /** An instance's avatar in a list row, and in the chat's app bar. */
    val avatar: Dp = 48.dp
    val avatarSmall: Dp = 32.dp

    /** The line where a control-plane surface meets content. */
    val hairline: Dp = 1.dp

    /** The instance's tint under the app bar. */
    val tintLine: Dp = 2.dp

    /** The widest a bubble grows, as a share of the column (section 13.11 rule 2), not of the window. */
    const val USER_BUBBLE_MAX_WIDTH = 0.78f
    const val AGENT_BUBBLE_MAX_WIDTH = 0.88f

    /** A timestamp sits inside its bubble, bottom-right, at this opacity. */
    const val TIMESTAMP_ALPHA = 0.6f
}
