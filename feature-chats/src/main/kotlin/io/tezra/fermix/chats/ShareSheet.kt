package io.tezra.fermix.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.instance.AvatarSize
import io.tezra.fermix.instance.InstanceAvatar
import io.tezra.fermix.instance.dot

// The visual canon's share sheet: its `h5` 24 dp in from the sides and 4 dp under the grab bar, its rows 8 dp under
// it, and the sheet's 36 dp at the foot.
private val SHEET_SIDES = 24.dp
private val TITLE_TOP = 4.dp
private val ROWS_TOP = 8.dp
private val SHEET_FOOT = 36.dp

/**
 * "Send to which Fermix?" (design sections 13.6 and 13.9, the visual canon's share sheet), over the app: a generic
 * share asks which paired Fermix it goes to. Each of [rows] is drawn as the Chats list draws it, its avatar with the
 * link's dot and its title with the DEV tag, and a tap hands the share to that chat ([onPick]), which lands it in its
 * tray and sends nothing. Put down ([onDismiss]), the share is let go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    rows: List<ChatRow>,
    onPick: (ChatRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFermixColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.tonalSolid, scrimColor = colors.scrim) {
        ShareSheetContent(rows, onPick)
    }
}

/** What "Send to which Fermix?" holds: the question, then a row per paired chat, scrolling as the window needs. */
@Composable
fun ShareSheetContent(
    rows: List<ChatRow>,
    onPick: (ChatRow) -> Unit,
) {
    val colors = LocalFermixColors.current
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = SHEET_FOOT)) {
        Text(
            text = stringResource(R.string.chats_share_title),
            style = FermixType.title,
            color = colors.ink,
            modifier =
                Modifier
                    .padding(start = SHEET_SIDES, top = TITLE_TOP, end = SHEET_SIDES, bottom = ROWS_TOP)
                    .semantics { heading() },
        )
        rows.forEach { row ->
            RowLayout(
                avatar = { InstanceAvatar(tint = row.record.tint, dot = row.link.dot, size = AvatarSize.ROW) },
                modifier = Modifier.clickable { onPick(row) },
                meta = {},
            ) { Title(row) }
        }
    }
}
