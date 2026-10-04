package io.tezra.fermix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.tezra.fermix.chats.ChatsViewModel
import io.tezra.fermix.chats.LockScreen
import io.tezra.fermix.chats.ShareSheet
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.onboarding.OnboardingViewModel
import io.tezra.fermix.onboarding.appBackStack
import io.tezra.fermix.onboarding.clipboardClip
import io.tezra.fermix.onboarding.onboardingEntries
import io.tezra.fermix.onboarding.phoneCamera
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** The key the screens' saved state is kept under while the lock holds and they are not drawn. */
private const val SCREENS = "screens"

/**
 * The back stack: the root, Welcome, or the Chats list once a record exists or a Fermix a restore dropped
 * waits to be paired again, the app's screens above it and onboarding's on top. Nothing shows until the
 * launch check has run, the records are read and the settings have said whether the app lock is on, so
 * the root never flickers from Welcome to Chats and never shows before the lock; then the last chat is
 * restored, or the one an intent names. While the lock holds, the screens are not drawn at all, so nothing
 * of them shows under "Fermix is locked" or reaches TalkBack, and what they keep for a rotation they keep
 * across the lock. Another app's share waits behind the lock too, then asks "Send to which Fermix?" or lands.
 */
@Composable
internal fun FermixApp(
    services: AppServices,
    hooks: ActivityHooks,
) {
    val list = viewModel { ChatsViewModel(services.chatsParts()) }
    val models =
        AppModels(
            onboarding = viewModel { OnboardingViewModel(services.onboardingParts()) },
            navigator = viewModel { AppNavigator(services.settings, services.instances.instances) },
            chats = list,
            share = viewModel { shareModel(services, list) },
        )
    val checked by services.checked.collectAsState()
    val lockKnown by services.lockGate.known.collectAsState()
    val rootFlow = remember(services) { chatsRoot(services.instances) }
    val chats by rootFlow.collectAsState(initial = null)
    val locked by services.lockGate.locked.collectAsState()
    val known = chats.takeIf { checked && lockKnown }
    FollowIntents(known, models, hooks.intents)
    FollowLockSetting(remember(services) { services.settings.settings.map { it.appLock } }, hooks.recents)
    val saved = rememberSaveableStateHolder()
    val canvas = LocalFermixColors.current.canvas
    Box(modifier = Modifier.fillMaxSize().background(canvas)) {
        if (known != null) {
            val above by models.navigator.above.collectAsState()
            val onboarding by models.onboarding.stack.collectAsState()
            val stack = appBackStack(known, ChatsKey, if (known) above else emptyList(), onboarding)
            val top = stack.last()
            LaunchedEffect(top, locked) { hooks.fit(top, locked) }
            if (!locked) saved.SaveableStateProvider(SCREENS) { Screens(stack, services, models) }
            if (!locked) ShareFlow(services, models)
        }
        if (locked) {
            LockScreen(onUnlock = hooks.unlock)
            BackHandler(onBack = hooks.leave)
        }
    }
}

/**
 * Whether the root is the Chats list: a Fermix is paired, or one a restore dropped waits for "Re-pair this
 * Fermix" (data's launchCheck), which only the list shows.
 */
private fun chatsRoot(instances: InstanceStore): Flow<Boolean> =
    combine(instances.instances, instances.repairNotices) { records, repairs ->
        records.isNotEmpty() || repairs.isNotEmpty()
    }

/**
 * The activity's share (design section 13.6): what the share entry hands over, behind the lock's [Sight] and among the
 * paired that take a share as the Chats list's rows read them (shareTargetsOf).
 */
private fun shareModel(
    services: AppServices,
    chats: ChatsViewModel,
): ShareModel =
    ShareModel(services.lockGate.sight, chats.ui.map { shareTargetsOf(it?.rows) }, services.shares, ::logShare)

/** Restores the last chat once the records are known, then acts on each intent as it comes. */
@Composable
private fun FollowIntents(
    known: Boolean?,
    models: AppModels,
    intents: kotlinx.coroutines.flow.MutableStateFlow<AppIntent?>,
) {
    val pending by intents.collectAsState()
    LaunchedEffect(known, pending) {
        if (known == null) return@LaunchedEffect
        val intent = pending
        models.navigator.restore((intent as? AppIntent.OpenChat)?.chat)
        when (intent) {
            is AppIntent.OpenChat -> models.navigator.showChat(intent.chat)
            AppIntent.AddFermix -> addFermix(models)
            null -> return@LaunchedEffect
        }
        intents.value = null
    }
}

/**
 * A share once the lock is passed (design section 13.6): "Send to which Fermix?" over the app while it asks, and the
 * share landing in the chat it goes to.
 */
@Composable
private fun ShareFlow(
    services: AppServices,
    models: AppModels,
) {
    val state by models.share.state.collectAsState()
    when (val now = state) {
        is ShareState.Landing -> LandShare(now, services, models)
        is ShareState.Pending -> if (now.asked) AskShare(models)
        ShareState.None -> Unit
    }
}

/** "Send to which Fermix?" over the paired chats, as the Chats list has them, those a share may go to (shareRowsOf). */
@Composable
private fun AskShare(models: AppModels) {
    val ui by models.chats.ui.collectAsState()
    val rows = ui?.rows ?: return
    ShareSheet(
        rows = shareRowsOf(rows),
        onPick = { row -> models.share.picked(ChatKey(row.record.id, row.profileId)) },
        onDismiss = models.share::dismissed,
    )
}

/**
 * [landing] into its chat's model, the one its screen draws, once: its items into the tray and its words into the
 * draft, nothing sent; then the chat shows over the list.
 */
@Composable
private fun LandShare(
    landing: ShareState.Landing,
    services: AppServices,
    models: AppModels,
) {
    val model = chatModel(services, landing.chat)
    LaunchedEffect(landing) {
        if (!models.share.landed(landing)) return@LaunchedEffect
        model.share(landing.shared)
        models.navigator.showPaired(landing.chat)
    }
}

/**
 * The recents preview is hidden while [appLock], the setting, is on (design section 13.7); [recents] shows it or not.
 * Until the setting is read the lock is taken as on, so a window made then shows nothing in the recents.
 */
@Composable
internal fun FollowLockSetting(
    appLock: Flow<Boolean>,
    recents: (Boolean) -> Unit,
) {
    val locked by appLock.collectAsState(initial = true)
    LaunchedEffect(locked) { recents(!locked) }
}

/** "Add Fermix": a pairing from the root, unless one is under way. */
internal fun addFermix(models: AppModels) {
    if (models.onboarding.stack.value
            .isNotEmpty()
    ) {
        return
    }
    models.navigator.toRoot()
    models.onboarding.getStarted()
}

@Composable
private fun Screens(
    stack: List<NavKey>,
    services: AppServices,
    models: AppModels,
) {
    val current = rememberUpdatedState(stack)
    val context = LocalContext.current
    val camera = remember { phoneCamera() }
    val clip = remember(context) { clipboardClip(context) }
    NavDisplay(
        backStack = stack,
        onBack = {
            if (models.onboarding.stack.value
                    .isNotEmpty()
            ) {
                models.onboarding.back()
            } else {
                models.navigator.back()
            }
        },
        entryProvider =
            entryProvider {
                onboardingEntries(this, models.onboarding, camera, clip)
                appEntries(this, services, models, showing(current))
            },
    )
}

/** Whether [key] is the screen on top: an action from a screen leaving is still drawn, and hit, and dropped. */
private fun showing(stack: State<List<NavKey>>): (NavKey) -> Boolean = { key -> stack.value.lastOrNull() == key }
