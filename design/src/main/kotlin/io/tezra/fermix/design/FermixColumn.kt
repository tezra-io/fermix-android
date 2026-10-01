package io.tezra.fermix.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.isSpecified
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * The column content sits in (design section 13.11): on a compact window, under 600 dp wide, the whole
 * window, as section 13 is drawn (rule 1); on a medium or an expanded one, a centred column of [width]
 * (rule 2), while what spans the window, the app bar, is drawn outside it. A bubble's 78 % / 88 % is of
 * this column. It spans the width it is given and centres the column in it, so a screen calls it with
 * no modifier. Not a Compose Column: its content is laid out as in a Box.
 *
 * The window's size class comes from androidx.window's WindowSizeClass, read from the window's size in
 * dp. That needs no Activity, so a preview, whose window is its device, gets the class the same way the
 * app does; material3-window-size-class reads it from an Activity.
 */
@Composable
fun FermixColumn(
    width: ColumnWidth,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val maxWidth = columnMaxWidth(LocalWindowInfo.current.containerDpSize, width)
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth()) { content() }
    }
}

/** The column's widest: unbounded on a compact window, the column's width on any wider one. */
internal fun columnMaxWidth(
    window: DpSize,
    width: ColumnWidth,
): Dp {
    require(window.isSpecified) { "The window has no size yet." }
    val windowClass = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(window.width.value, window.height.value)
    val compact = !windowClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    return if (compact) Dp.Infinity else width.width
}
