package io.tezra.fermix.design

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

// The visual canon's `.mark.xs`: two 6 dp dots 0.28 of a dot apart, in a box 2.28 dots across and one dot tall, and
// its `.orbit`, which sets them 0.22 of a dot above and below their line. The canon turns the dots with a transform,
// which leaves the box as it is, so the orbit draws outside it, as the halo under it does. Distances are in dots,
// between the dots' centres.
private val DOT = 6.dp
private const val APART = 1.28f
private const val ORBIT_RISE = 0.22f
private const val MARK_SPAN = 2.28f

/** The canon's orbit pose as the angle of the dots' line: the first dot above, the second below. */
internal val ORBIT_POSE = atan2(ORBIT_RISE, APART / 2f)

/**
 * The thinking card's two-dot mark (design section 13.5), its one use since the Fermix mark took the others (the M51
 * update's 7.3): two 6 dp dots in the ink, which at that size read as the mark's two eyes, turning about their centre
 * once per [FermixMotion.MARK_ORBIT_MILLIS], over a halo of the ink about 28 dp across that breathes between 0.92
 * and 1.08 of its size once per 3,800 ms, or 1,900 ms while [toolRunning] (the owner, 2026-10-10). Under
 * reduce-motion, and in a preview, the dots hold the canon's pose and the halo its middle. Three draws a frame, the
 * halo's image made once per size and mode ([haloImage]); the clock is read as the mark draws, so it recomposes
 * nothing.
 */
@Composable
fun TwoDotMark(
    modifier: Modifier = Modifier,
    toolRunning: Boolean = false,
) {
    val still = LocalReducedMotion.current || LocalInspectionMode.current
    val motion = remember { TwoDotMotion() }
    val fast by rememberUpdatedState(toolRunning)
    LaunchedEffect(motion, still) { motion.run(reduced = still) { fast } }
    TwoDotMarkAt(motion, modifier = modifier)
}

/**
 * The two-dot mark at [pose]: its dots' line at its angle and its halo at its share of [HALO_DIAMETER], both read as
 * it draws. [TwoDotMark] passes its clock; a preview passes a pose held still.
 */
@Composable
internal fun TwoDotMarkAt(
    pose: TwoDotMotion,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    Spacer(
        modifier =
            modifier.size(width = DOT * MARK_SPAN, height = DOT).drawWithCache {
                val stops = haloStops(haloPeak(colors)).map { alpha -> colors.ink.copy(alpha = alpha) }
                val halo = haloImage(HALO_DIAMETER.toPx() / 2f, stops)
                val corner = size.center - Offset(halo.width / 2f, halo.height / 2f)
                val radius = DOT.toPx() / 2f
                onDrawBehind {
                    scale(pose.haloScale, pivot = center) { drawImage(halo, topLeft = corner) }
                    drawDots(radius, pose.angle, colors.ink)
                }
            },
    )
}

/**
 * The halo's gradient of [stops], [radius] out, drawn once on the CPU into an image the mark then draws each frame:
 * the gradient drawn by the GPU each frame faulted the emulator's renderer (swiftshader_indirect), whose threads
 * read past a table within seconds of the card, so the app's emulators, CI's among them, died with it.
 */
private fun CacheDrawScope.haloImage(
    radius: Float,
    stops: List<Color>,
): ImageBitmap {
    val side = ceil(2f * radius).toInt()
    val image = ImageBitmap(side, side)
    val middle = Offset(side / 2f, side / 2f)
    CanvasDrawScope().draw(this, layoutDirection, Canvas(image), Size(side.toFloat(), side.toFloat())) {
        drawCircle(Brush.radialGradient(stops, center = middle, radius = radius), radius = radius, center = middle)
    }
    return image
}

/** The two dots of [radius], [APART] dots apart, their line turned by [angle] radians, the first to the left. */
private fun DrawScope.drawDots(
    radius: Float,
    angle: Float,
    ink: Color,
) {
    val along = Offset(cos(angle), sin(angle)) * (APART * radius)
    drawCircle(color = ink, radius = radius, center = center - along)
    drawCircle(color = ink, radius = radius, center = center + along)
}
