package io.tezra.fermix.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// The drop's morph (the M51 update's 3.4): each of the outline's 96 points is paired with the falling dot's point at
// its own angle about the morph centre, and the two are mixed by `morph`. Pairing by angle, not by index, keeps the
// swell from twisting: the outline's angles about that centre turn once, in order (MarkMorphTest).

/** Each outline point's angle about the morph centre, in its order. */
private val outlineAngles: List<Float> =
    MarkGeometry.outlinePoints.map { atan2(it.y - MarkGeometry.morphCentre.y, it.x - MarkGeometry.morphCentre.x) }

/** One cubic of the closed path: from [from] to [to], its handles [first] and [second]. */
@Immutable
internal data class MorphCubic(
    val from: Offset,
    val first: Offset,
    val second: Offset,
    val to: Offset,
)

/**
 * The 96 points of [pose]'s shape: each outline point mixed by `morph` with its partner on the dot, the dot's centre
 * `dropRy` above its lowest point, `dropBottom`, under the morph centre; at 0 the dot's points, at 1 the outline's.
 */
internal fun morphPoints(pose: MarkPose): List<Offset> {
    val dotCentreY = pose.dropBottom - pose.dropRy
    return MarkGeometry.outlinePoints.mapIndexed { i, point ->
        val angle = outlineAngles[i]
        val dot = Offset(MarkGeometry.morphCentre.x + pose.dropRx * cos(angle), dotCentreY + pose.dropRy * sin(angle))
        dot * (1f - pose.morph) + point * pose.morph
    }
}

/**
 * [points] closed into a smooth path by Catmull-Rom (3.4): from each point b to the next c, with a and d the points
 * either side, `cubicTo(b + (c − a) / 6, c − (d − b) / 6, c)`, the indices wrapping.
 */
internal fun catmullRom(points: List<Offset>): List<MorphCubic> {
    require(points.size >= MIN_CLOSED_POINTS) { "A closed path has ${MIN_CLOSED_POINTS}+ points, not ${points.size}." }
    val n = points.size
    return points.indices.map { i ->
        val a = points[(i - 1 + n) % n]
        val b = points[i]
        val c = points[(i + 1) % n]
        val d = points[(i + 2) % n]
        MorphCubic(from = b, first = b + (c - a) / 6f, second = c - (d - b) / 6f, to = c)
    }
}

/** The fewest points a closed Catmull-Rom path takes: the cubic from b to c reads a before and d after. */
private const val MIN_CLOSED_POINTS = 4
