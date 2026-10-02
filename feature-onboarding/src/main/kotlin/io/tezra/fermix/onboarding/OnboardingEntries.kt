package io.tezra.fermix.onboarding

import android.Manifest
import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeMark

/**
 * Onboarding's screens as entries of the app's back stack (design section 12.1, Navigation 3): each
 * [OnboardingKey] draws its screen from [viewModel]'s state, and hands the screen's actions to it, or
 * outside the app (a page, the Tailscale app, the VPN settings, the system's notification prompt), or to
 * the clipboard. An action reaches [viewModel] only from the screen on top ([topOf]): one popped off the
 * stack is still drawn, and hit, as it leaves, and a second quick tap lands there. The app keeps
 * [viewModel] for its activity, so a rotation or a fold keeps the pairing.
 */
fun onboardingEntries(
    builder: EntryProviderScope<NavKey>,
    viewModel: OnboardingViewModel,
) {
    builder.entry<OnboardingKey.Welcome> { WelcomeEntry(viewModel) }
    builder.entry<OnboardingKey.Pair> { PairEntry(viewModel) }
    builder.entry<OnboardingKey.Scan> { ScanEntry(viewModel) }
    builder.entry<OnboardingKey.Connecting> {
        val ui by viewModel.ui.collectAsState()
        ConnectingScreen(phase = ui.connecting, host = ui.host)
    }
    builder.entry<OnboardingKey.Verify> { VerifyEntry(viewModel) }
    builder.entry<OnboardingKey.Paired> { key ->
        val ui by viewModel.ui.collectAsState()
        PairedScreen(host = ui.host, onContinue = viewModel.whileShowing(key, viewModel::continueFromPaired))
    }
    builder.entry<OnboardingKey.Name> { key ->
        val ui by viewModel.ui.collectAsState()
        NameScreen(
            paired = checkNotNull(ui.paired) { "Name names a stored pairing" },
            onContinue = { nickname -> if (topOf(viewModel.stack.value) == key) viewModel.name(nickname) },
        )
    }
    builder.entry<OnboardingKey.Notifications> { NotificationsEntry(viewModel) }
    builder.entry<OnboardingKey.Failure> { key -> FailureEntry(key.case, viewModel) }
}

@Composable
private fun WelcomeEntry(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    WelcomeScreen(
        onGetStarted = viewModel.whileShowing(OnboardingKey.Welcome, viewModel::getStarted),
        onNoFermix = { openPage(context, R.string.onboarding_url_install) },
    )
}

@Composable
private fun PairEntry(viewModel: OnboardingViewModel) {
    val ui by viewModel.ui.collectAsState()
    val clipboard = LocalClipboard.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val command = stringResource(R.string.onboarding_pair_command)
    val key = OnboardingKey.Pair
    val paste = rememberPaste(viewModel, key)
    val actions =
        PairActions(
            onBack = viewModel.whileShowing(key, viewModel::back),
            onCopy = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(command, command))) }
                HapticFeedback.perform(view, HapticUse.Copy)
            },
            onRename = { name -> if (topOf(viewModel.stack.value) == key) viewModel.rename(name) },
            onScan = viewModel.whileShowing(key, viewModel::scan),
            onPaste = paste,
        )
    PairScreen(deviceName = ui.deviceName, actions = actions)
}

/**
 * The scan's frame. The CameraX preview and its torch come with the camera (a later change), so the torch
 * is not offered yet, and no toggle is drawn that could call [ScanActions.onTorchChange].
 */
@Composable
private fun ScanEntry(viewModel: OnboardingViewModel) {
    val ui by viewModel.ui.collectAsState()
    val paste = rememberPaste(viewModel, OnboardingKey.Scan)
    ScanScreen(
        refused = ui.scanRefusal != null,
        torchOn = null,
        actions =
            ScanActions(
                onBack = viewModel.whileShowing(OnboardingKey.Scan, viewModel::back),
                onTorchChange = { on -> error("no torch is drawn before the camera brings one, so none turns $on") },
                onPaste = paste,
            ),
    )
}

@Composable
private fun VerifyEntry(viewModel: OnboardingViewModel) {
    val ui by viewModel.ui.collectAsState()
    val verify = checkNotNull(ui.verify) { "Verify shows a ceremony's code" }
    val left by rememberSecondsLeft(verify.expiresAt)
    val cancel = viewModel.whileShowing(OnboardingKey.Verify, viewModel::back)
    VerifyScreen(state = VerifyUi(verify.sas, left, verify.deviceName), onCancel = cancel)
}

@Composable
private fun NotificationsEntry(viewModel: OnboardingViewModel) {
    val key = OnboardingKey.Notifications
    val prompt =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (topOf(viewModel.stack.value) == key) viewModel.notificationsAnswered(granted)
        }
    NotificationsScreen(
        onAllow = viewModel.whileShowing(key) { prompt.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onNotNow = viewModel.whileShowing(key) { viewModel.notificationsAnswered(false) },
    )
}

@Composable
private fun FailureEntry(
    case: FailureCase,
    viewModel: OnboardingViewModel,
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val key = OnboardingKey.Failure(case)
    val paste = rememberPaste(viewModel, key)
    FailureScreen(
        case = case,
        host = ui.host,
        onAction = { action ->
            when {
                action == FailureAction.PASTE_LINK -> paste()
                stepAfter(case, action) == FailureStep.Outside -> openOutside(context, action)
                topOf(viewModel.stack.value) == key -> viewModel.ceremony.act(case, action)
            }
        },
    )
}

/** [action] while [key]'s screen is on top, and nothing once it is leaving. */
private fun OnboardingViewModel.whileShowing(
    key: OnboardingKey,
    action: () -> Unit,
): () -> Unit = { if (topOf(stack.value) == key) action() }

/**
 * "Paste a pairing link" on [key]'s screen: the clipboard's text, as a link the ceremony takes or the scan
 * refuses, if the screen is still on top once the clipboard has answered. The clip is cleared once read, as
 * the link carries the pairing's secret (design section 12.4); that is all an app can do about a clip
 * (section 6.5).
 */
@Composable
private fun rememberPaste(
    viewModel: OnboardingViewModel,
    key: OnboardingKey,
): () -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(viewModel, key, clipboard) {
        {
            scope.launch {
                val clip = clipboard.getClipEntry()?.clipData
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                if (clip != null) clipboard.setClipEntry(null)
                if (topOf(viewModel.stack.value) == key) viewModel.ceremony.onLink(text?.toString().orEmpty())
            }
        }
    }
}

/**
 * The seconds left until [expiresAt], ticking on the second: the end lives in the ViewModel, so a screen
 * drawn again after a rotation counts on from where it was. The ticks end with the window.
 */
@Composable
private fun rememberSecondsLeft(expiresAt: TimeMark): State<Int> {
    val left = remember(expiresAt) { mutableIntStateOf(secondsUntil(expiresAt)) }
    LaunchedEffect(expiresAt) {
        repeat(PAIRING_COUNTDOWN_SECONDS + 1) {
            left.intValue = secondsUntil(expiresAt)
            if (left.intValue == 0) return@LaunchedEffect
            delay(millisToNextSecond(expiresAt))
        }
    }
    return left
}
