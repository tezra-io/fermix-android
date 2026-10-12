package io.tezra.fermix.instance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The Instance screen over [viewModel]'s state, nothing while the record and the chat's settings are read
 * or once the record is gone. [onBack] leaves the screen; an action reaches the ViewModel only while
 * [showing] says the screen is on top, as a screen leaving is still drawn, and hit. "Choose a photo" opens the
 * system's Photo Picker for one image, whose URI the ViewModel reads; one put down picks nothing. The ViewModel
 * outlives a visit, so each visit, and not a rotation or a fold within one, begins anew ([InstanceViewModel.entered]).
 */
@Composable
fun InstanceRoute(
    viewModel: InstanceViewModel,
    onBack: () -> Unit,
    showing: () -> Boolean,
) {
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        if (!entered) {
            entered = true
            viewModel.entered()
        }
    }
    val ui by viewModel.ui.collectAsState()
    // The system's Photo Picker, images only: the app holds no storage permission, and reads the one image picked.
    val picker =
        rememberLauncherForActivityResult(PickVisualMedia()) { picked ->
            picked?.let { viewModel.setPicture(it.toString()) }
        }
    val actions =
        remember(viewModel, onBack, showing, picker) {
            fun guarded(action: () -> Unit): () -> Unit = { if (showing()) action() }
            InstanceActions(
                onBack = guarded(onBack),
                onRename = { if (showing()) viewModel.rename(it) },
                onTest = guarded(viewModel::testConnection),
                onNotifications = { if (showing()) viewModel.setNotifications(it) },
                onPreviews = { if (showing()) viewModel.setPreviews(it) },
                onClearCache = guarded(viewModel::clearCache),
                onUnpair = guarded(viewModel::unpair),
                onTint = { if (showing()) viewModel.setTint(it) },
                onEyes = { if (showing()) viewModel.setEyes(it) },
                onChoosePhoto = guarded { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                onRemovePhoto = guarded(viewModel::removePicture),
            )
        }
    ui?.let { InstanceScreen(it, actions) }
}
