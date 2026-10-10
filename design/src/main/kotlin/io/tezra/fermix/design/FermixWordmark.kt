package io.tezra.fermix.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Fermix wordmark (the owner, 2026-10-10), the first-party one the Mac and Linux apps draw: a 1:1 port of
 * design/wordmark/fermix-wordmark.svg's paths ([WordmarkGeometry]), drawn rather than loaded, as macOS's
 * FermixWordmark.swift draws it, since the file's letters are `currentColor`, which no Android vector takes. The
 * letters are filled in the ink by the file's even-odd rule and the two eye-dots in [FermixColors.signal], the file's
 * own #2b5cff in both modes; nothing is cropped, redrawn or recoloured. [height] is the file's whole box, its margin
 * with it, so the letters stand 100 of its 116 units and the box is 396 of them wide. It is one node, which TalkBack
 * reads as "Fermix"; drawn in dp, it keeps its size at any font scale.
 */
@Composable
fun FermixWordmark(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    require(height > 0.dp) { "The wordmark needs a positive height, not $height." }
    val colors = LocalFermixColors.current
    val letters = remember { wordmarkLetters() }
    val description = stringResource(R.string.design_wordmark)
    val width = height * (WordmarkGeometry.WIDTH / WordmarkGeometry.HEIGHT)
    Canvas(modifier.size(width, height).semantics { contentDescription = description }) {
        drawWordmark(letters, colors.ink, colors.signal)
    }
}

/** The file's box scaled to this one, its viewBox's top left on the top left; the letters in [ink], dots in [dot]. */
private fun DrawScope.drawWordmark(
    letters: Path,
    ink: Color,
    dot: Color,
) {
    val unit = size.height / WordmarkGeometry.HEIGHT
    withTransform({
        scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero)
        translate(left = -WordmarkGeometry.LEFT, top = -WordmarkGeometry.TOP)
    }) {
        drawPath(letters, ink)
        for (centre in WordmarkGeometry.dots) {
            drawCircle(dot, WordmarkGeometry.DOT_RADIUS, centre + WordmarkGeometry.dotsAt)
        }
    }
}

/**
 * Every letter path at its group's translation, in one path filled by the even-odd rule as the file's group fills them:
 * no two of the file's paths overlap, only meet, so one path fills what they fill, with no seam where two meet.
 */
private fun wordmarkLetters(): Path {
    val letters = Path().apply { fillType = PathFillType.EvenOdd }
    for (group in WordmarkGeometry.letters) {
        for (data in group.paths) letters.addPath(PathParser().parsePathString(data).toPath(), group.at)
    }
    return letters
}
