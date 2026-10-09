package io.tezra.fermix.onboarding

import android.Manifest
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
 * every code the camera reads handed to the ceremony driver, as the paste sheet hands a pasted one. A Fermix
 * code is found (OnboardingViewModel.found): the reticle locks onto it, and the ceremony takes it, and
 * Connecting follows, 250 ms later (the M51 update's 7.4), the camera's reads meanwhile left unread. The
 * reader, zxing-cpp, gives the code's corners in the analysed frame, cropped and turned, which this screen
 * cannot place on the preview without the camera's own transform, so the reticle locks to the update's 66 %
 * for bounds not known.
 */
@Composable
internal fun ScanEntry(
    viewModel: OnboardingViewModel,
    camera: ScanCamera,
    clip: PrimaryClip,
) {
    val ui by viewModel.ui.collectAsState()
    val stack by viewModel.stack.collectAsState()
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
    val state = ScanUi(ui.scanRefusal != null, torch?.lit, gate.access, found = ui.scanFound, visit = ui.scanVisit)
    FollowBackSwipe(viewModel.backSwipe)
    ScanScreen(
        state = heldOnceLeft(onTop = topOf(stack) == key, now = state),
        actions = actions,
        preview = { camera.preview({ text -> viewModel.scanned(text, view) }, { torch = it }) },
    )
    PasteSheet(viewModel, key, clip)
    AlreadyPairedQuestion(viewModel, key)
}

/**
 * The scan's state [now] while it is [onTop], and, once it has left the top, what it showed last: the ViewModel lets
 * the code go and starts the scan's next visit as it leaves, so that a back swipe into it shows it searching, its
 * reticle settling in, from the first frame the swipe draws, while the scan going out stands locked on its code.
 */
@Composable
private fun heldOnceLeft(
    onTop: Boolean,
    now: ScanUi,
): ScanUi {
    var last by remember { mutableStateOf(now) }
    val shown = if (onTop) now else last
    SideEffect { last = shown }
    return shown
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
 * A code the camera read, while the scan is on top and has found none: a link the ceremony takes, found, with
 * its `CONFIRM` (design section 13.3, step 3), for the ViewModel to hand on once the reticle has locked onto
 * it; anything else straight to the ceremony driver, as a pasted one goes.
 */
private fun OnboardingViewModel.scanned(
    text: String,
    view: View,
) {
    if (topOf(stack.value) != OnboardingKey.Scan || ui.value.scanFound) return
    val outcome = readLink(text)
    if (outcome !is LinkOutcome.Link) return ceremony.onLink(outcome)
    HapticFeedback.perform(view, HapticUse.QrDecoded)
    found(outcome)
}
