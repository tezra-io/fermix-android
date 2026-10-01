package io.tezra.fermix.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * One of the two surfaces that carry tone (design section 13.1, "elevation is the control plane;
 * content lies flat"): the tonal surface at 94 % and a 1 dp hairline on the [edge] that touches
 * content. There is no backdrop blur and no shadow, and content gets nothing of this.
 */
fun Modifier.controlPlane(
    edge: Edge,
    colors: FermixColors,
): Modifier =
    when (edge) {
        Edge.Bottom -> {
            this.background(colors.tonal).drawBehind { drawHairline(colors, atBottom = true) }
        }

        Edge.Top -> {
            this.background(colors.tonal).drawBehind { drawHairline(colors, atBottom = false) }
        }

        Edge.Around -> {
            this
                .background(colors.tonal, FermixShapes.dock)
                .border(FermixSpacing.hairline, colors.hairline, FermixShapes.dock)
        }
    }

private fun DrawScope.drawHairline(
    colors: FermixColors,
    atBottom: Boolean,
) {
    val thickness = FermixSpacing.hairline.toPx()
    val top = if (atBottom) size.height - thickness else 0f
    drawRect(color = colors.hairline, topLeft = Offset(0f, top), size = Size(size.width, thickness))
}
