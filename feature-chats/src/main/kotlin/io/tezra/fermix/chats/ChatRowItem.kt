package io.tezra.fermix.chats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.rowFocusRing
import io.tezra.fermix.instance.AvatarSize
import io.tezra.fermix.instance.InstanceAvatar
import io.tezra.fermix.instance.Link
import io.tezra.fermix.instance.Tag
import io.tezra.fermix.instance.dot
import io.tezra.fermix.instance.linkWords

// The visual canon's `.row`: 72 dp, 16 dp at the sides, 12 dp between the avatar, the text and the meta;
// 8 dp between the title and its tag; the meta 4 dp apart; the badge 20 dp, 6 dp inside, a pill. Its
// long-press menu opens over the row, its top 14 dp below the row's and 76 dp in, past the avatar, and the
// row is on the agent's tone while it is open. A row keeps 8 dp above and below what it holds, which a 72 dp
// row has to spare, so rows that a large font makes taller still stand apart. The DEV tag (`.tag`) has 1 dp
// above and below inside its hairline.
private val ROW_HEIGHT = 72.dp
private val ROW_SIDES = 16.dp
private val ROW_ENDS = 8.dp
private val ROW_GAP = 12.dp
private val TITLE_GAP = 8.dp
private val META_GAP = 4.dp
private val BADGE = 20.dp
private val BADGE_SIDES = 6.dp
internal val MENU_START = 76.dp
internal val MENU_TOP = 14.dp
private val TAG_ENDS = 1.dp

/** The canon's `.tag`, mono 500 10/14 tracked 0.6. */
private val TAG_STYLE =
    FermixType.mono.copy(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium)

/** The canon's `.badge`, 500 11/20 in white. */
private val BADGE_STYLE = FermixType.labelSmall.copy(lineHeight = 20.sp, letterSpacing = 0.sp)

/** One (instance, profile)'s row: tap to open it, long-press for its menu (section 13.4) with the haptic. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatRowItem(
    row: ChatRow,
    menu: RowMenu,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val colors = LocalFermixColors.current
    var open by rememberSaveable { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    Box(modifier = modifier.onSizeChanged { height = it.height }) {
        RowLayout(
            avatar = { InstanceAvatar(tint = row.record.tint, dot = row.link.dot, size = AvatarSize.ROW) },
            modifier =
                Modifier.rowFocusRing().held(open, colors.agentBubble).combinedClickable(
                    hapticFeedbackEnabled = false,
                    onClick = { menu.actions.onOpen(row) },
                    onLongClick = {
                        HapticFeedback.perform(view, HapticUse.LongPress)
                        open = true
                    },
                ),
            meta = { Meta(row.time, row.unread) },
        ) {
            Title(row)
            SecondLine(row)
        }
        LongPressMenu(open = open, rowHeight = height, onDismiss = { open = false }) {
            val id = row.record.id
            val close = { open = false }
            MenuEntry(R.string.chats_move_to_top, R.drawable.ic_chats_menu_top) {
                close()
                menu.actions.onMoveToTop(id)
            }
            MenuEntry(R.string.chats_rename, R.drawable.ic_chats_menu_rename) {
                close()
                menu.onRename(id)
            }
            MenuEntry(R.string.chats_details, R.drawable.ic_chats_menu_details) {
                close()
                menu.actions.onDetails(id)
            }
            MenuEntry(R.string.chats_unpair, R.drawable.ic_chats_menu_unpair) {
                close()
                menu.onUnpair(id)
            }
        }
    }
}

/**
 * A Fermix a restore left without its key (design section 6.6): its title and "Re-pair this Fermix"; a tap
 * pairs it again, and its long-press removes the row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RepairRowItem(
    title: String,
    onRepair: () -> Unit,
    onRemove: () -> Unit,
) {
    val view = LocalView.current
    val colors = LocalFermixColors.current
    var open by rememberSaveable { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    Box(modifier = Modifier.onSizeChanged { height = it.height }) {
        RowLayout(
            avatar = { InstanceAvatar(tint = REPAIR_TINT, dot = null, size = AvatarSize.ROW) },
            modifier =
                Modifier.rowFocusRing().held(open, colors.agentBubble).combinedClickable(
                    hapticFeedbackEnabled = false,
                    onClick = onRepair,
                    onLongClick = {
                        HapticFeedback.perform(view, HapticUse.LongPress)
                        open = true
                    },
                ),
            meta = {},
        ) {
            Text(
                text = title,
                style = FermixType.title,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Line(stringResource(R.string.chats_repair), colors.ink)
        }
        LongPressMenu(open = open, rowHeight = height, onDismiss = { open = false }) {
            MenuEntry(R.string.chats_remove, R.drawable.ic_chats_menu_remove) {
                open = false
                onRemove()
            }
        }
    }
}

/**
 * A row's long-press menu over the row, [rowHeight] pixels tall, as the canon's `.menu`: the tonal surface's
 * solid on a hairline. Material opens a menu below its anchor, the row, so it is lifted by the row's height
 * less [MENU_TOP], whatever height a large font gave the row.
 */
@Composable
private fun LongPressMenu(
    open: Boolean,
    rowHeight: Int,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFermixColors.current
    val lift = with(LocalDensity.current) { rowHeight.toDp() } - MENU_TOP
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        offset = DpOffset(MENU_START, -lift),
        shape = MENU_SHAPE,
        containerColor = colors.tonalSolid,
        border = BorderStroke(FermixSpacing.hairline, colors.hairline),
        content = content,
    )
}

/** A row on [tone] while its menu is [open], as the canon draws the row long-pressed. */
private fun Modifier.held(
    open: Boolean,
    tone: Color,
): Modifier = if (open) background(tone) else this

/** A row dropped by a restore has lost its tint with its record: it is drawn in the first one. */
private const val REPAIR_TINT = "Slate"

@Composable
internal fun RowLayout(
    avatar: @Composable () -> Unit,
    modifier: Modifier,
    meta: @Composable () -> Unit,
    main: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .padding(horizontal = ROW_SIDES, vertical = ROW_ENDS),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Column(modifier = Modifier.weight(1f), content = main)
        meta()
    }
}

@Composable
internal fun Title(row: ChatRow) {
    val colors = LocalFermixColors.current
    val agent = row.agent
    val title =
        if (agent ==
            null
        ) {
            row.record.title
        } else {
            stringResource(R.string.chats_title_with_agent, row.record.title, agent)
        }
    Row(horizontalArrangement = Arrangement.spacedBy(TITLE_GAP), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = FermixType.title,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.dev) Tag(stringResource(R.string.chats_dev_tag), TAG_STYLE, TAG_ENDS)
    }
}

/** The second line in its colour: the error text's for a revoked phone, the ink for "thinking…". */
@Composable
private fun SecondLine(row: ChatRow) {
    val colors = LocalFermixColors.current
    when (val line = row.line) {
        is RowLine.Speaks -> {
            val words = linkWords(line.link, row.record.host) ?: return
            Line(words, if (line.link == Link.Revoked) colors.errText else colors.textSecondary)
        }

        RowLine.Thinking -> {
            Line(stringResource(R.string.chats_thinking), colors.ink)
        }

        is RowLine.Draft -> {
            Line(stringResource(R.string.chats_draft, line.text), colors.textSecondary)
        }

        is RowLine.Message -> {
            Line(line.text, colors.textSecondary)
        }

        // A chat with nothing in it yet has no second line.
        RowLine.Empty -> {}
    }
}

@Composable
private fun Line(
    text: String,
    color: Color,
) {
    Text(text = text, style = FermixType.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The time of the last message and the unread badge, at the row's end. */
@Composable
private fun Meta(
    time: String?,
    unread: Int,
) {
    val colors = LocalFermixColors.current
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(META_GAP)) {
        if (time != null) Text(text = time, style = FermixType.labelSmall, color = colors.textSecondary)
        if (unread > 0) {
            Text(
                text = unread.toString(),
                style = BADGE_STYLE,
                // Fermix blue marks what is unread, and nothing else (README, the owner's decision of 2026-10-05).
                color = colors.onSignal,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .background(colors.signal, CircleShape)
                        .wideAsTall()
                        .widthIn(min = BADGE)
                        .heightIn(min = BADGE)
                        .padding(horizontal = BADGE_SIDES),
            )
        }
    }
}

/**
 * At least as wide as it is tall, the content centred: a count whose line the font scale made taller
 * than its digits are wide stays the canon's pill, never one stood on its end.
 */
private fun Modifier.wideAsTall(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val width = maxOf(placeable.width, placeable.height).coerceAtMost(constraints.maxWidth)
        layout(width, placeable.height) { placeable.place((width - placeable.width) / 2, 0) }
    }

@Composable
private fun MenuEntry(
    label: Int,
    icon: Int,
    onClick: () -> Unit,
) {
    val colors = LocalFermixColors.current
    DropdownMenuItem(
        text = { Text(text = stringResource(label), style = FermixType.body, color = colors.ink) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = colors.ink) },
        modifier = Modifier.rowFocusRing(),
        onClick = onClick,
    )
}
