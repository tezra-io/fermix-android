package io.tezra.fermix.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min

/** The mark's box, in its own units. */
private const val MARK_UNITS = 100f

/** From here the morph has reached the silhouette, which the exact outline then draws (the update's 3.4). */
private const val MORPH_DONE = 0.999f

/** Below this the visor is shut, and nothing is cut out (section 6). */
private const val VISOR_SHUT = 0.001f

/** An eye is never drawn shorter than this, as the reference player draws it: a blink's or the happy eyes' line. */
private const val EYE_MIN_HEIGHT = 0.01f

/**
 * The Fermix mark in the pose [pose] gives, [size] across (the M51 update's 2.1 and section 6): the outline filled in
 * the ink with the visor cut out, the eyes as rounded rectangles in the ink, the happy arcs over them, the body scaled
 * about the feet and lifted by the hop. It draws in mark units and is never clipped to its box, which the drop and the
 * hop pass. A pose in, pixels out: it keeps no clock, so a preview or a test draws any instant. The pose is read as
 * the mark draws, so a moment's clock moves it frame by frame without composing it again, and in the same phase as the
 * words a screen raises on that clock. It is decorative, as every screen's words say what is happening (7.3), so it
 * has no description.
 */
@Composable
fun FermixMark(
    pose: () -> MarkPose,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val ink = LocalFermixColors.current.ink
    val paths = remember { MarkPaths() }
    Canvas(modifier = modifier.size(size)) { drawMark(pose(), paths, ink) }
}

/** [pose] in mark units: scaled to the box, lifted by the hop, the body scaled about the feet (section 6). */
private fun DrawScope.drawMark(
    pose: MarkPose,
    paths: MarkPaths,
    ink: Color,
) {
    val unit = size.width / MARK_UNITS
    val scaleX = pose.bodyScale * pose.breathX * pose.hopScaleX
    val scaleY = pose.bodyScale * pose.breathY * pose.hopScaleY
    withTransform({
        scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero)
        translate(top = pose.hopY)
        scale(scaleX = scaleX, scaleY = scaleY, pivot = MarkGeometry.feet)
    }) {
        drawPath(paths.body(pose), ink)
        // Where the eyes look moves them and their happy arcs together, inside the visor (the update's 7.4).
        translate(left = pose.eyeX, top = pose.eyeY) {
            drawEye(MarkGeometry.leftEye, pose.eyeLeft, eyeHeight(MarkGeometry.leftEye, pose), ink)
            drawEye(MarkGeometry.rightEye, pose.eyeRight, eyeHeight(MarkGeometry.rightEye, pose), ink)
            drawHappyArcs(paths, pose.happy, ink)
        }
    }
}

/**
 * [eye]'s height in [pose]: the drop's blink, the idle's, a squint and the happy eyes each shrink it about its centre,
 * and only the drop's pop scales it whole.
 */
internal fun eyeHeight(
    eye: MarkEye,
    pose: MarkPose,
): Float = max(EYE_MIN_HEIGHT, eye.height * pose.blink * pose.idleBlink * pose.squint * (1f - pose.happy))

/**
 * An eye's corner radius at [height]: the mark's 2.2 units, never more than half the height, so the corners stay
 * round.
 */
internal fun eyeCorner(height: Float): Float = min(MarkGeometry.EYE_CORNER, height / 2f)

/** [eye] at [height], centred, scaled whole by its [pop] about its centre. */
private fun DrawScope.drawEye(
    eye: MarkEye,
    pop: Float,
    height: Float,
    ink: Color,
) {
    if (pop <= 0f) return
    scale(scale = pop, pivot = eye.centre) {
        drawRoundRect(
            color = ink,
            topLeft = Offset(eye.centre.x - eye.width / 2f, eye.centre.y - height / 2f),
            size = Size(eye.width, height),
            cornerRadius = CornerRadius(eyeCorner(height)),
        )
    }
}

/** The happy arcs over both eyes at opacity [happy], none while it is 0. */
private fun DrawScope.drawHappyArcs(
    paths: MarkPaths,
    happy: Float,
    ink: Color,
) {
    if (happy <= 0f) return
    drawPath(path = paths.leftArc, color = ink, alpha = happy, style = paths.arcStroke)
    drawPath(path = paths.rightArc, color = ink, alpha = happy, style = paths.arcStroke)
}

/** The mark's fixed paths and the arcs' stroke, made once (section 6), and the body a pose draws. */
private class MarkPaths {
    private val outline = PathParser().parsePathString(MarkGeometry.OUTLINE).toPath()
    private val visor = PathParser().parsePathString(MarkGeometry.VISOR).toPath()

    /** The body standing: the outline with the visor open, cut out by the even-odd rule, as the vector fills it. */
    private val standing = cutOut(outline, visor)

    val leftArc = happyArc(MarkGeometry.leftEye)
    val rightArc = happyArc(MarkGeometry.rightEye)
    val arcStroke = Stroke(width = MarkGeometry.HAPPY_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)

    /**
     * [pose]'s body: the falling dot, an ellipse, until the morph starts; the morph's 96 points closed by Catmull-Rom
     * until it reaches the silhouette, so the path is built afresh each frame only while it swells (section 6's 430
     * ms); then the exact outline; the visor, scaled about its centre by `visorOpen`, cut out of it once it opens.
     */
    fun body(pose: MarkPose): Path {
        val whole = pose.morph >= MORPH_DONE
        val shape =
            when {
                whole -> outline
                pose.morph <= 0f -> dot(pose)
                else -> morphed(pose)
            }
        return when {
            whole && pose.visorOpen == 1f -> standing
            pose.visorOpen < VISOR_SHUT -> shape
            else -> cutOut(shape, opened(pose.visorOpen))
        }
    }

    /** The falling dot (3.4): `dropRx` by `dropRy` about its centre, under the morph centre, `dropRy` over its foot. */
    private fun dot(pose: MarkPose): Path {
        val centreX = MarkGeometry.morphCentre.x
        val centreY = pose.dropBottom - pose.dropRy
        val bounds =
            Rect(centreX - pose.dropRx, centreY - pose.dropRy, centreX + pose.dropRx, centreY + pose.dropRy)
        return Path().apply { addOval(bounds) }
    }

    /** The visor scaled about its centre to [open] of its height. */
    private fun opened(open: Float): Path {
        val opening = Path().apply { addPath(visor) }
        val centre = MarkGeometry.visorCentre
        opening.transform(
            Matrix().apply {
                translate(centre.x, centre.y)
                scale(1f, open)
                translate(-centre.x, -centre.y)
            },
        )
        return opening
    }

    private fun cutOut(
        shape: Path,
        hole: Path,
    ): Path =
        Path().apply {
            addPath(shape)
            addPath(hole)
            fillType = PathFillType.EvenOdd
        }

    private fun morphed(pose: MarkPose): Path {
        val cubics = catmullRom(morphPoints(pose))
        return Path().apply {
            moveTo(cubics.first().from.x, cubics.first().from.y)
            for (cubic in cubics) {
                cubicTo(cubic.first.x, cubic.first.y, cubic.second.x, cubic.second.y, cubic.to.x, cubic.to.y)
            }
            close()
        }
    }

    private fun happyArc(eye: MarkEye): Path =
        Path().apply {
            val (cx, cy) = eye.centre
            moveTo(cx - MarkGeometry.HAPPY_HALF_WIDTH, cy + MarkGeometry.HAPPY_FOOT)
            quadraticTo(
                cx,
                cy - MarkGeometry.HAPPY_RISE,
                cx + MarkGeometry.HAPPY_HALF_WIDTH,
                cy + MarkGeometry.HAPPY_FOOT,
            )
        }
}
