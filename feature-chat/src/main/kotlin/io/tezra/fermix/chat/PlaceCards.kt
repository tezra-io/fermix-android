package io.tezra.fermix.chat

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.focusRing
import io.tezra.fermix.design.rowFocusRing
import io.tezra.fermix.protocol.Place

/** A place card's width as a share of the bubble's greatest (M72 section 14), so the next card shows past it. */
internal const val PLACE_CARD_SHARE = 0.72f

/** The room between two cards in the lane. */
private val PLACE_GAP = 8.dp

/** The lines a card draws under the place's name, a blank one for each the record lacks: every card is one height. */
private const val FACT_LINES = 2

/** A place card's menu entries (M72 section 14), in the menu's order, each its icon and its words. */
internal enum class PlaceEntry(
    @param:DrawableRes val icon: Int,
    @param:StringRes val words: Int,
) {
    OPEN_IN_MAPS(R.drawable.ic_chat_pin, R.string.chat_place_open_in_maps),
    OPEN_WEBSITE(R.drawable.ic_chat_globe, R.string.chat_place_open_website),
    COPY_ADDRESS(R.drawable.ic_chat_copy, R.string.chat_place_copy_address),
}

/** [place]'s menu: Open in Maps; Open website when a page of its opens (websiteOf); Copy address when it has one. */
internal fun placeMenuOf(place: Place): List<PlaceEntry> =
    listOfNotNull(
        PlaceEntry.OPEN_IN_MAPS,
        PlaceEntry.OPEN_WEBSITE.takeIf { websiteOf(place) != null },
        PlaceEntry.COPY_ADDRESS.takeIf { place.address != null },
    )

/** What [entry] does for [place]: the Maps app, its page in a Custom Tab, or its address copied, with the toast. */
private fun pick(
    entry: PlaceEntry,
    place: Place,
    context: TimelineContext,
) {
    when (entry) {
        PlaceEntry.OPEN_IN_MAPS -> context.cards.onPlace(place)
        PlaceEntry.OPEN_WEBSITE -> websiteOf(place)?.let(context.cards.onLink)
        PlaceEntry.COPY_ADDRESS -> place.address?.let(context.text.copy)
    }
}

/**
 * The places an agent's reply links, as cards in a sideways lane under its prose and its cards and above its link
 * previews (M72 section 14), in the order the reply first links them, with no number and no map: each card about
 * 72 % of the bubble's greatest width, so the next shows past it, and a card alone that whole width; a fling settles
 * with a card at the lane's start.
 */
@Composable
internal fun PlaceCards(
    places: List<Place>,
    context: TimelineContext,
) {
    if (places.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = FermixSpacing.withinGroup)) {
        val bubble = maxWidth * FermixSpacing.AGENT_BUBBLE_MAX_WIDTH
        val width = if (places.size == 1) bubble else bubble * PLACE_CARD_SHARE
        val lane = rememberLazyListState()
        LazyRow(
            state = lane,
            flingBehavior = rememberSnapFlingBehavior(lane, SnapPosition.Start),
            horizontalArrangement = Arrangement.spacedBy(PLACE_GAP),
        ) {
            items(places) { place -> PlaceCard(place, context, Modifier.width(width)) }
        }
    }
}

/**
 * One place, in the link preview's card under the agent's reply: its name, then its facts line and its hours line
 * (factsLine, hoursLine), each one line that ends in "…", a blank line for each the record lacks, so every card is
 * one height at every type size. A tap opens it in the Maps app ([CardActions.onPlace]); a long-press opens its menu
 * (placeMenuOf). TalkBack stops on the card once and hears it in one sentence (spokenPlace), its tap "Open in Maps"
 * and the menu's other entries its custom actions.
 */
@Composable
private fun PlaceCard(
    place: Place,
    context: TimelineContext,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    val resources = LocalResources.current
    val locale = context.locale
    val lines =
        remember(place, locale, resources) {
            listOfNotNull(factsLine(place, locale, resources), hoursLine(place, locale, resources))
        }
    val spoken = remember(place, locale, resources) { spokenPlace(place, locale, resources) }
    val entries = remember(place) { placeMenuOf(place) }
    val labels = entries.associateWith { stringResource(it.words) }
    val open = labels.getValue(PlaceEntry.OPEN_IN_MAPS)
    var menu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        role = Role.Button
                        onClick(open) { true.also { context.cards.onPlace(place) } }
                        customActions =
                            entries.drop(1).map { entry ->
                                CustomAccessibilityAction(labels.getValue(entry)) {
                                    true.also { pick(entry, place, context) }
                                }
                            }
                    }.focusRing(PREVIEW_UNDER_AGENT)
                    .clip(PREVIEW_UNDER_AGENT)
                    .background(colors.agentBubble)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = open,
                        onLongClick = { menu = true },
                        onClick = { context.cards.onPlace(place) },
                    ).padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
        ) {
            Text(place.name, style = PREVIEW_TITLE, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            repeat(FACT_LINES) { line ->
                Text(
                    lines.getOrElse(line) { "" },
                    style = FermixType.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PlaceMenu(menu, entries, { entry -> pick(entry, place, context).also { menu = false } }) { menu = false }
    }
}

/** A place card's menu (M72 section 14), under the card, in the chat's menu style (OutboxMenu). */
@Composable
private fun PlaceMenu(
    expanded: Boolean,
    entries: List<PlaceEntry>,
    onPick: (PlaceEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        entries.forEach { entry ->
            DropdownMenuItem(
                text = { Text(stringResource(entry.words), style = FermixType.body) },
                leadingIcon = { Icon(painterResource(entry.icon), null) },
                modifier = Modifier.rowFocusRing(),
                onClick = { onPick(entry) },
            )
        }
    }
}
