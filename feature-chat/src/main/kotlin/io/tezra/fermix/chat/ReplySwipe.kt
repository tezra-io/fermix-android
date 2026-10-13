package io.tezra.fermix.chat

import android.view.View
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalFermixMotion
import io.tezra.fermix.design.LocalReducedMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * How far a message travels toward the end edge before a let go replies: the app's number, as the design draws no
 * reply (README's departures), for the owner to settle.
 */
val REPLY_SWIPE: Dp = 56.dp

/** How far past [REPLY_SWIPE] the message follows the finger, at a quarter of its travel: the app's number. */
val REPLY_OVERSHOOT: Dp = 16.dp

/** The share of the finger's travel past [REPLY_SWIPE] that the message follows. */
private const val PAST_SHARE = 0.25f

/** The reply glyph: its size, its gap before the message's start edge at the threshold, and its scale as it starts. */
private val GLYPH = 24.dp
private val GLYPH_GAP = 12.dp
private const val GLYPH_SCALE_FROM = 0.6f

/**
 * Where a message stands for the finger's [travel] toward the end edge, in pixels: with the finger to [threshold],
 * a quarter of its travel past it, and never more than [overshoot] past; travel the other way moves nothing.
 */
internal fun replyOffset(
    travel: Float,
    threshold: Float,
    overshoot: Float,
): Float =
    when {
        travel <= 0f -> 0f
        travel <= threshold -> travel
        else -> threshold + ((travel - threshold) * PAST_SHARE).coerceAtMost(overshoot)
    }

/** The finger's travel that puts a message at [offset] (replyOffset's inverse), as a drag takes it up mid-spring. */
private fun travelAt(
    offset: Float,
    threshold: Float,
): Float = if (offset <= threshold) offset else threshold + (offset - threshold) / PAST_SHARE

/**
 * What a message's swipe is drawn and moved with, fixed while it is shown: the threshold, the overshoot, the glyph's
 * size and gap in pixels; whether motion is reduced and the window runs right to left; the spring home; the glyph and
 * its tint.
 */
internal data class SwipeLook(
    val threshold: Float,
    val overshoot: Float,
    val glyph: Float,
    val gap: Float,
    val reduced: Boolean,
    val rtl: Boolean,
    val spring: AnimationSpec<Float>,
    val painter: Painter,
    val tint: ColorFilter,
)

/**
 * One message's swipe to reply (the owner, 2026-10-11), made once per message (rememberReplySwipe): a horizontal
 * drag whose travel toward the end edge moves the message ([bubble], read in its layer alone) as replyOffset says and
 * reveals the reply glyph behind it ([row], drawn by the message's row, read in its draw alone), so a move recomposes
 * nothing and allocates nothing. Crossing the threshold outward plays [onCross] once; let go past it, [onReply] runs,
 * and the message springs home either way, while it is shown. Under reduce-motion the message stays and the glyph is
 * whole once the finger is past the threshold. A message that takes no reply ([enabled] false) has neither.
 */
internal class ReplySwipe(
    enabled: Boolean,
    private val look: SwipeLook,
    private val onCross: () -> Unit,
    private val onReply: () -> Unit,
) {
    private val offset = mutableFloatStateOf(0f)
    private val past = mutableStateOf(false)
    private var travel = 0f
    private var home: Job? = null
    private var bubbleAt: LayoutCoordinates? = null
    private var rowAt: LayoutCoordinates? = null
    private val drag = DraggableState { delta -> moved(delta) }

    /** On the message: where it rests, the drag, and its layer, which alone reads where it stands. */
    val bubble: Modifier =
        if (!enabled) {
            Modifier
        } else {
            Modifier
                .onPlaced { bubbleAt = it }
                .draggable(
                    drag,
                    Orientation.Horizontal,
                    reverseDirection = look.rtl,
                    onDragStarted = { started() },
                    onDragStopped = { released() },
                ).graphicsLayer { translationX = if (look.rtl) -offset.floatValue else offset.floatValue }
        }

    /** On the message's row, under the message: the glyph, drawn behind it. */
    val row: Modifier = if (!enabled) Modifier else Modifier.onPlaced { rowAt = it }.drawBehind { glyph(this) }

    private fun moved(delta: Float) {
        travel = (travel + delta).coerceAtLeast(0f)
        if (!look.reduced) offset.floatValue = replyOffset(travel, look.threshold, look.overshoot)
        val beyond = travel >= look.threshold
        if (beyond == past.value) return
        past.value = beyond
        if (beyond) onCross()
    }

    /** A drag starts, taking the message up where a spring home left it. */
    private fun started() {
        home?.cancel()
        home = null
        travel = travelAt(offset.floatValue, look.threshold)
        past.value = travel >= look.threshold
    }

    /** Let go: past the threshold it replies, and the message springs home on the standard scheme. */
    private suspend fun released() {
        val replies = past.value
        travel = 0f
        past.value = false
        if (replies) onReply()
        val from = offset.floatValue
        if (from == 0f) return
        home = currentCoroutineContext()[Job]
        animate(from, 0f, animationSpec = look.spring) { value, _ -> offset.floatValue = value }
    }

    /**
     * The glyph in the row, [GLYPH_GAP] before where the message's start edge stands at the threshold, centred on it,
     * fading in and growing from [GLYPH_SCALE_FROM] over the travel to the threshold, then still; under reduce-motion
     * whole once past it.
     */
    private fun glyph(scope: DrawScope) {
        val shown = shown()
        val message = bubbleAt?.takeIf { it.isAttached }
        val within = rowAt?.takeIf { it.isAttached }
        if (shown == 0f || message == null || within == null) return
        val at = within.localPositionOf(message, Offset.Zero)
        val left =
            if (look.rtl) {
                at.x + message.size.width - look.threshold + look.gap
            } else {
                at.x + look.threshold - look.gap - look.glyph
            }
        val top = at.y + (message.size.height - look.glyph) / 2f
        val scale = GLYPH_SCALE_FROM + (1f - GLYPH_SCALE_FROM) * shown
        scope.translate(left, top) {
            scale(scale, Offset(look.glyph / 2f, look.glyph / 2f)) {
                with(look.painter) { draw(Size(look.glyph, look.glyph), alpha = shown, colorFilter = look.tint) }
            }
        }
    }

    /** How much of the glyph shows: the travel's share of the threshold, or under reduce-motion all once past it. */
    private fun shown(): Float =
        when {
            !look.reduced -> (offset.floatValue / look.threshold).coerceIn(0f, 1f)
            past.value -> 1f
            else -> 0f
        }
}

/** [swipe] on a message: its drag, and its layer, which follows the finger. */
internal fun Modifier.replySwipe(swipe: ReplySwipe): Modifier = this then swipe.bubble

/**
 * The swipe of one message, made once for it while it takes a reply ([enabled]): [onReply] runs as a let go past the
 * threshold, read at that moment; the threshold felt through the window's view ([HapticUse.ReplyThreshold]).
 */
@Composable
internal fun rememberReplySwipe(
    enabled: Boolean,
    onReply: () -> Unit,
): ReplySwipe {
    val view = LocalView.current
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spring = LocalFermixMotion.current.defaultSpatial
    val ink = LocalFermixColors.current.ink
    val painter = painterResource(R.drawable.ic_chat_reply)
    val reply by rememberUpdatedState(onReply)
    return remember(enabled, view, density, reduced, rtl, spring, ink, painter) {
        val look =
            with(density) {
                SwipeLook(
                    REPLY_SWIPE.toPx(),
                    REPLY_OVERSHOOT.toPx(),
                    GLYPH.toPx(),
                    GLYPH_GAP.toPx(),
                    reduced,
                    rtl,
                    spring.spec(reduced),
                    painter,
                    ColorFilter.tint(ink),
                )
            }
        ReplySwipe(enabled, look, { crossed(view) }, { reply() })
    }
}

private fun crossed(view: View) {
    HapticFeedback.perform(view, HapticUse.ReplyThreshold)
}
