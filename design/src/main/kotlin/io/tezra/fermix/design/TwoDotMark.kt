package io.tezra.fermix.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// The visual canon's `.mark`: two dots 0.28 of a dot apart, a box 2.28 dots across and one dot tall, from
// which the screens measure; `.orbit` sets them 0.22 of a dot above and below their line, and `.merge` draws
// the second at 92 %, pulled 0.78 of a dot back from where the gap left it, over the first: its centre
// 1.28 - 0.78 = 0.5 of a dot from the first's. The canon moves the dots with transforms, which leave the box
// as it is, so the orbit and the assembly draw outside it. Distances are in dots, between the dots' centres.
private const val APART = 1.28f
private const val MERGED = 0.5f
private const val ORBIT_RISE = 0.22f
private const val MERGED_ALPHA = 0.92f
private const val MARK_SPAN = 2.28f

/** How far apart the dots start before they assemble. */
private const val ASSEMBLE_SPREAD = 2.4f

/** The canon's orbit pose as the angle of the dots' line: the first dot above, the second below. */
private val ORBIT_POSE = atan2(ORBIT_RISE, APART / 2f)

/** The canon's sizes of the mark, by its dot: `.mark.xl` on the onboarding screens, `.mark.xs` on the thinking card. */
enum class MarkSize(
    val dot: Dp,
) {
    /** 28 dp dots, a box 64 dp across (section 13.3, step 1). */
    XL(28.dp),

    /** 6 dp dots beside the working indicator's line (section 13.5, the thinking card). */
    XS(6.dp),
}

/** How the two-dot mark moves on its screen (design sections 13.3 and 13.5). */
enum class MarkMotion {
    /** Welcome: the dots come together from apart, once. */
    ASSEMBLE,

    /** Connecting, and the thinking card: the dots turn about their centre (section 13.5). */
    ORBIT,

    /** Paired: the second dot slides over the first, once. */
    MERGE,
}

/**
 * Fermix's two-dot mark (design section 13.10, item 1) at [size], in ink and the accent, moving as [motion]
 * asks on the scheme the screen provides; under reduce-motion the springs snap (section 13.1) and the orbit
 * holds its pose. The dots assemble or merge once: a rotation, a fold or a return to the screen draws them
 * where they arrived.
 */
@Composable
fun TwoDotMark(
    motion: MarkMotion,
    modifier: Modifier = Modifier,
    size: MarkSize = MarkSize.XL,
) {
    val reduced = LocalReducedMotion.current
    val target = if (motion == MarkMotion.MERGE) MERGED else APART
    var arrived by rememberSaveable(motion) { mutableStateOf(false) }
    val distance = remember { Animatable(if (arrived) target else startOf(motion)) }
    val spring = LocalFermixMotion.current.defaultSpatial
    LaunchedEffect(motion, reduced) {
        if (!arrived) distance.animateTo(target, spring.spec(reduced))
        arrived = true
    }
    val still = if (motion == MarkMotion.ORBIT) ORBIT_POSE else 0f
    val angle = if (motion == MarkMotion.ORBIT && !reduced) orbitAngle() else remember { mutableFloatStateOf(still) }
    val colors = LocalFermixColors.current
    val second = if (motion == MarkMotion.MERGE) colors.accent.copy(alpha = MERGED_ALPHA) else colors.accent
    Canvas(modifier = modifier.size(width = size.dot * MARK_SPAN, height = size.dot)) {
        drawDots(size.dot.toPx() / 2f, distance.value, angle.value, colors.ink, second)
    }
}

/** How far apart the dots stand before [motion] moves them. */
private fun startOf(motion: MarkMotion): Float = if (motion == MarkMotion.ASSEMBLE) ASSEMBLE_SPREAD else APART

/** One turn per [FermixMotion.MARK_ORBIT_MILLIS], from the canon's pose. */
@Composable
private fun orbitAngle(): State<Float> =
    rememberInfiniteTransition(label = "orbit").animateFloat(
        initialValue = ORBIT_POSE,
        targetValue = ORBIT_POSE + 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(FermixMotion.MARK_ORBIT_MILLIS, easing = LinearEasing)),
        label = "orbit angle",
    )

/** The two dots of [radius], [distance] dots apart, their line turned by [angle] radians, the first to the left. */
private fun DrawScope.drawDots(
    radius: Float,
    distance: Float,
    angle: Float,
    first: Color,
    second: Color,
) {
    val along = Offset(cos(angle), sin(angle)) * (distance * radius)
    drawCircle(color = first, radius = radius, center = center - along)
    drawCircle(color = second, radius = radius, center = center + along)
}
