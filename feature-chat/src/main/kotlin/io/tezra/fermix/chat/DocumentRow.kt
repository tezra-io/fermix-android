package io.tezra.fermix.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors

/** A document's row (the canon's `.doc`): 64 dp, its extension's tile 40 dp with 10 dp corners. */
private val DOCUMENT_HEIGHT = 64.dp
private val EXTENSION_TILE = 40.dp
private val EXTENSION_SHAPE = RoundedCornerShape(10.dp)

/**
 * The extension's mono (the canon's `.doc .ext`, 500 11/16), sized in dp: the tile is an icon of a fixed 40 dp, so
 * its label does not follow the font scale, which would push four letters past its edges.
 */
private val EXTENSION_SIZE = 11.dp
private val EXTENSION_LINE = 16.dp
private val EXTENSION_TYPE = FermixType.mono.copy(fontWeight = FontWeight.Medium)
private val NAME_TYPE = FermixType.body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

/** The size · origin line under a document's name (the canon's 400 12/16). */
private val ORIGIN_TYPE = FermixType.body.copy(fontSize = 12.sp, lineHeight = 16.sp)

/**
 * A document's row (design section 13.5, the canon's `.doc`): its extension's tile, its name truncated in the
 * middle, and its size · origin, "this phone" for the owner's, with its upload's share under them while it goes up;
 * the agent's wears Save. A tap downloads it and opens it through the chooser; a long-press offers Share and Save.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentRow(
    media: ShownMedia,
    user: Boolean,
    context: TimelineContext,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    val actions = context.media.actions
    var menu by remember { mutableStateOf(false) }
    val name = media.name ?: stringResource(R.string.chat_file_tile)
    val origin = if (user) stringResource(R.string.chat_this_phone) else context.host
    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DOCUMENT_HEIGHT)
                    .clip(FermixShapes.card)
                    .background(colors.agentBubble)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.chat_open, name),
                        onClick = { actions.onOpen(media) },
                        onLongClick = { menu = true },
                    ).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtensionTile(name)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = NAME_TYPE, color = colors.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val line = stringResource(R.string.chat_size_origin, sizeText(media.sizeBytes, context.locale), origin)
                Text(line, style = ORIGIN_TYPE, color = colors.textSecondary, maxLines = 1)
                media.sent?.let { UploadBar(it, colors.hairline, colors.ink, Modifier.padding(top = 4.dp)) }
            }
            if (!user) SaveButton { actions.onSave(media) }
        }
        DocumentMenu(menu, { menu = false }, { actions.onShare(media) }, { actions.onSave(media) })
    }
}

/** A document's extension on its tile (the canon's `.doc .ext`): the canvas, a hairline, the mono. */
@Composable
private fun ExtensionTile(name: String) {
    val colors = LocalFermixColors.current
    Box(
        modifier =
            Modifier
                .size(EXTENSION_TILE)
                .clip(EXTENSION_SHAPE)
                .background(colors.canvas)
                .border(1.dp, colors.hairline, EXTENSION_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        ExtensionLabel(name)
    }
}

/**
 * [name]'s extension, "FILE" for one with none, in the tile's mono sized in dp, on one line: a document's tile and
 * a tray item with no thumbnail are icons of a fixed size, which a label that followed the font scale would outgrow.
 */
@Composable
internal fun ExtensionLabel(name: String) {
    val density = LocalDensity.current
    val type =
        with(density) { EXTENSION_TYPE.copy(fontSize = EXTENSION_SIZE.toSp(), lineHeight = EXTENSION_LINE.toSp()) }
    val extension = extensionOf(name) ?: stringResource(R.string.chat_file_tile)
    Text(extension, style = type, color = LocalFermixColors.current.textSecondary, maxLines = 1)
}

/** The agent's document's Save, the canon's save glyph at 20 dp in a 40 dp circle, its target 48 dp. */
@Composable
private fun SaveButton(onSave: () -> Unit) {
    val label = stringResource(R.string.chat_save)
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(role = Role.Button, onClickLabel = label, onClick = onSave),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_chat_save),
            label,
            tint = LocalFermixColors.current.ink,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A document's long-press menu: Share and Save. */
@Composable
private fun DocumentMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_share), style = FermixType.body) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_chat_share), null) },
            onClick = {
                onDismiss()
                onShare()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_save), style = FermixType.body) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_chat_save), null) },
            onClick = {
                onDismiss()
                onSave()
            },
        )
    }
}
