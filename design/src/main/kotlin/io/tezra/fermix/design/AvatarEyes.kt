package io.tezra.fermix.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * The avatar's eyes' ink: the mark's own, `#0B0B0D`, light mode's ink and the launcher icon's fill, in both modes. A
 * tint is a fixed surface of its own colour, the same in both modes, as the code card is, so what lies on it keeps
 * one colour; dark mode's ink would read at 3 : 1 on two of the eight tints alone.
 */
val AVATAR_EYE_INK = Color(0xFF0B0B0D)

/**
 * How far Sideways looks, in mark units, the app's own number: the pair pressed toward the disc's left edge, its outer
 * corners 4.4 units inside the rim, about 5.7 dp on the chat bar's 32 dp avatar. The avatar has no visor to bound the
 * eyes, so only a look this far reads as one: the search's reach (2.6) is under 2 dp on a 48 dp disc, and twice it
 * looked like Open set off-centre at 32 and 40 dp.
 */
internal const val AVATAR_LOOK = 11f

/**
 * The share of their height Squint's eyes keep, the app's own number: wider than tall, well short of the shut eye's
 * line. The mark's own squint, [Narrow.SQUINT], is a moment's, and at 32 and 40 dp it looked like Open.
 */
internal const val AVATAR_SQUINT = 0.4f

/**
 * The share of the disc's width the eye pair spans, from the left eye's left edge to the right eye's right edge, the
 * pair centred on the disc: the app's own number, chosen by eye at 32, 48 and 72 dp.
 */
internal const val EYE_SPAN = 0.46f

/**
 * The avatar's six eye styles (the owner, 2026-10-11: "Just different style of eyes is fine"), each a still pose of the
 * mark's two eyes: open, happy, a wink of the right eye, a squint, both shut, and a look to the side. A record keeps
 * its style by name, one of data's EYE_STYLES (EyeStylesTest holds the two equal).
 */
enum class EyeStyle(
    val pose: MarkPose,
) {
    Open(MarkPose.Rest),
    Happy(MarkPose(happy = 1f)),
    Wink(MarkPose(blinkRight = EYE_SHUT)),
    Squint(MarkPose(squint = AVATAR_SQUINT)),
    Sleepy(MarkPose(blink = EYE_SHUT)),
    Sideways(MarkPose(eyeX = -AVATAR_LOOK)),
}

/** The style a record names; a name that is none of [EyeStyle]'s fails loud. */
fun eyeStyleOf(name: String): EyeStyle =
    requireNotNull(EyeStyle.entries.firstOrNull { it.name == name }) {
        "eyes '$name' are not one of ${EyeStyle.entries}"
    }

// The eye pair at rest in the mark's units: the left eye's left edge, the right eye's right edge, and their line.
private val PAIR_LEFT = MarkGeometry.leftEye.centre.x - MarkGeometry.leftEye.width / 2f
private val PAIR_RIGHT = MarkGeometry.rightEye.centre.x + MarkGeometry.rightEye.width / 2f
private val PAIR_LINE = (MarkGeometry.leftEye.centre.y + MarkGeometry.rightEye.centre.y) / 2f

/**
 * [style]'s eyes on a disc of [disc], at its origin, in [AVATAR_EYE_INK]: the mark's eyes scaled until the pair spans
 * [EYE_SPAN] of the disc's width, the pair's centre on the disc's, drawn with the mark's own [drawEyes] and [paths],
 * which the caller makes once. A frame makes nothing.
 */
fun DrawScope.drawAvatarEyes(
    style: EyeStyle,
    disc: Size,
    paths: MarkEyePaths,
) {
    val scale = EYE_SPAN * disc.minDimension / (PAIR_RIGHT - PAIR_LEFT)
    withTransform({
        translate(
            left = disc.width / 2f - (PAIR_LEFT + PAIR_RIGHT) / 2f * scale,
            top = disc.height / 2f - PAIR_LINE * scale,
        )
        scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
    }) {
        drawEyes(style.pose, paths, AVATAR_EYE_INK)
    }
}
