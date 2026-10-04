package io.tezra.fermix.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixType

/** The longest edge the viewer decodes an image at: a 1440 dp-tall fold at 3×, enough to zoom into. */
const val VIEWER_EDGE_PX = 4_096

/** The furthest the viewer zooms in (design section 13.7, "pinch to 5×"). */
const val MAX_ZOOM = 5f

/** A swipe down past this, with the image at its own size, puts the viewer down (design section 13.7). */
private val DISMISS_SWIPE = 120.dp

/** The action's words (the canon's `.view .acts a`, 500 12/16) and its narrowest. */
private val ACTION_TYPE = FermixType.label.copy(fontSize = 12.sp, lineHeight = 16.sp)
private val ACTION_WIDTH = 88.dp

/** One image the viewer pages through: its cell's [key], its message's, and the blob. */
data class ViewedImage(
    val key: String,
    val messageKey: String,
    val media: ShownMedia,
)

/** Every image the list holds, oldest first, each by its cell's key: what the viewer swipes between. */
fun viewerImages(items: List<ChatItem>): List<ViewedImage> =
    items.asReversed().filterIsInstance<ChatItem.Message>().flatMap { item ->
        item.message.media
            .filter { it.shape == MediaShape.IMAGE }
            .mapIndexed { index, media -> ViewedImage(cellKey(item.key, index), item.key, media) }
    }

/**
 * What the viewer does: the image it shows changed ([onPage], by its cell's key); back, the swipe down ([onClose]);
 * Share, Save to Pictures/Fermix, and Show in chat, which puts it down at its message ([onShowInChat]).
 */
data class ViewerActions(
    val onPage: (String) -> Unit = {},
    val onClose: () -> Unit = {},
    val onShare: (ShownMedia) -> Unit = {},
    val onSave: (ShownMedia) -> Unit = {},
    val onShowInChat: (String) -> Unit = {},
)

/**
 * The viewer over the chat while an image is [open], by its cell's key (design section 13.7): it fades in and
 * out, and the image moves between its bubble and the viewer as a shared element; it keeps the last image it
 * showed while it fades out.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ViewerLayer(
    images: List<ViewedImage>,
    open: String?,
    context: TimelineContext,
    actions: ViewerActions,
) {
    var last by remember { mutableStateOf(open) }
    if (open != null) last = open
    val shown = last ?: return
    AnimatedVisibility(visible = open != null, enter = fadeIn(), exit = fadeOut()) {
        MediaViewer(images, shown, context, actions, this)
    }
}

/**
 * The media viewer (design section 13.7, the canon's `.view`): black in both themes, the images of the chat paged
 * left and right from [open], each pinched to [MAX_ZOOM] and panned while zoomed, a swipe down at its own size
 * putting it down; back above, Share, Save and Show in chat below. [visibility] carries the shared element; none in
 * a preview, which draws the image plainly. It follows the window as it turns.
 */
@Composable
internal fun MediaViewer(
    images: List<ViewedImage>,
    open: String,
    context: TimelineContext,
    actions: ViewerActions,
    visibility: AnimatedVisibilityScope?,
) {
    val start = images.indexOfFirst { it.key == open }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { images.size }
    val onPage by rememberUpdatedState(actions.onPage)
    LaunchedEffect(pager, images) {
        snapshotFlow { pager.currentPage }.collect { page -> images.getOrNull(page)?.let { onPage(it.key) } }
    }
    val current = images.getOrNull(pager.currentPage)
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ViewerBar(actions.onClose)
        HorizontalPager(
            state = pager,
            key = { images[it].key },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            ZoomedImage(images[page], context, actions.onClose, visibility)
        }
        if (current != null) ViewerActionsRow(current, actions)
    }
}

/** The viewer's bar: back, in white (the canon's `.view .abx`). */
@Composable
private fun ViewerBar(onClose: () -> Unit) {
    val label = stringResource(R.string.chat_back)
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_chat_back), label, tint = Color.White)
        }
    }
}

/**
 * One image of the viewer: decoded at [VIEWER_EDGE_PX], or "No longer on {host}"; pinched to [MAX_ZOOM] and panned
 * while zoomed; at its own size a swipe down past [DISMISS_SWIPE] puts the viewer down ([onClose]), a shorter one
 * springs back.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ZoomedImage(
    viewed: ViewedImage,
    context: TimelineContext,
    onClose: () -> Unit,
    visibility: AnimatedVisibilityScope?,
) {
    val image by produceState<MediaImage?>(null, viewed.media.cacheName, context.linkUp) {
        value = context.media.actions.image(viewed.media, VIEWER_EDGE_PX)
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val transform =
        rememberTransformableState { _, zoomBy, panBy, _ ->
            zoom = (zoom * zoomBy).coerceIn(1f, MAX_ZOOM)
            pan = if (zoom > 1f) pan + panBy else Offset.Zero
        }
    val swipe = rememberDraggableState { dragged = (dragged + it).coerceAtLeast(0f) }
    val dismissPx = with(LocalDensity.current) { DISMISS_SWIPE.toPx() }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .transformable(transform, canPan = { zoom > 1f })
                .draggable(swipe, Orientation.Vertical, enabled = zoom == 1f, onDragStopped = {
                    if (dragged > dismissPx) onClose() else dragged = 0f
                }),
        contentAlignment = Alignment.Center,
    ) {
        val layer =
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y + dragged
            }
        when (val shown = image) {
            is MediaImage.Shown -> ViewedBitmap(shown, viewed.key, layer, visibility)
            MediaImage.Gone -> GoneFrame(context.host)
            MediaImage.Missing, null -> Unit
        }
    }
}

/** The decoded image at its own aspect, the shared element of its cell when the viewer has a transition. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ViewedBitmap(
    image: MediaImage.Shown,
    key: String,
    modifier: Modifier,
    visibility: AnimatedVisibilityScope?,
) {
    val label = stringResource(R.string.chat_image)
    val transition = LocalViewerTransition.current
    if (transition == null || visibility == null) {
        Image(image.bitmap, label, contentScale = ContentScale.Fit, modifier = modifier)
        return
    }
    with(transition.scope) {
        Image(
            image.bitmap,
            label,
            contentScale = ContentScale.Fit,
            modifier = modifier.sharedElement(rememberSharedContentState(key), visibility),
        )
    }
}

/** Share, Save and Show in chat (the canon's `.view .acts`): a glyph over its word, in white. */
@Composable
private fun ViewerActionsRow(
    current: ViewedImage,
    actions: ViewerActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        ViewerAction(R.drawable.ic_chat_share, stringResource(R.string.chat_share)) { actions.onShare(current.media) }
        ViewerAction(R.drawable.ic_chat_save, stringResource(R.string.chat_save)) { actions.onSave(current.media) }
        ViewerAction(R.drawable.ic_chat_chat, stringResource(R.string.chat_show_in_chat)) {
            actions.onShowInChat(current.messageKey)
        }
    }
}

@Composable
private fun ViewerAction(
    icon: Int,
    words: String,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .widthIn(
                    min = ACTION_WIDTH,
                ).heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(icon), null, tint = Color.White)
        Text(words, style = ACTION_TYPE, color = Color.White, textAlign = TextAlign.Center)
    }
}
