package io.tezra.fermix.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixMotionScheme
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.Sender
import io.tezra.fermix.design.focusRing
import io.tezra.fermix.protocol.LinkPreviewCard

/**
 * The reaction chip's height (design section 13.5: a 24 dp pill), which it grows past with the type size so
 * the emoji is never cut, and its least width (the canon's `.rx`).
 */
private val REACTION_HEIGHT = 24.dp
private val REACTION_MIN_WIDTH = 32.dp

/**
 * Space the reaction chip takes under its bubble (the canon's `.b.rxd`), how far a 24 dp chip hangs past it, and
 * so how far it overlaps the bubble's bottom, which stays so at every type size: a taller chip grows down.
 */
private val REACTION_ROOM = 12.dp
private val REACTION_HANG = 14.dp
private val REACTION_OVERLAP = REACTION_HEIGHT - REACTION_HANG
private val REACTION_LEAD = (-4).dp

/**
 * The room under a reacted bubble: the canon's 12 dp, or the chip's whole hang when link previews follow, so the
 * chip's edge and the first card's never touch; more by as much as the chip grew past 24 dp.
 */
private fun reactionRoom(previewsFollow: Boolean): Dp = if (previewsFollow) REACTION_HANG else REACTION_ROOM

/**
 * The owner's [bubble] with the host's reaction [chip] hung over its bottom-left (design section 13.5, the
 * canon's `.rx`): the chip's top [REACTION_OVERLAP] above the bubble's bottom, inside its padding, at every type
 * size, so a chip that grows with the type grows down and never over the words; the room under the bubble grows
 * with it ([reactionRoom]).
 */
@Composable
internal fun ReactedBubble(
    previewsFollow: Boolean,
    bubble: @Composable () -> Unit,
    chip: @Composable () -> Unit,
) {
    Layout(contents = listOf(bubble, chip)) { (bubbles, chips), constraints ->
        val shown = bubbles.single().measure(constraints)
        val hung = chips.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val grown = (hung.height - REACTION_HEIGHT.roundToPx()).coerceAtLeast(0)
        val room = reactionRoom(previewsFollow).roundToPx() + grown
        layout(shown.width, shown.height + room) {
            shown.place(0, 0)
            hung.place(REACTION_LEAD.roundToPx(), shown.height - REACTION_OVERLAP.roundToPx())
        }
    }
}

/** A preview's description runs at most this many lines (the canon's `.lp p`). */
private const val DESCRIPTION_LINES = 2

/** A preview's thumbnail, 16:9 (the canon's `.lp .th`). */
private const val THUMBNAIL_RATIO = 16f / 9f

/** The canon's `.lp b`, 600 14/20. */
private val PREVIEW_TITLE = FermixType.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

/** The canon's `.rx`, 400 13/22. */
private val REACTION_STYLE = FermixType.bodyMedium.copy(fontSize = 13.sp, lineHeight = 22.sp)

/** A preview's corner nearest the message it sits under (the canon's `.card.g`): the inner 6 dp. */
private val PREVIEW_UNDER_AGENT =
    RoundedCornerShape(topStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
private val PREVIEW_UNDER_USER =
    RoundedCornerShape(topStart = 16.dp, topEnd = 6.dp, bottomEnd = 16.dp, bottomStart = 16.dp)

/**
 * The host's reaction on the owner's message (design section 13.5, "Reaction chip"): a 24 dp pill on the canvas,
 * a pill still as it grows with the type, hung over the bubble's bottom-left ([ReactedBubble]). One that lands
 * while the message is on screen ([pops]) pops in on the expressive spring, as section 13.5 names it; under
 * reduce-motion, and for one already there as the message comes into view, it is simply there.
 */
@Composable
internal fun ReactionChip(
    emoji: String,
    pops: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    val reduced = LocalReducedMotion.current
    val scale = remember(emoji) { Animatable(if (pops && !reduced) 0f else 1f) }
    LaunchedEffect(emoji) { scale.animateTo(1f, FermixMotionScheme.Expressive.fastSpatial.spec(reduced)) }
    val label = stringResource(R.string.chat_reaction, emoji)
    Text(
        emoji,
        style = REACTION_STYLE,
        color = colors.ink,
        textAlign = TextAlign.Center,
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }.heightIn(min = REACTION_HEIGHT)
                .defaultMinSize(minWidth = REACTION_MIN_WIDTH)
                .clip(FermixShapes.button)
                .background(colors.canvas)
                .border(FermixSpacing.hairline, colors.hairline, FermixShapes.button)
                .padding(horizontal = 7.dp)
                .semantics { contentDescription = label },
    )
}

/**
 * A message's link previews (design section 13.5, "Link preview"), at most two, under it on its sender's side
 * and grouped with it: site, title and description, and the 16:9 thumbnail the daemon serves for its
 * `image_ref` ([TimelineContext.cards]' thumbnail), never one fetched from its own address. A tap opens the
 * link in a Custom Tab tinted with the instance's colour.
 */
@Composable
internal fun LinkPreviews(
    previews: List<LinkPreviewCard>,
    sender: Sender,
    context: TimelineContext,
) {
    if (previews.isEmpty()) return
    val user = sender == Sender.User
    val width = if (user) FermixSpacing.USER_BUBBLE_MAX_WIDTH else FermixSpacing.AGENT_BUBBLE_MAX_WIDTH
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = FermixSpacing.withinGroup),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(FermixSpacing.withinGroup),
    ) {
        previews.take(MAX_SHOWN_PREVIEWS).forEach { card ->
            LinkPreview(
                card,
                if (user) PREVIEW_UNDER_USER else PREVIEW_UNDER_AGENT,
                context,
                Modifier.fillMaxWidth(width),
            )
        }
    }
}

@Composable
private fun LinkPreview(
    card: LinkPreviewCard,
    shape: RoundedCornerShape,
    context: TimelineContext,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    val open = stringResource(R.string.chat_open_link, card.site)
    Column(
        modifier =
            modifier
                .focusRing(shape)
                .clip(shape)
                .background(colors.agentBubble)
                .clickable(role = Role.Button, onClickLabel = open) { context.cards.onLink(card.url) },
    ) {
        card.imageRef?.let { Thumbnail(it, context) }
        Column(modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp)) {
            Text(
                card.site,
                style = FermixType.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(card.title, style = PREVIEW_TITLE, color = colors.ink)
            card.description?.let {
                Text(
                    it,
                    style = FermixType.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = DESCRIPTION_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** A thumbnail on its way, shown, or not to be had. */
private sealed interface Thumb {
    data object Loading : Thumb

    data class Shown(
        val image: ImageBitmap,
    ) : Thumb

    data object None : Thumb
}

/**
 * The 16:9 thumbnail of [ref]: a hairline block while it comes, the picture once it came, and nothing when it
 * did not. It is asked again as a connection comes up ([TimelineContext.linkUp]).
 */
@Composable
private fun Thumbnail(
    ref: String,
    context: TimelineContext,
) {
    val thumb by produceState<Thumb>(Thumb.Loading, ref, context.linkUp) {
        value = context.cards.thumbnail(ref)?.let { Thumb.Shown(it) } ?: Thumb.None
    }
    val frame = Modifier.fillMaxWidth().aspectRatio(THUMBNAIL_RATIO)
    when (val shown = thumb) {
        Thumb.Loading -> Box(frame.background(LocalFermixColors.current.hairline))
        is Thumb.Shown -> Image(shown.image, null, frame, contentScale = ContentScale.Crop)
        Thumb.None -> Unit
    }
}

/** How far a jump's ring stands out of its bubble (the canon's `.b.hl`, a 6 dp spread). */
private val RING_SPREAD = 6.dp

/**
 * The ring's opacity for a row the screen jumped to ([lit], design section 13.7): the ink at 12 % while it
 * pulses, fading in and out in the cross-fade's time, and none at once under reduce-motion.
 */
@Composable
internal fun ringAlpha(lit: Boolean): Float {
    val time = if (LocalReducedMotion.current) 0 else FermixMotion.INDICATOR_CROSS_FADE_MILLIS
    val alpha by animateFloatAsState(
        targetValue = if (lit) FermixMotion.JUMP_HIGHLIGHT_ALPHA else 0f,
        animationSpec = tween(time),
        label = "jump ring",
    )
    return alpha
}

/**
 * The jump's ring behind a bubble of [shape] at [alpha] of [color], [RING_SPREAD] past its edges, each corner
 * rounded out from the bubble's own, a grouped 6 dp corner too, as the canon's spread follows them; none at 0.
 */
internal fun Modifier.pulseRing(
    alpha: Float,
    color: Color,
    shape: RoundedCornerShape,
): Modifier =
    if (alpha <= 0f) {
        this
    } else {
        drawBehind {
            val spread = RING_SPREAD.toPx()
            val ltr = layoutDirection == LayoutDirection.Ltr
            val out = { corner: CornerSize -> CornerRadius(corner.toPx(size, this) + spread) }
            val ring =
                RoundRect(
                    rect = Rect(-spread, -spread, size.width + spread, size.height + spread),
                    topLeft = out(if (ltr) shape.topStart else shape.topEnd),
                    topRight = out(if (ltr) shape.topEnd else shape.topStart),
                    bottomRight = out(if (ltr) shape.bottomEnd else shape.bottomStart),
                    bottomLeft = out(if (ltr) shape.bottomStart else shape.bottomEnd),
                )
            drawPath(Path().apply { addRoundRect(ring) }, color.copy(alpha = alpha))
        }
    }

/**
 * A bubble's time and, on the owner's, its mark (design section 13.5): the clock until `accepted`, then one
 * tick and never two; a refused one keeps the clock, as the canon draws it; at 60 %, in the bubble's ink, or
 * whole where it is not [faded]: on a selected row's wash, where the faded ink would not read (rowLine).
 */
@Composable
internal fun Stamp(
    message: ShownMessage,
    context: TimelineContext,
    ink: Color,
    modifier: Modifier = Modifier,
    faded: Boolean = true,
) {
    val time = message.wallMs?.let { timeOf(it, context) }
    val shade = if (faded) ink.copy(alpha = FermixSpacing.TIMESTAMP_ALPHA) else ink
    Row(
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        time?.let { Text(it, style = FermixType.labelSmall, color = shade) }
        DeliveryMark(message.delivery, shade)
    }
}

@Composable
private fun DeliveryMark(
    delivery: Delivery,
    tint: Color,
) {
    val (icon, label) =
        when (delivery) {
            Delivery.SENDING, Delivery.PENDING -> R.drawable.ic_chat_clock to R.string.chat_mark_sending

            Delivery.DELIVERED -> R.drawable.ic_chat_check to R.string.chat_mark_delivered

            // The canon keeps the clock; the failure is said under the bubble, on the canvas (StateLine).
            Delivery.FAILED -> R.drawable.ic_chat_clock to R.string.chat_mark_failed

            Delivery.NONE, Delivery.QUEUED -> return
        }
    Icon(painterResource(icon), stringResource(label), tint = tint, modifier = Modifier.size(12.dp))
}
