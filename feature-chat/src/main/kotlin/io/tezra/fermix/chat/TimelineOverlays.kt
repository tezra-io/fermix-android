package io.tezra.fermix.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.RingOn
import io.tezra.fermix.design.focusRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The badge's least height and width (the canon's `.badge`); its figures are label-small, in sp. */
private val BADGE_HEIGHT = 20.dp

/** The canvas ring that keeps the badge apart from a bubble under it. */
private val BADGE_RING = 2.dp

/**
 * Where the badge's bottom-end corner stands in the pill's 48 dp target: the canon's top -8, right -4 from the
 * circle, which sits 4 dp inside the target, with the badge's ring. A larger figure, or a larger font, grows the
 * badge up and toward the start from there: above the arrow, whose glyph starts below that bottom, and never
 * past the window's end.
 */
private val BADGE_END = 50.dp
private val BADGE_BOTTOM = 18.dp

/**
 * The day of the item at [index] in [items] (newest first): the day header above it, the first Day at or
 * past [index]. Null when no day header is there, as above the skeleton of an older page.
 */
fun dateAt(
    items: List<ChatItem>,
    index: Int,
): LocalDate? {
    require(index >= 0) { "an item's index is never negative" }
    return items.drop(index).firstNotNullOfOrNull { (it as? ChatItem.Day)?.date }
}

/**
 * What floats over the timeline, in its column (design section 13.5): the date pill while the list scrolls,
 * and the scroll-to-bottom pill once the list is off the bottom. [datePillHeld] keeps the date pill up.
 */
@Composable
internal fun TimelineOverlays(
    state: ChatScreenState,
    context: TimelineContext,
    listState: LazyListState,
    datePillHeld: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        DatePill(state.items, context, listState, datePillHeld, Modifier.align(Alignment.TopCenter))
        if (!listState.atBottom) {
            val scope = rememberCoroutineScope()
            ScrollPill(
                state.unseen,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * The sticky date pill (design sections 13.1 and 13.5): the day of the topmost item in view, shown while the
 * owner scrolls the list and faded [FermixMotion.DATE_PILL_FADE_DELAY_MILLIS] after it settles, on the control plane's
 * tone with its hairline so it stands apart from the bubbles it floats over; none while a day header is the
 * topmost item, which names its day itself.
 */
@Composable
private fun DatePill(
    items: List<ChatItem>,
    context: TimelineContext,
    listState: LazyListState,
    held: Boolean,
    modifier: Modifier,
) {
    // The owner's scroll only, a drag and the fling after it: the list following what lands moves it too.
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    val moving = listState.isScrollInProgress
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(dragged, moving) {
        if (dragged) shown = true
        if (dragged || moving) return@LaunchedEffect
        delay(FermixMotion.DATE_PILL_FADE_DELAY_MILLIS.toLong())
        shown = false
    }
    val top by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
        }
    }
    // A day header topmost in view names its day itself: the pill over it would say it twice.
    val date = top?.takeIf { items.getOrNull(it) !is ChatItem.Day }?.let { dateAt(items, it) }
    val reduced = LocalReducedMotion.current
    val fade = if (reduced) 0 else FermixMotion.INDICATOR_CROSS_FADE_MILLIS
    AnimatedVisibility(
        visible = (shown || held) && date != null,
        enter = fadeIn(tween(fade)),
        exit = fadeOut(tween(fade)),
        modifier = modifier.padding(top = 8.dp),
    ) {
        val colors = LocalFermixColors.current
        val shape = RoundedCornerShape(percent = 50)
        Text(
            text = date?.let { dayWords(it, context) }.orEmpty(),
            style = FermixType.labelSmall,
            color = colors.textSecondary,
            modifier =
                Modifier
                    .background(colors.tonalSolid, shape)
                    .border(FermixSpacing.hairline, colors.hairline, shape)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * The scroll-to-bottom pill (the canon's `.sp`): a 40 dp tonal circle in a 48 dp target, its badge the count of
 * the agent's unseen rows, outside the circle's top end with a canvas ring.
 */
@Composable
internal fun ScrollPill(
    unseen: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    Box(
        modifier =
            modifier
                .size(FermixSpacing.minTarget)
                // It floats over the timeline, so its ring lies on the owner's ink bubbles as often as on the canvas.
                .focusRing(CircleShape, RingOn.Picture)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(colors.tonalSolid, CircleShape)
                    .border(FermixSpacing.hairline, colors.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_chat_arrow_down),
                stringResource(R.string.chat_to_bottom),
                tint = colors.ink,
                modifier = Modifier.size(20.dp),
            )
        }
        if (unseen > 0) Badge(unseen, Modifier.align(Alignment.TopStart).growingUpFrom(BADGE_END, BADGE_BOTTOM))
    }
}

/** Places the content with its bottom-end corner at ([end], [bottom]), mirrored right to left, taking no room. */
private fun Modifier.growingUpFrom(
    end: Dp,
    bottom: Dp,
): Modifier =
    layout { measurable, _ ->
        val placeable = measurable.measure(Constraints())
        val x = end.roundToPx() - placeable.width
        val y = bottom.roundToPx() - placeable.height
        layout(0, 0) { placeable.placeRelative(x, y) }
    }

/** At least as wide as it is tall, its content centred: a round badge for one figure, a pill for more. */
private fun Modifier.atLeastAsWideAsTall(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val width = maxOf(placeable.width, placeable.height)
        layout(width, placeable.height) { placeable.place((width - placeable.width) / 2, 0) }
    }

@Composable
private fun Badge(
    count: Int,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    Box(
        modifier =
            modifier
                .background(colors.canvas, CircleShape)
                .padding(BADGE_RING)
                .background(colors.signal, CircleShape)
                .atLeastAsWideAsTall()
                .heightIn(min = BADGE_HEIGHT)
                .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$count",
            style = FermixType.labelSmall,
            color = colors.onSignal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
