package io.tezra.fermix

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation3.runtime.NavKey
import io.tezra.fermix.chats.ChatsViewModel
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.onboarding.OnboardingViewModel
import io.tezra.fermix.onboarding.darkUnderBars
import io.tezra.fermix.onboarding.securesWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What an intent asks of the app: a chat from a notification or a shortcut, or "Add Fermix". */
sealed interface AppIntent {
    data class OpenChat(
        val chat: ChatKey,
    ) : AppIntent

    data object AddFermix : AppIntent
}

/** What [intent] asks, none for the launcher's plain start or anything the app does not take. */
fun appIntentOf(intent: Intent?): AppIntent? {
    if (intent?.action == ACTION_ADD_FERMIX) return AppIntent.AddFermix
    return chatOfLink(intent?.dataString)?.let(AppIntent::OpenChat)
}

/** The explicit intent that opens a chat: the deep link, to this app's activity alone. */
fun chatIntent(
    context: Context,
    instanceId: String,
    profileId: String,
): Intent = Intent(Intent.ACTION_VIEW, chatLink(instanceId, profileId).toUri(), context, MainActivity::class.java)

/**
 * The one activity: the design's theme, the back stack in Navigation 3's NavDisplay with its predictive
 * back, `FLAG_SECURE` while an onboarding screen (design section 13.3) or the Instance screen shows or the
 * app is locked, white
 * system bars over the scan's camera, the recents preview hidden while the app lock is on, and the
 * pairing-wait notification when the owner leaves Verify for another app and the upload's short service when
 * the owner leaves with an upload in flight (section 12.5). The app lock's
 * gate hears when the app comes into and goes out of sight, and the system's prompt asks for the unlock as
 * the lock comes, once per time the app is in sight; "Unlock" asks again after a prompt the owner closed.
 */
class MainActivity : ComponentActivity() {
    private val services: AppServices get() = (application as FermixApplication).services

    /** The intent not yet acted on: the one the activity started with, or a later one. */
    private val intents = MutableStateFlow<AppIntent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity's intent was acted on before.
        if (savedInstanceState == null) intents.value = appIntentOf(intent)
        val hooks =
            ActivityHooks(
                fit = { top, locked -> fitWindow(this, top, locked) },
                intents = intents,
                unlock = ::unlock,
                recents = ::setRecentsScreenshotEnabled,
                leave = { moveTaskToBack(true) },
            )
        setContent {
            FermixTheme { FermixApp(services, hooks) }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { promptWhenLocked() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intents.value = appIntentOf(intent)
    }

    override fun onStart() {
        super.onStart()
        services.inBackground.value = false
        services.lockGate.cameIntoSight(SystemClock.elapsedRealtime())
    }

    /**
     * Out of sight: a pairing that waits on the computer gets its notification, and an upload in flight its
     * short service, each started here, as the app leaves, while it may still start a foreground service. A
     * rotation is not leaving.
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        services.inBackground.value = true
        services.lockGate.wentOutOfSight(SystemClock.elapsedRealtime())
        val shown = pairingWaitShown(services.pairingWait.value, services.inBackground.value)
        if (shown != null) PairingWaitService.start(this)
        val uploading = services.supervisor.uploading.value
        if (uploading.isNotEmpty()) UploadService.start(this)
    }

    /** The system's prompt each time the lock comes while the activity is in sight, once it is resumed. */
    private suspend fun promptWhenLocked() {
        services.lockGate.locked.collect { locked ->
            if (locked) withResumed { unlock() }
        }
    }

    private fun unlock() {
        promptUnlock(this, getString(io.tezra.fermix.chats.R.string.chats_locked)) { services.lockGate.unlocked() }
    }
}

/** The activity's three ViewModels: onboarding's, the app's own screens' and the Chats list's. */
internal class AppModels(
    val onboarding: OnboardingViewModel,
    val navigator: AppNavigator,
    val chats: ChatsViewModel,
)

/** What the composition asks of its activity. */
internal class ActivityHooks(
    val fit: (NavKey, Boolean) -> Unit,
    val intents: MutableStateFlow<AppIntent?>,
    val unlock: () -> Unit,
    val recents: (Boolean) -> Unit,
    val leave: () -> Unit,
)

/** Whether this is a debuggable build, which a release never is (the application convention). */
internal fun debuggable(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/** [activity]'s window as [top] needs it, and as the lock does: kept out of screenshots or not, and its bars. */
internal fun fitWindow(
    activity: ComponentActivity,
    top: NavKey,
    locked: Boolean,
) {
    secureWindow(activity.window, top, locked)
    barsOver(activity, top)
}

/**
 * Keeps [window] out of screenshots and the recents while [top] is an onboarding screen (design sections
 * 12.4 and 13.3) or the Instance screen, which shows the daemon's key fingerprint (section 12.4: "Screens
 * showing the SAS, fingerprints or QR set FLAG_SECURE"), or the app is [locked] (section 13.7), and lets it
 * be seen again once none holds.
 */
internal fun secureWindow(
    window: Window,
    top: NavKey,
    locked: Boolean,
) {
    if (securesWindow(top) || top is InstanceKey || locked) {
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
