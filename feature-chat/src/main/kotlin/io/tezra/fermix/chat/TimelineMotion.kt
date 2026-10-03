package io.tezra.fermix.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalFermixMotion
import io.tezra.fermix.design.LocalReducedMotion

/** The skeleton's bubbles, as the canon's `.skel`: 40 dp tall, fully round, the agent's tone at 70 %. */
private val SKELETON_HEIGHT = 40.dp
private const val SKELETON_ALPHA = 0.7f
private const val SKELETON_USER_WIDTH = 0.52f
private const val SKELETON_AGENT_WIDTH = 0.72f
private const val SKELETON_SHORT_WIDTH = 0.44f

/**
 * The keys of [keys] (newest first) that landed at the bottom since [before], the keys of the list as it was
 * last drawn: those ahead of every key it held. None on the first draw ([before] null or empty), so nothing
 * rises as the chat opens, and none for an older page, which lands at the top.
 */
fun freshKeys(
    before: List<String>?,
    keys: List<String>,
): Set<String> {
    if (before.isNullOrEmpty()) return emptySet()
    val held = before.toHashSet()
    return keys.takeWhile { it !in held }.toSet()
}

/** The list's keys as last drawn, which the next draw's fresh keys are read against. */
private class DrawnKeys {
    var keys: List<String>? = null
}

/**
 * The keys of [items] that landed at the bottom since the list was last drawn (freshKeys). Read as the list
 * composes: its items compose as it lays out, after this draw is applied, so each reads the set of its own draw.
 */
@Composable
internal fun rememberFreshKeys(items: List<ChatItem>): Set<String> {
    val drawn = remember { DrawnKeys() }
    val keys = items.map { it.key }
    val fresh = freshKeys(drawn.keys, keys)
    SideEffect { drawn.keys = keys }
    return fresh
}

/**
 * An item that rises [FermixMotion.bubbleInsertRise] into its place as it lands, when [fresh], on the
 * standard scheme's spatial spring; the list's own item animation fades it in. [modifier] is the item's root's.
 */
@Composable
internal fun Rising(
    fresh: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val rise = with(LocalDensity.current) { FermixMotion.bubbleInsertRise.toPx() }
    val offset = remember { Animatable(if (fresh && !reduced) rise else 0f) }
    val spring = LocalFermixMotion.current.defaultSpatial
    LaunchedEffect(offset) { offset.animateTo(0f, spring.spec(reduced)) }
    Box(modifier = modifier.graphicsLayer { translationY = offset.value }) { content() }
}

/**
 * The thinking card becoming the answer's bubble (design section 13.5): one item whose bounds move from the
 * card's to the bubble's as one fades into the other, 300 ms on the emphasized easing; under reduce-motion it
 * changes at once. Nothing pops, nothing is visibly deleted (section 13.10).
 */
@Composable
internal fun Morph(
    item: ChatItem,
    content: @Composable (ChatItem) -> Unit,
) {
    val time = if (LocalReducedMotion.current) 0 else FermixMotion.THINKING_TO_ANSWER_MILLIS
    AnimatedContent(
        targetState = item,
        contentKey = { it is ChatItem.Thinking },
        contentAlignment = Alignment.BottomStart,
        transitionSpec = {
            val fade = tween<Float>(time, easing = FermixMotion.emphasized)
            val bounds = SizeTransform(clip = false) { _, _ -> tween(time, easing = FermixMotion.emphasized) }
            (fadeIn(fade) togetherWith fadeOut(fade)).using(bounds)
        },
        label = "thinking to answer",
    ) { shown -> content(shown) }
}

/**
 * The older page on its way (design section 13.5, the canon's "Older page loading"): three bubbles in the
 * thread's own rhythm, the owner's 52 % on the right, then the agent's 72 % and 44 % on the left, grouped.
 */
@Composable
internal fun OlderSkeleton(modifier: Modifier) {
    val tone = LocalFermixColors.current.agentBubble
    val shape = RoundedCornerShape(20.dp)
    Column(modifier = modifier.fillMaxWidth().alpha(SKELETON_ALPHA)) {
        val bubble = Modifier.height(SKELETON_HEIGHT).background(tone, shape)
        Spacer(bubble.fillMaxWidth(SKELETON_USER_WIDTH).align(Alignment.End))
        Spacer(Modifier.height(FermixSpacing.betweenGroups))
        Spacer(bubble.fillMaxWidth(SKELETON_AGENT_WIDTH))
        Spacer(Modifier.height(FermixSpacing.withinGroup))
        Spacer(bubble.fillMaxWidth(SKELETON_SHORT_WIDTH))
    }
}
