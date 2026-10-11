package io.tezra.fermix.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.MarkEasing

/** The reveal's length, and how large the image starts, settling to its own size. */
internal const val REVEAL_MILLIS = 400
internal const val REVEAL_SCALE = 1.04f

/**
 * How a bubble's image comes in (the owner, 2026-10-10, after libraries.dev's image loader, the idea alone): over its
 * placeholder, from unseen and a little large to whole and its own size in 400 ms on the emphasized decelerate, when
 * the daemon has just brought it ([MediaImage.Shown.fresh]); an image the phone held already, as a chat opens or a
 * cell scrolls back to it, is drawn plainly, and so is any under reduce-motion. No render effect: a blur's crashed the
 * emulator's renderer (Task 23's review), and a fade and a scale are a layer's own. What it returns is the share of the
 * way so far, read as the layer draws ([reveal]), so a frame recomposes nothing, and none once the image is whole,
 * when the layer goes. [held] is a preview's share of the way, held still (PreviewHeld).
 */
@Composable
internal fun rememberReveal(
    shown: MediaImage.Shown?,
    held: Float?,
): (() -> Float)? {
    val plays = shown?.fresh == true && !LocalReducedMotion.current
    val progress = remember(shown) { Animatable(if (plays) 0f else 1f) }
    var whole by remember(shown) { mutableStateOf(!plays) }
    LaunchedEffect(progress) {
        if (!whole) {
            progress.animateTo(1f, tween(REVEAL_MILLIS, easing = MarkEasing.EmphasizedDecelerate))
            whole = true
        }
    }
    val share = remember(progress) { { progress.value } }
    return when {
        held != null -> ({ held })
        whole -> null
        else -> share
    }
}

/** The image's scale at [at] of its way in: [REVEAL_SCALE] as it starts, its own size once whole. */
internal fun revealScale(at: Float): Float = REVEAL_SCALE - (REVEAL_SCALE - 1f) * at.coerceIn(0f, 1f)

/**
 * The image at [share] of its way in ([rememberReveal]): that much of it seen, over its placeholder, and that much
 * settled to its size, inside its cell; as it is, with no layer, once [share] is none. The fade is laid on each of its
 * draws ([CompositingStrategy.ModulateAlpha]), as one bitmap overlaps nothing of its own, so it takes no offscreen
 * buffer.
 */
internal fun Modifier.reveal(share: (() -> Float)?): Modifier =
    if (share == null) {
        this
    } else {
        clipToBounds().graphicsLayer {
            val at = share().coerceIn(0f, 1f)
            alpha = at
            scaleX = revealScale(at)
            scaleY = revealScale(at)
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    }
