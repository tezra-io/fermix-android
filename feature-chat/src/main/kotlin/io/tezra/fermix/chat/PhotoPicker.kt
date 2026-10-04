package io.tezra.fermix.chat

import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo
import androidx.annotation.RequiresExtension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.photopicker.compose.EmbeddedPhotoPicker
import androidx.photopicker.compose.ExperimentalPhotoPickerComposeApi
import androidx.photopicker.compose.rememberEmbeddedPhotoPickerState
import io.tezra.fermix.session.MAX_ATTACHMENTS

/** The SDK extension the embedded Photo Picker needs (D18: present on every Android 15 phone). */
private const val EMBEDDED_PICKER_EXTENSION = 15

private const val PICKER_TAG = "FermixPicker"

/**
 * The attach sheet's grid (design sections 8.5 and 13.6, D18): the embedded Photo Picker, its numbered multi-select
 * drawn by the picker itself, at most what the tray's other items leave of the ten, the items already picked from
 * it selected again as the sheet reopens;
 * each item picked or let go goes to [actions] by its URI. Where the phone cannot draw it, the Photos tile opens the
 * system Photo Picker. A session the picker ends with an error is logged; the chips still work.
 */
@OptIn(ExperimentalPhotoPickerComposeApi::class)
@Composable
internal fun PhotoGrid(
    attach: AttachUi,
    actions: AttachActions,
    modifier: Modifier,
) {
    if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= EMBEDDED_PICKER_EXTENSION) {
        val chosen =
            attach.picked
                .filter { it.from == PickedFrom.PHOTOS }
                .map { it.uri.toUri() }
                .toSet()
        val state =
            rememberEmbeddedPhotoPickerState(
                initialMediaSelection = chosen,
                onSessionError = { Log.w(PICKER_TAG, "the embedded Photo Picker ended its session", it) },
                onUriPermissionGranted = { uris -> actions.onPicked(uris.map(Uri::toString)) },
                onUriPermissionRevoked = { uris -> actions.onUnpicked(uris.map(Uri::toString)) },
            )
        // The tray's items from the other sources leave the picker the rest of the ten, one at least.
        val room = (MAX_ATTACHMENTS - attach.picked.count { it.from != PickedFrom.PHOTOS }).coerceAtLeast(1)
        val features = remember(room) { pickerFeatures(room) }
        EmbeddedPhotoPicker(state = state, modifier = modifier, embeddedPhotoPickerFeatureInfo = features)
    } else {
        PhotosTile(attach, actions, modifier)
    }
}

/** At most [room] of the ten, numbered in the order picked (design section 13.6, "numbered multi-select"). */
@RequiresExtension(extension = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, version = EMBEDDED_PICKER_EXTENSION)
private fun pickerFeatures(room: Int): EmbeddedPhotoPickerFeatureInfo =
    EmbeddedPhotoPickerFeatureInfo
        .Builder()
        .setMaxSelectionLimit(room)
        .setOrderedSelection(true)
        .build()
