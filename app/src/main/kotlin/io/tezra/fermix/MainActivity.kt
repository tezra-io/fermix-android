package io.tezra.fermix

import android.graphics.Color
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.onboarding.OnboardingViewModel
import io.tezra.fermix.onboarding.appBackStack
import io.tezra.fermix.onboarding.darkUnderBars
import io.tezra.fermix.onboarding.onboardingEntries
import io.tezra.fermix.onboarding.securesWindow
import kotlinx.coroutines.flow.map

/** The Chats list (design section 13.4), the root once a Fermix is paired; a placeholder until it is built. */
data object ChatsKey : NavKey

/**
 * The one activity: the design's theme, the back stack in Navigation 3's NavDisplay with its predictive
 * back, `FLAG_SECURE` while an onboarding screen shows (design section 13.3), white system bars over the
 * scan's camera, and the pairing-wait notification when the owner leaves Verify for another app (section
 * 12.5).
 */
class MainActivity : ComponentActivity() {
    private val services: AppServices get() = (application as FermixApplication).services

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FermixTheme { FermixApp(services, ::fit) }
        }
    }

    override fun onStart() {
        super.onStart()
        services.inBackground.value = false
    }

    /**
     * Out of sight: a pairing that waits on the computer gets its notification, started here, as the app
     * leaves, while it may still start a foreground service. A rotation is not leaving.
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        services.inBackground.value = true
        val shown = pairingWaitShown(services.pairingWait.value, services.inBackground.value)
        if (shown != null) PairingWaitService.start(this)
    }

    private fun fit(top: NavKey) = fitWindow(this, top)
}

/** [activity]'s window as [top] needs it: kept out of screenshots or not, and its system bars. */
internal fun fitWindow(
    activity: ComponentActivity,
    top: NavKey,
) {
    secureWindow(activity.window, top)
    barsOver(activity, top)
}

/**
 * Keeps [window] out of screenshots and the recents while [top] is an onboarding screen (design sections
 * 12.4 and 13.3), and lets it be seen again once the screen is the app's own.
 */
internal fun secureWindow(
    window: Window,
    top: NavKey,
) {
    if (securesWindow(top)) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/**
 * [activity]'s system bars over [top]: transparent with white icons and no contrast scrim over a screen dark
 * in both modes (the visual canon's `.phone.bleed`), and otherwise the activity's own, which follow the
 * theme; the edge-to-edge setup is the activity's, so asking for it again restores them.
 */
private fun barsOver(
    activity: ComponentActivity,
    top: NavKey,
) {
    if (darkUnderBars(top)) {
        val white = SystemBarStyle.dark(Color.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = white, navigationBarStyle = white)
    } else {
        activity.enableEdgeToEdge()
    }
}

/**
 * The back stack: the root, Welcome or the Chats list once a record exists, under onboarding's screens.
 * Nothing shows until the records are read, so the root never flickers from Welcome to Chats.
 */
@Composable
private fun FermixApp(
    services: AppServices,
    fit: (NavKey) -> Unit,
) {
    val model = viewModel { OnboardingViewModel(services.onboardingParts()) }
    val pairedFlow = remember(services) { services.instances.instances.map { it.isNotEmpty() } }
    val paired by pairedFlow.collectAsState(initial = null)
    val onboarding by model.stack.collectAsState()
    val canvas = LocalFermixColors.current.canvas
    val known = paired
    Box(modifier = Modifier.fillMaxSize().background(canvas)) {
        if (known != null) Screens(appBackStack(known, ChatsKey, onboarding), model, fit)
    }
}

@Composable
private fun Screens(
    stack: List<NavKey>,
    model: OnboardingViewModel,
    fit: (NavKey) -> Unit,
) {
    val top = stack.last()
    LaunchedEffect(top) { fit(top) }
    NavDisplay(
        backStack = stack,
        onBack = model::back,
        entryProvider =
            entryProvider {
                onboardingEntries(this, model)
                entry<ChatsKey> { ChatsPlaceholder() }
            },
    )
}

/** Until the Chats list is built, the root says the app's name. */
@Composable
private fun ChatsPlaceholder() {
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.app_name),
            style = FermixType.display,
            color = LocalFermixColors.current.ink,
        )
    }
}
