package io.tezra.fermix.onboarding

import android.Manifest
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse

/**
 * The scan (design section 13.3, step 3): the camera once this app may use it, the rationale before the
 * system's prompt and "Camera is off for Fermix" after a no, the torch once the camera reports one, and
 * every code the camera reads handed to the ceremony driver, as the paste sheet hands a pasted one.
 */
@Composable
internal fun ScanEntry(
    viewModel: OnboardingViewModel,
    camera: ScanCamera,
    clip: PrimaryClip,
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val key = OnboardingKey.Scan
    val gate = rememberCameraGate(camera)
    var torch by remember { mutableStateOf<CameraTorch?>(null) }
    val actions =
        ScanActions(
            onBack = viewModel.whileShowing(key, viewModel::back),
            onTorchChange = { on -> checkNotNull(torch) { "no torch is drawn without a camera's" }.switch(on) },
            onPaste = viewModel.whileShowing(key, viewModel.paste::open),
            onAllowCamera = viewModel.whileShowing(key, gate.ask),
            onOpenSettings = { openAppSettings(context) },
        )
    ScanScreen(
        state = ScanUi(refused = ui.scanRefusal != null, torchOn = torch?.lit, access = gate.access),
        actions = actions,
        preview = { camera.preview({ text -> viewModel.scanned(text, view) }, { torch = it }) },
    )
    PasteSheet(viewModel, key, clip)
    AlreadyPairedQuestion(viewModel, key)
}

/** What the scan may show of the camera, and the system's prompt that asks for it. */
private class CameraGate(
    val access: CameraAccess,
    val ask: () -> Unit,
)

/**
 * The camera's permission as the scan follows it: read from [camera] as the screen shows and each time it
 * comes back to the front, as from the app's settings page, and the prompt's answer, which [cameraAccess]
 * turns into the frame's state.
 */
@Composable
private fun rememberCameraGate(camera: ScanCamera): CameraGate {
    val context = LocalContext.current
    var granted by remember(camera) { mutableStateOf(camera.allowed(context)) }
    var refusedPrompt by rememberSaveable { mutableStateOf(false) }
    val prompt =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { yes ->
            granted = yes
            refusedPrompt = !yes
        }
    LifecycleResumeEffect(camera) {
        granted = camera.allowed(context)
        onPauseOrDispose {}
    }
    return CameraGate(cameraAccess(granted, refusedPrompt)) { prompt.launch(Manifest.permission.CAMERA) }
}

/**
 * A code the camera read, while the scan is on top: `CONFIRM` for a link the ceremony takes (design section
 * 13.3, step 3), and whatever it is to the ceremony driver, as a pasted one goes.
 */
private fun OnboardingViewModel.scanned(
    text: String,
    view: View,
) {
    if (topOf(stack.value) != OnboardingKey.Scan) return
    val outcome = readLink(text)
    if (outcome is LinkOutcome.Link) HapticFeedback.perform(view, HapticUse.QrDecoded)
    ceremony.onLink(outcome)
}
