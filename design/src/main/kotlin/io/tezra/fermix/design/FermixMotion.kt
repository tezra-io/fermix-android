package io.tezra.fermix.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The moments of design section 13.1, "Motion", in milliseconds, the unit of Compose's animation specs. */
object FermixMotion {
    /** A new bubble rises this far as it fades in; tokens are never animated one by one. */
    val bubbleInsertRise: Dp = 12.dp

    /** The streaming answer's beam cursor blinks on this period. */
    const val CURSOR_BLINK_MILLIS = 800

    /** One pass of the thinking line's shimmer. */
    const val THINKING_SHIMMER_MILLIS = 1_600

    /** One orbit of the thinking card's two dots (section 13.5). */
    const val MARK_ORBIT_MILLIS = 1_200

    /** The indicator's phrase changes by this cross-fade (section 13.5). */
    const val INDICATOR_CROSS_FADE_MILLIS = 200

    /** The thinking card's bounds become the answer bubble's, on [emphasized]. */
    const val THINKING_TO_ANSWER_MILLIS = 300

    /** A tool chip scales in from this, while its arc turns once per [TOOL_CHIP_ARC_MILLIS]. */
    const val TOOL_CHIP_SCALE_FROM = 0.92f
    const val TOOL_CHIP_ARC_MILLIS = 1_200

    /** Send and stop cross-rotate into each other. */
    const val SEND_STOP_MILLIS = 200

    /** The thin connection banner waits this long before it shows. */
    const val CONNECTION_BANNER_DELAY_MILLIS = 2_000

    /** The date pill fades once the scroll has settled for this long. */
    const val DATE_PILL_FADE_DELAY_MILLIS = 500

    /**
     * A message jumped to is highlighted for this long, in accentInk at [JUMP_HIGHLIGHT_ALPHA]: the visual
     * canon's 6 dp ring around the bubble, which in dark mode is the lifted blue.
     */
    const val JUMP_HIGHLIGHT_MILLIS = 1_500
    const val JUMP_HIGHLIGHT_ALPHA = 0.12f

    /** An approval card becomes its receipt. */
    const val APPROVAL_RESOLVE_MILLIS = 350

    /** The SAS digits land one after another, each with its haptic (section 13.1, "Haptics"). */
    const val SAS_DIGIT_STAGGER_MILLIS = 40

    /** Material's emphasized easing (material3's EasingEmphasizedCubicBezier). */
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/** A spring of a motion scheme. */
@Immutable
data class MotionSpring(
    val dampingRatio: Float,
    val stiffness: Float,
) {
    /** The spring, or a snap under reduce-motion: "springs snap" (section 13.1). */
    fun <T> spec(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else spring(dampingRatio = dampingRatio, stiffness = stiffness)
}

/**
 * Material's motion schemes as their six springs, with material3's StandardMotionTokens and
 * ExpressiveMotionTokens values. material3 1.4.0, the design's version, keeps MotionScheme internal, and
 * MaterialTheme's motionScheme parameter and property with it (internal to Kotlin, which the compiler
 * enforces; in the bytecode they are public, as every Kotlin internal declaration is): every Material
 * component already moves on its standard scheme, and no app can hand one the expressive scheme. So the
 * design's own animations take their springs from [LocalFermixMotion].
 */
@Immutable
data class FermixMotionScheme(
    val defaultSpatial: MotionSpring,
    val fastSpatial: MotionSpring,
    val slowSpatial: MotionSpring,
    val defaultEffects: MotionSpring,
    val fastEffects: MotionSpring,
    val slowEffects: MotionSpring,
) {
    companion object {
        val Standard =
            FermixMotionScheme(
                defaultSpatial = MotionSpring(dampingRatio = 0.9f, stiffness = 700f),
                fastSpatial = MotionSpring(dampingRatio = 0.9f, stiffness = 1_400f),
                slowSpatial = MotionSpring(dampingRatio = 0.9f, stiffness = 300f),
                defaultEffects = MotionSpring(dampingRatio = 1f, stiffness = 1_600f),
                fastEffects = MotionSpring(dampingRatio = 1f, stiffness = 3_800f),
                slowEffects = MotionSpring(dampingRatio = 1f, stiffness = 800f),
            )

        val Expressive =
            FermixMotionScheme(
                defaultSpatial = MotionSpring(dampingRatio = 0.8f, stiffness = 380f),
                fastSpatial = MotionSpring(dampingRatio = 0.6f, stiffness = 800f),
                slowSpatial = MotionSpring(dampingRatio = 0.8f, stiffness = 200f),
                defaultEffects = MotionSpring(dampingRatio = 1f, stiffness = 1_600f),
                fastEffects = MotionSpring(dampingRatio = 1f, stiffness = 3_800f),
                slowEffects = MotionSpring(dampingRatio = 1f, stiffness = 800f),
            )
    }
}

/**
 * The scheme the design's animations move on: the standard one, app-wide (section 13.1), which
 * FermixTheme provides. Read outside FermixTheme, it fails.
 */
val LocalFermixMotion =
    staticCompositionLocalOf<FermixMotionScheme> { error("LocalFermixMotion is read outside FermixTheme.") }

/**
 * The expressive scheme for [content]. Section 13.1 allows it in three places only: the SAS reveal,
 * "Paired", and the first chat's entrance.
 */
@Composable
fun ExpressiveMotion(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFermixMotion provides FermixMotionScheme.Expressive, content = content)
}
