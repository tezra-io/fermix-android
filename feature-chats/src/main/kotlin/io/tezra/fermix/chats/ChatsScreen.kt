package io.tezra.fermix.chats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.instance.RenameDialog
import io.tezra.fermix.instance.StillTwoDotMark
import io.tezra.fermix.instance.UnpairDialog

// The visual canon's `.ab`: 64 dp, 4 dp at the sides, the title 22/28 medium 16 dp in; its menus (`.menu`)
// on the tonal surface's solid with 16 dp corners and a hairline, 48 dp entries in 16/24. The empty state
// (`.fail.mid`): the mark 32 dp above "Add Fermix", 16 dp and 24 dp of padding.
private val BAR_HEIGHT = 64.dp
private val BAR_SIDES = 4.dp
private val BAR_TITLE_START = 16.dp
internal val MENU_SHAPE = RoundedCornerShape(16.dp)
private val EMPTY_ENDS = 16.dp
private val EMPTY_SIDES = 24.dp
private val MARK_BELOW = 32.dp
private val ACTION_TOP = 40.dp

/** The canon's app bar title, 500 22/28. */
internal val BAR_TITLE = FermixType.headline.copy(fontSize = 22.sp, lineHeight = 28.sp)

/** What the Chats list's controls do; each is the app's or the ViewModel's. */
data class ChatsActions(
    val onAdd: () -> Unit,
    val onAppLock: () -> Unit,
    val onOpen: (ChatRow) -> Unit,
    val onMoveToTop: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onDetails: (String) -> Unit,
    val onUnpair: (String) -> Unit,
    val onRepair: (String) -> Unit,
    val onDismissRepair: (String) -> Unit,
)

/**
 * The Chats list, the root (design section 13.4): "Fermix" with "+" (Add Fermix) and the overflow's "App
 * lock", a row per (instance, profile) and one per Fermix to re-pair, or the empty state's "Add Fermix".
 * The rename and unpair dialogs a row's long-press opens survive a rotation or a fold.
 */
@Composable
fun ChatsScreen(
    ui: ChatsUi,
    actions: ChatsActions,
    modifier: Modifier = Modifier,
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var unpairing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(onAdd = actions.onAdd, onAppLock = actions.onAppLock)
        if (ui.rows.isEmpty() && ui.repairs.isEmpty()) {
            EmptyState(onAdd = actions.onAdd)
        } else {
            val menu = RowMenu(actions, onRename = { renaming = it }, onUnpair = { unpairing = it })
            Rows(ui, actions, menu)
        }
    }
    val records = ui.rows.map { it.record }
    records.find { it.id == renaming }?.let { record ->
        val done = { renaming = null }
        RenameDialog(record, records - record, onRename = {
            done()
            actions.onRename(record.id, it)
        }, onDismiss = done)
    }
    records.find { it.id == unpairing }?.let { record ->
        val done = { unpairing = null }
        UnpairDialog(record.host, onUnpair = {
            done()
            actions.onUnpair(record.id)
        }, onDismiss = done)
    }
}

/** The long-press menu's entries for a row; Rename and Unpair… open their dialogs over the list. */
internal class RowMenu(
    val actions: ChatsActions,
    val onRename: (String) -> Unit,
    val onUnpair: (String) -> Unit,
)

@Composable
private fun Rows(
    ui: ChatsUi,
    actions: ChatsActions,
    menu: RowMenu,
) {
    FermixColumn(ColumnWidth.Wide) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(ui.rows, key = { "${it.record.id}:${it.profileId}" }) { row -> ChatRowItem(row, menu) }
            items(ui.repairs, key = { "repair:${it.id}" }) { notice ->
                RepairRowItem(
                    notice.title,
                    onRepair = { actions.onRepair(notice.id) },
                    onRemove = { actions.onDismissRepair(notice.id) },
                )
            }
        }
    }
}

/** "Fermix", "+" and the overflow with its one entry, "App lock". */
@Composable
private fun TopBar(
    onAdd: () -> Unit,
    onAppLock: () -> Unit,
) {
    val colors = LocalFermixColors.current
    var overflow by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = BAR_SIDES),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chats_title),
            style = BAR_TITLE,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = BAR_TITLE_START).weight(1f),
        )
        IconButton(onClick = onAdd) {
            Icon(painterResource(R.drawable.ic_chats_plus), stringResource(R.string.chats_add), tint = colors.ink)
        }
        Box {
            IconButton(onClick = { overflow = true }) {
                Icon(painterResource(R.drawable.ic_chats_more), stringResource(R.string.chats_more), tint = colors.ink)
            }
            DropdownMenu(
                expanded = overflow,
                onDismissRequest = { overflow = false },
                shape = MENU_SHAPE,
                containerColor = colors.tonalSolid,
                border = BorderStroke(FermixSpacing.hairline, colors.hairline),
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.chats_app_lock),
                            style = FermixType.body,
                            color = colors.ink,
                        )
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_chats_lock), null, tint = colors.ink) },
                    onClick = {
                        overflow = false
                        onAppLock()
                    },
                )
            }
        }
    }
}

/** No Fermix yet (the canon's empty state): the mark and "Add Fermix", with no sentence, as the design gives none. */
@Composable
private fun EmptyState(onAdd: () -> Unit) {
    FermixColumn(ColumnWidth.Narrow) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = EMPTY_SIDES, vertical = EMPTY_ENDS),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StillTwoDotMark(modifier = Modifier.padding(bottom = MARK_BELOW))
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().padding(top = ACTION_TOP).heightIn(min = FermixSpacing.minTarget),
            ) {
                Text(text = stringResource(R.string.chats_add))
            }
        }
    }
}
