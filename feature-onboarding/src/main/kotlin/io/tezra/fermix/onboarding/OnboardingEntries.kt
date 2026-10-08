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
import kotlin.random.Random
import kotlin.time.TimeMark

/**
 * Onboarding's screens as entries of the app's back stack (design section 12.1, Navigation 3): each
 * [OnboardingKey] draws its screen from [viewModel]'s state, and hands the screen's actions to it, or
 * outside the app (a page, the app's settings, the Tailscale app, the VPN settings, the system's prompts).
 * The scan's [camera] and the paste sheet's [clip] are the phone's, or the instrumented tests' stand-ins; the Fermix
 * mark's blinks come when Kotlin's default random source draws.
 * An action reaches [viewModel] only from the screen on top ([topOf]): one popped off the stack is still
 * drawn, and hit, as it leaves, and a second quick tap lands there. The app keeps [viewModel] for its
 * activity, so a rotation or a fold keeps the pairing.
 */
fun onboardingEntries(
    builder: EntryProviderScope<NavKey>,
    viewModel: OnboardingViewModel,
    camera: ScanCamera,
    clip: PrimaryClip,
) {
    builder.entry<OnboardingKey.Welcome> { WelcomeEntry(viewModel) }
    builder.entry<OnboardingKey.Pair> { PairEntry(viewModel, clip) }
    builder.entry<OnboardingKey.Scan> { ScanEntry(viewModel, camera, clip) }
    builder.entry<OnboardingKey.Connecting> {
        val ui by viewModel.ui.collectAsState()
        ConnectingScreen(phase = ui.connecting, host = ui.host, random = Random.Default)
    }
    builder.entry<OnboardingKey.Verify> { VerifyEntry(viewModel) }
    builder.entry<OnboardingKey.Paired> { key ->
        val ui by viewModel.ui.collectAsState()
        PairedScreen(
            host = ui.host,
            onContinue = viewModel.whileShowing(key, viewModel::continueFromPaired),
            random = Random.Default,
        )
    }
    builder.entry<OnboardingKey.Name> { key ->
        val ui by viewModel.ui.collectAsState()
        NameScreen(
            paired = checkNotNull(ui.paired) { "Name names a stored pairing" },
            onContinue = { nickname -> if (topOf(viewModel.stack.value) == key) viewModel.name(nickname) },
        )
    }
    builder.entry<OnboardingKey.Notifications> { NotificationsEntry(viewModel) }
    builder.entry<OnboardingKey.Failure> { key -> FailureEntry(key.case, viewModel, clip) }
}

@Composable
private fun WelcomeEntry(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    WelcomeScreen(
        onGetStarted = viewModel.whileShowing(OnboardingKey.Welcome, viewModel::getStarted),
        onNoFermix = { openPage(context, R.string.onboarding_url_install) },
        random = Random.Default,
    )
}

@Composable
private fun PairEntry(
    viewModel: OnboardingViewModel,
    clip: PrimaryClip,
) {
    val ui by viewModel.ui.collectAsState()
    val clipboard = LocalClipboard.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val command = stringResource(R.string.onboarding_pair_command)
    val key = OnboardingKey.Pair
    val actions =
        PairActions(
            onBack = viewModel.whileShowing(key, viewModel::back),
            onCopy = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(command, command))) }
                HapticFeedback.perform(view, HapticUse.Copy)
            },
            onRename = { name -> if (topOf(viewModel.stack.value) == key) viewModel.rename(name) },
            onScan = viewModel.whileShowing(key, viewModel::scan),
            onPaste = viewModel.whileShowing(key, viewModel.paste::open),
        )
    PairScreen(deviceName = ui.deviceName, actions = actions)
    PasteSheet(viewModel, key, clip)
    AlreadyPairedQuestion(viewModel, key)
}

/** The paste sheet over [key]'s screen, while it is open and the screen is on top. */
@Composable
internal fun PasteSheet(
    viewModel: OnboardingViewModel,
    key: OnboardingKey,
    clip: PrimaryClip,
) {
    val field by viewModel.paste.field.collectAsState()
    val stack by viewModel.stack.collectAsState()
    val open = field
    if (open == null || topOf(stack) != key) return
    PasteLinkSheet(
        field = open,
        actions =
            PasteActions(
                onEdit = viewModel.paste::edit,
                onPaste = { viewModel.paste.pasteFrom(clip) },
                onContinue = { viewModel.paste.submit(clip) },
                onDismiss = viewModel.paste::close,
            ),
    )
}

@Composable
private fun VerifyEntry(viewModel: OnboardingViewModel) {
    val ui by viewModel.ui.collectAsState()
    val verify = checkNotNull(ui.verify) { "Verify shows a ceremony's code" }
    val left by rememberSecondsLeft(verify.expiresAt)
    val cancel = viewModel.whileShowing(OnboardingKey.Verify, viewModel::back)
    VerifyScreen(state = VerifyUi(verify.sas, left, verify.deviceName), onCancel = cancel, random = Random.Default)
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
    clip: PrimaryClip,
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val key = OnboardingKey.Failure(case)
    FailureScreen(
        case = case,
        host = ui.host,
        onAction = { action ->
            when {
                action == FailureAction.PASTE_LINK -> viewModel.whileShowing(key, viewModel.paste::open)()
                stepAfter(case, action) == FailureStep.Outside -> openOutside(context, action)
                topOf(viewModel.stack.value) == key -> viewModel.ceremony.act(case, action)
            }
        },
    )
    PasteSheet(viewModel, key, clip)
    AlreadyPairedQuestion(viewModel, key)
}

/** [action] while [key]'s screen is on top, and nothing once it is leaving. */
internal fun OnboardingViewModel.whileShowing(
    key: OnboardingKey,
    action: () -> Unit,
): () -> Unit = { if (topOf(stack.value) == key) action() }

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
