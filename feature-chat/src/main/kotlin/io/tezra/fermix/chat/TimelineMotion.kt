package io.tezra.fermix.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalFermixMotion
import io.tezra.fermix.design.LocalReducedMotion
import kotlin.math.roundToInt

/** The skeleton's bubbles, as the canon's `.skel`: 40 dp tall, fully round, the agent's tone at 70 %. */
private val SKELETON_HEIGHT = 40.dp
private const val SKELETON_ALPHA = 0.7f
private const val SKELETON_USER_WIDTH = 0.52f
private const val SKELETON_AGENT_WIDTH = 0.72f
private const val SKELETON_SHORT_WIDTH = 0.44f

/** A picture's landing: the card's words leave in the first of these parts of the morph as the picture comes in. */
private const val PICTURE_FADE_OUT_PARTS = 3

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
 * changes at once. Nothing pops, nothing is visibly deleted (section 13.10). A turn's picture takes the card's place
 * so too (TurnMedia), in a landing of its own ([morphOf]), its bounds growing as [growing] has them.
 */
@Composable
internal fun Morph(
    item: ChatItem,
    content: @Composable (ChatItem) -> Unit,
) {
    val time = if (LocalReducedMotion.current) 0 else FermixMotion.THINKING_TO_ANSWER_MILLIS
    val transition = updateTransition(item, label = "thinking to answer")
    val grown =
        transition.animateFloat({ tween(time, easing = FermixMotion.emphasized) }, label = "picture growing") {
            if (it is ChatItem.Thinking) 0f else 1f
        }
    val growth = remember { Growth() }
    transition.AnimatedContent(
        modifier = Modifier.growing(isPicture(item), grown, growth),
        contentKey = { it is ChatItem.Thinking },
        contentAlignment = Alignment.BottomStart,
        transitionSpec = { morphOf(targetState, time) },
    ) { shown -> content(shown) }
}

/** A picture's height as it lands: [from] its card's, last measured, to [to], its own as the lookahead has it. */
private class Growth {
    var from = 0
    var to: Int? = null
}

/**
 * The morph's item at a landing picture's height, [grown] of the way from its card's to its own, the same in the
 * lookahead pass (ChatScreen's SharedTransitionLayout) as on screen, so the list places the rows above at the
 * picture's top on every frame (Task 23's second review: from the lookahead's sizes the list placed them at once
 * where the grown picture's top would be, over a blank band the picture had yet to grow into); the picture clipped
 * to that height as it grows, its foot at the item's. Any other item, and a picture once grown, keeps its own height,
 * unclipped. [growth] holds the heights between frames: outside a lookahead, the item's own as it is measured.
 */
private fun Modifier.growing(
    picture: Boolean,
    grown: State<Float>,
    growth: Growth,
): Modifier =
    drawWithContent {
        if (picture && grown.value < 1f) clipRect { this@drawWithContent.drawContent() } else drawContent()
    }.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        if (isLookingAhead) growth.to = placeable.height
        if (!picture) growth.from = placeable.height
        val fraction = grown.value
        val to = growth.to ?: placeable.height
        val growing = picture && fraction < 1f
        val height = if (growing) growth.from + ((to - growth.from) * fraction).roundToInt() else placeable.height
        layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
    }

/**
 * The card becoming [target] over [time]: a bubble fades in as the card fades out, its bounds unclipped as they move;
 * a picture (Task 23's review: a picture many times the card's height was drawn whole from the first frame, over the
 * owner's request above it) grows in bounds of its own ([growing]), not these, and fades in from the first frame as
 * the card's words fade out in the first third, so one or the other is drawn on every frame (Task 23's second
 * review: a picture that waited for the words to go left the list blank while its bounds grew).
 */
private fun morphOf(
    target: ChatItem,
    time: Int,
): ContentTransform {
    val fade = tween<Float>(time, easing = FermixMotion.emphasized)
    if (!isPicture(target)) {
        val bounds = { _: IntSize, _: IntSize -> tween<IntSize>(time, easing = FermixMotion.emphasized) }
        return ContentTransform(fadeIn(fade), fadeOut(fade), sizeTransform = SizeTransform(clip = false, bounds))
    }
    val exit = fadeOut(tween(time / PICTURE_FADE_OUT_PARTS, easing = FermixMotion.emphasized))
    return ContentTransform(fadeIn(fade), exit, sizeTransform = null)
}

/**
 * How an item slides to its new place as the list changes (LazyItemScope.animateItem's placementSpec): at
 * animateItem's own spring, and not at all while a picture lands ([landing]), so the items above follow its bounds as
 * they grow, frame for frame, as a slide lags behind the growth and leaves an item drawn over the picture (Task 23's
 * review).
 */
internal fun itemSlide(landing: Boolean): FiniteAnimationSpec<IntOffset>? =
    if (landing) {
        null
    } else {
        spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
    }

/** The keys of a turn's pictures under their cards' keys as last drawn, and when the last of them landed. */
private class DrawnPictures {
    var keys: List<String>? = null
    var landedAt: Long? = null
}

/**
 * Whether a turn's picture lands: one took its card's place in this draw of [items] (TurnMedia), or did within the
 * morph's time before it, on [nowMono]. None as the list first draws, so nothing lands as a chat opens. Read as the
 * list composes, from the draw before, as [rememberFreshKeys] is.
 */
@Composable
internal fun rememberPictureLanding(
    items: List<ChatItem>,
    nowMono: () -> Long,
): Boolean {
    val drawn = remember { DrawnPictures() }
    val pictures = items.filter { it.key.startsWith(CARD_KEY_PREFIX) && isPicture(it) }.map { it.key }
    val now = nowMono()
    val landed = drawn.keys?.let { before -> pictures.any { it !in before } } == true
    val since = drawn.landedAt?.let { now - it }
    val landing = landed || (since != null && since < FermixMotion.THINKING_TO_ANSWER_MILLIS)
    SideEffect {
        drawn.keys = pictures
        if (landed) drawn.landedAt = now
    }
    return landing
}

/** Whether [item] is a message with media, as a turn's picture is. */
private fun isPicture(item: ChatItem): Boolean = (item as? ChatItem.Message)?.message?.media?.isNotEmpty() == true

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
