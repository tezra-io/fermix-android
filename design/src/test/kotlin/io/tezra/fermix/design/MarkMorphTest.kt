package io.tezra.fermix.design

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.atan2

private const val CLOSE = 1e-3f

/** Each cubic of the closed path flattened to this many straight pieces, to look for a crossing. */
private const val PIECES = 8

/**
 * The drop's morph (the M51 update's 3.4): the falling dot and the silhouette as 96 pairs, each outline point with the
 * dot's point at its angle about the morph centre, mixed by `morph` and closed through Catmull-Rom cubics.
 */
class MarkMorphTest {
    @Test
    fun `at morph 0 every point lies on the dot's ellipse, at the angle of its outline point`() {
        for (ms in listOf(0f, 200f, 380f, 400f, 470f)) {
            val pose = dropAt(ms)
            assertEquals(0f, pose.morph)
            val centre = Offset(MarkGeometry.morphCentre.x, pose.dropBottom - pose.dropRy)
            val points = morphPoints(pose)
            for ((i, point) in points.withIndex()) {
                val x = (point.x - centre.x) / pose.dropRx
                val y = (point.y - centre.y) / pose.dropRy
                assertEquals(1f, x * x + y * y, CLOSE, "point $i at $ms ms")
                assertEquals(angleOf(MarkGeometry.outlinePoints[i]), atan2(y, x), CLOSE, "point $i at $ms ms")
            }
        }
    }

    @Test
    fun `at morph 1 the points are the outline's 96`() {
        assertEquals(MarkGeometry.outlinePoints, morphPoints(dropAt(900f)))
        assertEquals(MarkGeometry.outlinePoints, morphPoints(MarkPose.Rest))
    }

    @Test
    fun `between, each point lies on the line from its dot point to its outline point`() {
        val pose = dropAt(600f)
        val dot = morphPoints(pose.copy(morph = 0f))
        for ((i, point) in morphPoints(pose).withIndex()) {
            val expected = dot[i] + (MarkGeometry.outlinePoints[i] - dot[i]) * pose.morph
            assertEquals(expected.x, point.x, CLOSE)
            assertEquals(expected.y, point.y, CLOSE)
        }
    }

    @Test
    fun `the outline's angles about the morph centre turn once, clockwise, so pairing by angle keeps their order`() {
        val angles = MarkGeometry.outlinePoints.map(::angleOf)
        val steps = angles.indices.map { i -> (angles[(i + 1) % angles.size] - angles[i] + TURN) % TURN }
        assertTrue(steps.all { it > 0f && it < TURN / 2f })
        assertEquals(TURN, steps.sum(), CLOCK_TOLERANCE)
    }

    @Test
    fun `each cubic runs from one point to the next, its handles a sixth of the neighbours' chord away`() {
        val points = morphPoints(dropAt(600f))
        val cubics = catmullRom(points)
        assertEquals(points.size, cubics.size)
        for (i in points.indices) {
            val near = { step: Int -> points[(i + step + points.size) % points.size] }
            val (a, b) = near(-1) to near(0)
            val (c, d) = near(1) to near(2)
            val cubic = cubics[i]
            assertEquals(b, cubic.from)
            assertEquals(b + (c - a) / 6f, cubic.first)
            assertEquals(c - (d - b) / 6f, cubic.second)
            assertEquals(c, cubic.to)
        }
    }

    @Test
    fun `the closed path never crosses itself through the drop, every 10 ms from the dot to the silhouette`() {
        for (ms in 0..900 step 10) {
            val outline = flattened(catmullRom(morphPoints(dropAt(ms.toFloat()))))
            assertEquals(null, firstCrossing(outline), "at $ms ms")
        }
    }

    @Test
    fun `the crossing check finds a crossing`() {
        // The control: the outline's points with two neighbours swapped, which twists the path.
        val twisted = MarkGeometry.outlinePoints.toMutableList()
        twisted[10] = MarkGeometry.outlinePoints[11].also { twisted[11] = MarkGeometry.outlinePoints[10] }
        assertTrue(firstCrossing(flattened(catmullRom(twisted))) != null)
    }

    private fun angleOf(point: Offset): Float =
        atan2(point.y - MarkGeometry.morphCentre.y, point.x - MarkGeometry.morphCentre.x)

    private fun flattened(cubics: List<MorphCubic>): List<Offset> =
        cubics.flatMap { cubic -> (0 until PIECES).map { cubic.at(it / PIECES.toFloat()) } }

    private fun MorphCubic.at(t: Float): Offset {
        val u = 1f - t
        return from * (u * u * u) + first * (3f * u * u * t) + second * (3f * u * t * t) + to * (t * t * t)
    }

    /** The first pair of the closed polyline's edges that cross, other than neighbours, or null. */
    private fun firstCrossing(points: List<Offset>): Pair<Int, Int>? {
        val n = points.size
        for (i in 0 until n) {
            val crossing = (i + 2 until n).firstOrNull { j -> (i != 0 || j != n - 1) && crosses(points, i, j) }
            if (crossing != null) return i to crossing
        }
        return null
    }

    private fun crosses(
        points: List<Offset>,
        i: Int,
        j: Int,
    ): Boolean {
        val n = points.size
        val (a, b) = points[i] to points[(i + 1) % n]
        val (c, d) = points[j] to points[(j + 1) % n]
        val ab = side(a, b, c) * side(a, b, d)
        val cd = side(c, d, a) * side(c, d, b)
        return ab < 0f && cd < 0f
    }

    private fun side(
        from: Offset,
        to: Offset,
        point: Offset,
    ): Float = (to.x - from.x) * (point.y - from.y) - (to.y - from.y) * (point.x - from.x)

    private companion object {
        val TURN = (2 * PI).toFloat()
        const val CLOCK_TOLERANCE = 1e-4f
    }
}
