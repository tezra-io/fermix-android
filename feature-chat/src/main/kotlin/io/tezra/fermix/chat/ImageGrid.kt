package io.tezra.fermix.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.RingOn
import io.tezra.fermix.design.rowFocusRing

/** The longest edge a bubble's image decodes at: a phone's width at 3×, enough for 88 % of it. */
const val BUBBLE_EDGE_PX = 1_080

/** The gap between a grid's images (the canon's `.ig`, 2). */
private val GRID_GAP = 2.dp

/** The overlay over the last cell's "+N" (the canon's rgba(11,11,13,.5)). */
private val MORE_SCRIM = Color(0x800B0B0D)

/** The ring of an image going up (the canon's `.ring`): 44 dp, a 3 dp stroke, its track white at .35. */
private val RING = 44.dp
private val RING_STROKE = 3.dp
private val RING_TRACK = Color.White.copy(alpha = 0.35f)
private const val FULL_TURN = 360f
private const val RING_START = -90f

/** The gone frame's aspect (the canon's `.gone`, 16:7). */
private const val GONE_ASPECT = 16f / 7f

/** The aspects the canon gives two images (3:4 each) and four (4:3 each), and a square for three's large one. */
private const val PAIR_ASPECT = 3f / 4f
private const val QUAD_ASPECT = 4f / 3f

/** Three images: the large square beside the column of the other two, half as wide as it is tall. */
private const val TRIO = 3
private const val STACK_ASPECT = 0.5f

/**
 * The shared-element transition between a bubble's image and the viewer (design section 13.7): the screen's
 * [scope], and the image the viewer has open, by its cell's key, which its bubble hides while it is open.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
class ViewerTransition(
    val scope: SharedTransitionScope,
    val open: String?,
)

/** The screen's viewer transition, none in a preview, where images are drawn plainly. */
val LocalViewerTransition = compositionLocalOf<ViewerTransition?> { null }

/** A bubble's image's key, the one its shared element and the viewer name it by. */
fun cellKey(
    messageKey: String,
    index: Int,
): String = "$messageKey/$index"

/**
 * A message's images (design section 13.5, the canon's `.ig`): one fills the card at its own aspect clamped to
 * 3:4…16:9; two side by side at 3:4; three as one large square and two beside it; four or more as a 2 × 2 at 4:3,
 * its last cell "+N". A tap opens the viewer at that image.
 */
@Composable
internal fun ImageGrid(
    images: List<ShownMedia>,
    messageKey: String,
    context: TimelineContext,
) {
    val layout = imageLayoutOf(images.size)
    val cell = @Composable { index: Int, modifier: Modifier ->
        val more = if (index == layout.shown - 1) layout.more else 0
        MediaCell(images[index], CellSpot(messageKey, index, more, lone = layout.shown == 1), context, modifier)
    }
    when (layout.shown) {
        1 -> {
            cell(0, Modifier.fillMaxWidth())
        }

        2 -> {
            Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                cell(0, Modifier.weight(1f).aspectRatio(PAIR_ASPECT))
                cell(1, Modifier.weight(1f).aspectRatio(PAIR_ASPECT))
            }
        }

        TRIO -> {
            GridThree(cell)
        }

        else -> {
            GridFour(cell)
        }
    }
}

/** Where a cell stands: its message's key, its index, the "+N" it wears, and whether it is a lone image. */
private class CellSpot(
    val messageKey: String,
    val index: Int,
    val more: Int,
    val lone: Boolean,
) {
    val key: String get() = cellKey(messageKey, index)
}

/** Three: the first square on the left, two thirds of the width, the other two stacked on the right. */
@Composable
private fun GridThree(cell: @Composable (Int, Modifier) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
        cell(0, Modifier.weight(2f).aspectRatio(1f))
        Column(
            modifier = Modifier.weight(1f).aspectRatio(STACK_ASPECT),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        ) {
            cell(1, Modifier.weight(1f).fillMaxWidth())
            cell(2, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Four or more: a 2 × 2 at 4:3. */
@Composable
private fun GridFour(cell: @Composable (Int, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
        Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
            cell(0, Modifier.weight(1f).aspectRatio(QUAD_ASPECT))
            cell(1, Modifier.weight(1f).aspectRatio(QUAD_ASPECT))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
            cell(2, Modifier.weight(1f).aspectRatio(QUAD_ASPECT))
            cell(GRID_CELLS - 1, Modifier.weight(1f).aspectRatio(QUAD_ASPECT))
        }
    }
}

/**
 * One image of a bubble: its placeholder colour while it streams (the first chunk's, MediaPipeline.dominantColour,
 * or the hairline before any), then the image, cropped to its cell; "No longer on {host}" once the daemon let it
 * go; the ring while it goes up; "+[more]" over the last cell of four. Asked again as a connection comes. [modifier]
 * sizes it; a lone image takes its own aspect once decoded.
 */
@Composable
private fun MediaCell(
    media: ShownMedia,
    spot: CellSpot,
    context: TimelineContext,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    val image by produceState<MediaImage?>(null, media.cacheName, media.local, context.linkUp) {
        value = context.media.actions.image(media, BUBBLE_EDGE_PX)
    }
    val shown = image as? MediaImage.Shown
    if (image == MediaImage.Gone) {
        return if (spot.lone) GoneFrame(context.host, modifier) else GoneCell(context.host, modifier)
    }
    val sized = if (spot.lone) modifier.aspectRatio(aspectOf(shown)) else modifier
    val placeholder =
        context.media.ui.colours[media.cacheName]
            ?.let { Color(it) } ?: colors.hairline
    val open = { context.media.onView(spot.messageKey, spot.index) }
    Box(
        modifier =
            sized
                .rowFocusRing(RingOn.Picture)
                .background(placeholder)
                .clickable(enabled = shown != null, role = Role.Image, onClick = open),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) SharedImage(shown, spot.key, Modifier.fillMaxSize())
        media.sent?.let { Ring(it) }
        if (spot.more > 0) MoreOverlay(spot.more)
    }
}

/** The aspect a lone image takes: its own, clamped, once decoded; 4:3 before. */
private fun aspectOf(shown: MediaImage.Shown?): Float =
    singleAspect(shown?.bitmap?.width ?: 0, shown?.bitmap?.height ?: 0)

/**
 * [image] cropped to [modifier]'s bounds, the shared element of its cell's [key] when the screen has a viewer
 * transition, hidden while the viewer holds it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedImage(
    image: MediaImage.Shown,
    key: String,
    modifier: Modifier,
    scale: ContentScale = ContentScale.Crop,
) {
    val transition = LocalViewerTransition.current
    val label = stringResource(R.string.chat_image)
    if (transition == null) {
        Image(image.bitmap, label, contentScale = scale, modifier = modifier)
        return
    }
    AnimatedVisibility(visible = transition.open != key, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        with(transition.scope) {
            Image(
                image.bitmap,
                label,
                contentScale = scale,
                modifier =
                    Modifier.fillMaxSize().sharedElement(
                        rememberSharedContentState(key),
                        this@AnimatedVisibility,
                    ),
            )
        }
    }
}

/** The ring over an image going up: its track, and the share [sent] of it as a white arc from the top. */
@Composable
private fun Ring(sent: Float) {
    Canvas(modifier = Modifier.size(RING)) {
        val stroke = RING_STROKE.toPx()
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        val corner = Offset(inset, inset)
        drawArc(RING_TRACK, 0f, FULL_TURN, false, corner, arc, style = Stroke(stroke))
        drawArc(Color.White, RING_START, FULL_TURN * sent.coerceIn(0f, 1f), false, corner, arc, style = Stroke(stroke))
    }
}

/** "+N" over the last of four cells (the canon's `.ig .plus`). */
@Composable
private fun MoreOverlay(more: Int) {
    Box(modifier = Modifier.fillMaxSize().background(MORE_SCRIM), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.chat_more_images, more),
            style = FermixType.label.copy(fontSize = 20.sp, lineHeight = 28.sp),
            color = Color.White,
        )
    }
}

/**
 * One cell of a grid whose image the daemon let go: the image glyph over "No longer on {host}", centred, at most two
 * lines, in the cell's own size, which a lone image's 16:7 row would outgrow at a third of the card in large type.
 */
@Composable
private fun GoneCell(
    host: String,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    Column(
        modifier = modifier.background(colors.agentBubble).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val glyph = Modifier.size(20.dp)
        Icon(painterResource(R.drawable.ic_chat_image), null, tint = colors.textSecondary, modifier = glyph)
        Text(
            stringResource(R.string.chat_media_gone, host),
            style = FermixType.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An image the daemon let go (the canon's `.gone`): 16:7, the image glyph and "No longer on {host}". */
@Composable
internal fun GoneFrame(
    host: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier = modifier.fillMaxWidth().aspectRatio(GONE_ASPECT).background(colors.agentBubble),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_chat_image),
            null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            stringResource(R.string.chat_media_gone, host),
            style = FermixType.bodyMedium,
            color = colors.textSecondary,
        )
    }
}
