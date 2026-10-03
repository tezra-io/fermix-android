package io.tezra.fermix.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors

/** A menu's narrowest (the canon's `.menu`). */
private val MENU_WIDTH = 196.dp

private fun iconOf(entry: MenuEntry): Int =
    when (entry) {
        MenuEntry.COPY -> R.drawable.ic_chat_copy
        MenuEntry.SELECT_TEXT -> R.drawable.ic_chat_select
        MenuEntry.COPY_CODE -> R.drawable.ic_chat_term
        MenuEntry.SHARE -> R.drawable.ic_chat_share
        MenuEntry.INFO -> R.drawable.ic_chat_info
        MenuEntry.RETRY -> R.drawable.ic_chat_retry
    }

private fun wordsOf(entry: MenuEntry): Int =
    when (entry) {
        MenuEntry.COPY -> R.string.chat_copy
        MenuEntry.SELECT_TEXT -> R.string.chat_select_text
        MenuEntry.COPY_CODE -> R.string.chat_copy_code
        MenuEntry.SHARE -> R.string.chat_share
        MenuEntry.INFO -> R.string.chat_info
        MenuEntry.RETRY -> R.string.chat_retry
    }

/**
 * A long-pressed message (design section 13.7, the canon's "Long-press"): the timeline dims, the message lifts
 * above it with its menu under it, menuOf's entries in order. A tap on the lifted message selects it and starts
 * multi-select; a tap on the dim, or back, puts it down.
 */
@Composable
internal fun LiftedMessage(
    item: ChatItem.Message,
    context: TimelineContext,
    onPick: (MenuEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val lifted = context.copy(selected = emptySet(), onLongPress = {})
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            MessageItem(item, lifted)
            val entries = menuOf(item.message)
            Column(
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .widthIn(min = MENU_WIDTH)
                        .background(colors.tonalSolid, FermixShapes.card)
                        .border(FermixSpacing.hairline, colors.hairline, FermixShapes.card)
                        .padding(vertical = 6.dp),
            ) {
                entries.forEach { entry -> MenuRow(iconOf(entry), stringResource(wordsOf(entry))) { onPick(entry) } }
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: Int,
    words: String,
    onClick: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier =
            Modifier
                .widthIn(min = MENU_WIDTH)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = colors.ink, modifier = Modifier.size(24.dp))
        Text(words, style = FermixType.body, color = colors.ink)
    }
}

/**
 * A tap's menu on the owner's item in the outbox (design section 13.6), under its bubble: Edit and Remove while
 * its frame was never written, "Try again" and "Remove from outbox" once refused.
 */
@Composable
internal fun OutboxMenu(
    entries: List<OutboxEntry>,
    onPick: (OutboxEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = entries.isNotEmpty(), onDismissRequest = onDismiss) {
        entries.forEach { entry ->
            val (icon, words) =
                when (entry) {
                    OutboxEntry.EDIT -> R.drawable.ic_chat_pencil to R.string.chat_edit
                    OutboxEntry.REMOVE -> R.drawable.ic_chat_trash to R.string.chat_remove
                    OutboxEntry.TRY_AGAIN -> R.drawable.ic_chat_retry to R.string.chat_try_again
                    OutboxEntry.REMOVE_FROM_OUTBOX -> R.drawable.ic_chat_trash to R.string.chat_remove_from_outbox
                }
            DropdownMenuItem(
                text = { Text(stringResource(words), style = FermixType.body) },
                leadingIcon = { Icon(painterResource(icon), null) },
                onClick = { onPick(entry) },
            )
        }
    }
}
