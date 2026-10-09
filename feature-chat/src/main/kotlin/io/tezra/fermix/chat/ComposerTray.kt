package io.tezra.fermix.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.focusRing

/** The tray's items (design section 13.6): 56 dp, 12 dp corners. */
private val TRAY_ITEM = 56.dp
private val TRAY_SHAPE = RoundedCornerShape(12.dp)

/**
 * The tray (design section 13.6, the canon's `.tray`): the picked items as 56 dp thumbnails with 12 dp corners,
 * each with its ✕; an item that draws no thumbnail shows its extension, a voice note its mic.
 */
@Composable
internal fun Tray(
    picked: List<Picked>,
    thumbnail: suspend (Picked) -> ImageBitmap?,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        picked.forEach { item -> TrayItem(item, thumbnail, onRemove) }
    }
}

@Composable
private fun TrayItem(
    item: Picked,
    thumbnail: suspend (Picked) -> ImageBitmap?,
    onRemove: (String) -> Unit,
) {
    val colors = LocalFermixColors.current
    val image by produceState<ImageBitmap?>(null, item.uri) { value = thumbnail(item) }
    Box(modifier = Modifier.size(TRAY_ITEM + 6.dp)) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .size(TRAY_ITEM)
                    .clip(TRAY_SHAPE)
                    .background(colors.hairline),
            contentAlignment = Alignment.Center,
        ) {
            val shown = image
            when {
                shown != null -> {
                    Image(shown, item.name, contentScale = ContentScale.Crop, modifier = Modifier.size(TRAY_ITEM))
                }

                item.kind == PickedKind.VOICE -> {
                    Icon(painterResource(R.drawable.ic_chat_mic), item.name, tint = colors.ink)
                }

                else -> {
                    ExtensionLabel(item.name)
                }
            }
        }
        RemoveBadge(stringResource(R.string.chat_remove_item, item.name), Modifier.align(Alignment.TopEnd)) {
            onRemove(item.id)
        }
    }
}

/**
 * A tray item's ✕: the canon's 18 dp badge at the item's corner, inside a 48 dp target (design sections 13.1 and
 * 13.8) that reaches into the thumbnail, which takes no touch of its own.
 */
@Composable
private fun RemoveBadge(
    label: String,
    modifier: Modifier,
    onRemove: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Box(
        modifier =
            modifier
                .size(TOUCH_TARGET)
                .focusRing(CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onRemove)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(modifier = Modifier.size(18.dp).background(colors.ink, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_chat_x), null, tint = colors.onInk, modifier = Modifier.size(12.dp))
        }
    }
}
