package io.tezra.fermix.instance

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
 * [showing] says the screen is on top, as a screen leaving is still drawn, and hit. The ViewModel outlives
 * a visit, so each visit, and not a rotation or a fold within one, begins anew ([InstanceViewModel.entered]).
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
    val actions =
        remember(viewModel, onBack, showing) {
            fun guarded(action: () -> Unit): () -> Unit = { if (showing()) action() }
            InstanceActions(
                onBack = guarded(onBack),
                onRename = { if (showing()) viewModel.rename(it) },
                onTest = guarded(viewModel::testConnection),
                onNotifications = { if (showing()) viewModel.setNotifications(it) },
                onPreviews = { if (showing()) viewModel.setPreviews(it) },
                onClearCache = guarded(viewModel::clearCache),
                onUnpair = guarded(viewModel::unpair),
            )
        }
    ui?.let { InstanceScreen(it, actions) }
}
