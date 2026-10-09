package io.tezra.fermix.onboarding

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.ScreenChange
import kotlin.math.sign

/** How one screen gives way to another when one of them is onboarding's (the M51 update's 7.2). */
enum class Move {
    /** Material's shared axis X, the incoming screen from the end side: the next step of the flow. */
    FORWARD,

    /** The shared axis mirrored, the incoming screen from the start side: back a step. */
    BACK,

    /** Material's fade through: into or out of Scan, into or out of a failure, into or out of onboarding. */
    FADE_THROUGH,
}

/**
 * The move from the screen [from] to [to], each onboarding's or, when null, the app's (the Chats list), with [from]
 * leaving the back stack when it [pops]: the shared axis between the steps of the flow, forward or back, and fade
 * through where the context changes, into or out of Scan's camera, a failure, a branch rather than the next step, or
 * the app itself. Verify's "Cancel" goes back to Scan, the back stack's step under it, so it fades through.
 */
fun moveBetween(
    from: OnboardingKey?,
    to: OnboardingKey?,
    pops: Boolean,
): Move {
    require(from != null || to != null) { "a move of onboarding's has one of its screens at an end" }
    return when {
        from == null || to == null -> Move.FADE_THROUGH
        changesContext(from) || changesContext(to) -> Move.FADE_THROUGH
        pops -> Move.BACK
        else -> Move.FORWARD
    }
}

private fun changesContext(key: OnboardingKey): Boolean = key == OnboardingKey.Scan || key is OnboardingKey.Failure

/** The onboarding screen an entry shows, in its metadata, so that a transition can tell what it goes from and to. */
data object OnboardingScreen : NavMetadataKey<OnboardingKey>

/**
 * The screen changes of onboarding's entries, which NavDisplay runs outside composition: each reads, as it runs,
 * whether Remove animations is on ([reduced]) and the shared axis's shift in the window's pixels ([axisShift]).
 */
@Stable
class ScreenChanges(
    private val reduced: () -> Boolean,
    private val axisShift: () -> Int,
) {
    // Made once, so that an entry's metadata is equal each time NavDisplay asks for it. A new lambda each time made
    // each scene unequal to the last for the same screen, and a back swipe let go at its very end, its scrub at 1,
    // then ran the change again from its start between the two (Navigation 3 1.2.0, Compose 1.12).
    private val changeIn: AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform = { change(pops = false) }
    private val changeOut: AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform = { change(pops = true) }
    private val swipeOut: AnimatedContentTransitionScope<Scene<*>>.(Int) -> ContentTransform = { change(pops = true) }

    /**
     * [key]'s entry metadata: the screen it shows, and its change in, its change out as back takes it off the stack,
     * and the same change out as a back swipe scrubs it, so that a cancelled swipe settles back along it (7.2).
     */
    fun metadataFor(key: OnboardingKey): Map<String, Any> =
        metadata {
            put(OnboardingScreen, key)
            put(NavDisplay.TransitionKey, changeIn)
            put(NavDisplay.PopTransitionKey, changeOut)
            put(NavDisplay.PredictivePopTransitionKey, swipeOut)
        }

    private fun AnimatedContentTransitionScope<Scene<*>>.change(pops: Boolean): ContentTransform {
        val move = moveBetween(initialState.metadata[OnboardingScreen], targetState.metadata[OnboardingScreen], pops)
        return when {
            reduced() -> cut()
            move == Move.FADE_THROUGH -> fadeThrough()
            else -> sharedAxis(forward = move == Move.FORWARD)
        }
    }

    /**
     * The shared axis: the incoming screen from [axisShift] on the side it comes from, the end going forward, and the
     * outgoing one as far the other way, on the standard scheme's defaultSpatial; Start and End follow the layout's
     * direction, so a right-to-left layout mirrors it.
     */
    private fun AnimatedContentTransitionScope<Scene<*>>.sharedAxis(forward: Boolean): ContentTransform {
        val shift = axisShift()
        val towards = if (forward) SlideDirection.Start else SlideDirection.End
        val position =
            spring(
                dampingRatio = ScreenChange.position.dampingRatio,
                stiffness = ScreenChange.position.stiffness,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            )
        val enter = slideIntoContainer(towards, position) { full -> full.sign * shift } + fadeInAfterOut()
        val exit = slideOutOfContainer(towards, position) { full -> full.sign * shift } + fadeOutFirst()
        return enter togetherWith exit
    }
}

/**
 * Under Remove animations every change is a cut: nothing slides, fades or scales. Not the empty transitions, as
 * NavDisplay keeps drawing the outgoing screen until its transition ends, a frame or two on, over the incoming one, and
 * the screens draw over the app's canvas, not a fill of their own; a snapped fade each way keeps the incoming screen
 * hidden until the frame the outgoing one goes. A back swipe scrubs the same cut, which has no time to scrub: the
 * screen it goes back to shows as it starts, and a cancelled swipe cuts back.
 */
private fun cut(): ContentTransform = fadeIn(snap()) togetherWith fadeOut(snap())

/** Fade through: the outgoing screen fades out, then the incoming one fades in, scaling up from 92 %. */
private fun fadeThrough(): ContentTransform {
    val scale =
        scaleIn(
            tween(ScreenChange.IN_MILLIS, ScreenChange.OUT_MILLIS, ScreenChange.inEasing),
            initialScale = ScreenChange.FADE_THROUGH_FROM,
        )
    return (fadeInAfterOut() + scale) togetherWith fadeOutFirst()
}

private fun fadeInAfterOut(): EnterTransition =
    fadeIn(tween(ScreenChange.IN_MILLIS, delayMillis = ScreenChange.OUT_MILLIS, easing = ScreenChange.inEasing))

private fun fadeOutFirst(): ExitTransition = fadeOut(tween(ScreenChange.OUT_MILLIS, easing = ScreenChange.outEasing))

/**
 * The scope onboarding's screens are laid out in, which carries the Fermix mark from one to the next (the M51 update's
 * 7.3): the app's, around its NavDisplay. A screen drawn outside one, as a preview or a screen's own test draws it, has
 * none, and its mark is its own.
 */
internal val LocalMarkTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Onboarding's screen changes for the NavDisplay [content] lays out, which onboardingEntries puts on each entry, and
 * the shared layout around it, in which the mark moves from screen to screen.
 */
@Composable
fun OnboardingTransitions(content: @Composable (ScreenChanges) -> Unit) {
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val shift by rememberUpdatedState(with(LocalDensity.current) { ScreenChange.axisShift.roundToPx() })
    val changes = remember { ScreenChanges(reduced = { reduced }, axisShift = { shift }) }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalMarkTransition provides this) { content(changes) }
    }
}
