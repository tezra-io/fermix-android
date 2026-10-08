package io.tezra.fermix.onboarding

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import io.tezra.fermix.design.riseIn
import kotlin.math.max

// The words that rise in on a moment's clock beside the Fermix mark on onboarding's screens (the M51 update's 3.2 and
// 7.4). The mark itself is design's FermixMark: Welcome at 112 dp, Connecting at 88, Verify at 56 and Paired at 88.

/**
 * The least alpha rising words have. Compose takes a layer at alpha 0 for transparent and leaves what it holds out of
 * the tree TalkBack reads, so words not yet risen would be out of its reach until they rise; at a thousandth they draw
 * nothing a screen shows, an 8-bit alpha of 0, and TalkBack reaches them from the first frame.
 */
private const val LEAST_ALPHA = 0.001f

/**
 * Words that fade in and rise [rise] into place over the update's 420 ms on emphasized decelerate, from [start] on the
 * clock [ms] reads as they are drawn: before [start] they draw nothing, though TalkBack reaches them, and from 420 ms
 * after it they stand.
 */
internal fun Modifier.risingIn(
    ms: () -> Float,
    start: Int,
    rise: Dp,
): Modifier =
    graphicsLayer {
        val shown = riseIn(ms(), start)
        alpha = max(shown, LEAST_ALPHA)
        translationY = (1f - shown) * rise.toPx()
    }
