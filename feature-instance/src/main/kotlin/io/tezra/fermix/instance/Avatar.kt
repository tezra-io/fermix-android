package io.tezra.fermix.instance

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.Tint

// The visual canon's `.mark`: two dots a dot wide, 0.28 of a dot apart. Its `.dot`: 14 dp (12 on the small
// avatar) on a 2 dp ring of the canvas, 1 dp past the avatar's bottom right.
private const val MARK_GAP = 0.28f
private val DOT_RING = 2.dp
private val DOT_OUTSET = 1.dp

/** The avatar's sizes in the canon: the app bar's (`.av.s`), a row's (`.av`) and the Instance header's (`.av.l`). */
enum class AvatarSize(
    val disc: Dp,
    val connectionDot: Dp,
) {
    SMALL(disc = 32.dp, connectionDot = 12.dp),
    ROW(disc = 48.dp, connectionDot = 14.dp),
    LARGE(disc = 72.dp, connectionDot = 14.dp),
}

/**
 * A Fermix's avatar (design section 9.2): its [tint], one of data's TINT_NAMES, as a disc with nothing on it, as the
 * M51 update's reference player draws its Chats row (the two-dot mark it carried is retired, 7.3), or the owner's
 * [picture] in the round in its place (the owner, 2026-10-10), read for it by [rememberAvatarPicture]; and the
 * connection [dot], none where the canon draws none. The picture's brush is made once per picture and size, so a
 * frame draws two or three circles and makes nothing.
 */
@Composable
fun InstanceAvatar(
    tint: String,
    dot: Dot?,
    size: AvatarSize,
    modifier: Modifier = Modifier,
    picture: ImageBitmap? = null,
) {
    val colors = LocalFermixColors.current
    val fill = tintColor(tint)
    Spacer(
        modifier =
            modifier.size(size.disc).drawWithCache {
                val brush = picture?.let { pictureBrush(it, this.size) }
                val dotAcross = size.connectionDot.toPx()
                onDrawBehind {
                    if (brush == null) drawCircle(color = fill) else drawCircle(brush = brush)
                    if (dot != null) drawConnectionDot(dotAcross, dotColor(dot, colors), colors.canvas)
                }
            },
    )
}

/** [picture] as a brush that fills [area], scaled to it on each edge: it was decoded square, at about that size. */
private fun pictureBrush(
    picture: ImageBitmap,
    area: Size,
): ShaderBrush {
    val shader = ImageShader(picture)
    shader.setLocalMatrix(Matrix().apply { setScale(area.width / picture.width, area.height / picture.height) })
    return ShaderBrush(shader)
}

/**
 * The canon's `.mark.xl`: 28 dp dots, both in the ink, still, as the empty list and the lock draw it (design sections
 * 13.4 and 9.4, which the M51 update leaves standing; its Fermix mark is onboarding's).
 */
@Composable
fun StillTwoDotMark(modifier: Modifier = Modifier) {
    val colors = LocalFermixColors.current
    val dot = 28.dp
    Canvas(modifier = modifier.size(width = dot * (2f + MARK_GAP), height = dot)) {
        drawMark(dot.toPx(), colors.ink)
    }
}

/** The design's colour of [tint], which data keeps by name. */
fun tintColor(tint: String): Color = Tint.entries.single { it.name == tint }.color

/** The dot's colour in the theme's [colors]. */
fun dotColor(
    dot: Dot,
    colors: FermixColors,
): Color =
    when (dot) {
        Dot.OFF -> colors.inkTertiary
        Dot.OK -> colors.ok
        Dot.WARN -> colors.warn
        Dot.ERR -> colors.err
    }

/** The two dots in [ink], each [dot] across, centred. */
private fun DrawScope.drawMark(
    dot: Float,
    ink: Color,
) {
    val radius = dot / 2f
    val apart = dot * (1f + MARK_GAP) / 2f
    drawCircle(color = ink, radius = radius, center = center - Offset(apart, 0f))
    drawCircle(color = ink, radius = radius, center = center + Offset(apart, 0f))
}

/** The connection dot, [across] wide with its ring, at the bottom right, a little past the disc. */
private fun DrawScope.drawConnectionDot(
    across: Float,
    fill: Color,
    ring: Color,
) {
    val outset = DOT_OUTSET.toPx()
    val radius = across / 2f
    val centre = Offset(size.width - radius + outset, size.height - radius + outset)
    drawCircle(color = ring, radius = radius, center = centre)
    drawCircle(color = fill, radius = radius - DOT_RING.toPx(), center = centre)
}
