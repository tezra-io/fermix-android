package io.tezra.fermix.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// The visual canon's `.mark.xs`: two 6 dp dots 0.28 of a dot apart, in a box 2.28 dots across and one dot tall, and
// its `.orbit`, which sets them 0.22 of a dot above and below their line. The canon turns the dots with a transform,
// which leaves the box as it is, so the orbit draws outside it. Distances are in dots, between the dots' centres.
private val DOT = 6.dp
private const val APART = 1.28f
private const val ORBIT_RISE = 0.22f
private const val MARK_SPAN = 2.28f

/** The canon's orbit pose as the angle of the dots' line: the first dot above, the second below. */
private val ORBIT_POSE = atan2(ORBIT_RISE, APART / 2f)

/**
 * The thinking card's two-dot mark (design section 13.5), its one use since the Fermix mark took the others (the M51
 * update's 7.3): two 6 dp dots in the ink, which at that size read as the mark's two eyes, turning about their centre
 * once per [FermixMotion.MARK_ORBIT_MILLIS]; under reduce-motion they hold the canon's pose.
 */
@Composable
fun TwoDotMark(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val angle = if (reduced) remember { mutableFloatStateOf(ORBIT_POSE) } else orbitAngle()
    val ink = LocalFermixColors.current.ink
    Canvas(modifier = modifier.size(width = DOT * MARK_SPAN, height = DOT)) {
        drawDots(DOT.toPx() / 2f, angle.value, ink)
    }
}

/** One turn per [FermixMotion.MARK_ORBIT_MILLIS], from the canon's pose. */
@Composable
private fun orbitAngle(): State<Float> =
    rememberInfiniteTransition(label = "orbit").animateFloat(
        initialValue = ORBIT_POSE,
        targetValue = ORBIT_POSE + 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(FermixMotion.MARK_ORBIT_MILLIS, easing = LinearEasing)),
        label = "orbit angle",
    )

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
