package io.tezra.fermix.onboarding

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.ScreenChange
import io.tezra.fermix.design.riseIn
import io.tezra.fermix.design.risingIn

// The Fermix mark on onboarding's screens and the words that rise in beside it on a moment's clock (the M51 update's
// 3.2, 7.3 and 7.4). The mark itself is design's FermixMark: Welcome at 112 dp, Connecting at 88, Verify at 56 and
// Paired at 88.

/**
 * The key the mark is shared by from Connecting to Verify to Paired (7.3); it tags the mark for the tests too, which a
 * screen reader passes over, the mark saying nothing.
 */
internal const val MARK_KEY = "onboarding-mark"

/**
 * Words that fade in and rise [rise] into place over the update's 420 ms on emphasized decelerate, from [start] on the
 * clock [ms] reads as they are drawn: before [start] they draw nothing, though TalkBack reaches them, and from 420 ms
 * after it they stand.
 */
internal fun Modifier.risingIn(
    ms: () -> Float,
    start: Int,
    rise: Dp,
): Modifier = risingIn(shown = { riseIn(ms(), start) }, rise = rise)

/**
 * The mark of a screen [content] draws with the modifier it is handed, which goes before the mark's own size: in
 * onboarding's shared layout, as the app's NavDisplay draws the flow, the one mark moves from one screen's place and
 * size to the next one's on the standard scheme's defaultSpatial as the screens change (7.3), and at once under Remove
 * animations; drawn on its own, as a preview or a screen's own test draws it, it is the screen's alone.
 */
@Composable
internal fun SharedMark(content: @Composable (Modifier) -> Unit) {
    val layout = LocalMarkTransition.current
    if (layout == null) {
        content(Modifier.testTag(MARK_KEY))
        return
    }
    val screen = LocalNavAnimatedContentScope.current
    val bounds = if (LocalReducedMotion.current) SNAPS else MOVES
    with(layout) {
        // Tagged inside the shared element, where the mark is measured at the size it is drawn at.
        content(Modifier.sharedElement(rememberSharedContentState(MARK_KEY), screen, bounds).testTag(MARK_KEY))
    }
}

private val MOVES =
    BoundsTransform { _, _ ->
        spring(ScreenChange.position.dampingRatio, ScreenChange.position.stiffness, Rect.VisibilityThreshold)
    }

private val SNAPS = BoundsTransform { _, _ -> snap() }
