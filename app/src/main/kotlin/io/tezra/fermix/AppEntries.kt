package io.tezra.fermix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.tezra.fermix.chats.AppLockScreen
import io.tezra.fermix.chats.ChatPlaceholder
import io.tezra.fermix.chats.ChatRow
import io.tezra.fermix.chats.ChatsActions
import io.tezra.fermix.chats.ChatsScreen
import io.tezra.fermix.chats.Trust
import io.tezra.fermix.chats.TrustScreen
import io.tezra.fermix.instance.InstanceRoute
import io.tezra.fermix.instance.InstanceViewModel
import io.tezra.fermix.instance.Link
import kotlinx.coroutines.flow.map

/**
 * The app's own screens as entries of the back stack (design section 13.2): the Chats list, a chat, the
 * Instance screen, the two trust states and the App lock setting. An action reaches a model only from the
 * screen on top ([showing]).
 */
internal fun appEntries(
    builder: EntryProviderScope<NavKey>,
    services: AppServices,
    models: AppModels,
    showing: (NavKey) -> Boolean,
) {
    builder.entry<ChatsKey> { ChatsEntry(models) { showing(ChatsKey) } }
    builder.entry<ChatKey> { key -> ChatEntry(key, models) { showing(key) } }
    builder.entry<InstanceKey> { key ->
        val model =
            viewModel(
                key = "instance:${key.instanceId}",
            ) { InstanceViewModel(services.instanceParts(), key.instanceId) }
        InstanceRoute(model, onBack = models.navigator::back, showing = { showing(key) })
    }
    builder.entry<RevokedKey> { key -> TrustEntry(Trust.REVOKED, key.instanceId, models) { showing(key) } }
    builder.entry<IdentityChangedKey> { key ->
        TrustEntry(Trust.IDENTITY_CHANGED, key.instanceId, models) { showing(key) }
    }
    builder.entry<AppLockKey> { AppLockEntry(services, models) { showing(AppLockKey) } }
}

/**
 * The full screen [instanceId]'s [link] calls for while the owner must decide (design section 9.4): the
 * revoked one or the identity-changed one, and none for any other link, a protocol error's among them.
 */
internal fun trustKey(
    link: Link,
    instanceId: String,
): NavKey? =
    when (link) {
        Link.Revoked -> RevokedKey(instanceId)
        Link.IdentityChanged -> IdentityChangedKey(instanceId)
        else -> null
    }

/** Where a row opens: its trust state's screen while the owner must decide, else its chat. */
internal fun keyFor(row: ChatRow): NavKey = trustKey(row.link, row.record.id) ?: ChatKey(row.record.id, row.profileId)

@Composable
private fun ChatsEntry(
    models: AppModels,
    showing: () -> Boolean,
) {
    val ui by models.chats.ui.collectAsState()
    val chats = models.chats
    val navigator = models.navigator
    val actions =
        remember(models) {
            fun guarded(action: () -> Unit) = { if (showing()) action() }
            ChatsActions(
                onAdd = guarded { addFermix(models) },
                onAppLock = guarded { navigator.open(AppLockKey) },
                onOpen = { row -> if (showing()) navigator.open(keyFor(row)) },
                onMoveToTop = { if (showing()) chats.moveToTop(it) },
                onRename = { id, nickname -> if (showing()) chats.rename(id, nickname) },
                onDetails = { if (showing()) navigator.open(InstanceKey(it)) },
                onUnpair = { if (showing()) chats.unpair(it) },
                onRepair = { if (showing()) addFermix(models) },
                onDismissRepair = { if (showing()) chats.dismissRepair(it) },
            )
        }
    ui?.let { ChatsScreen(it, actions) }
}

/** A chat, until the Chat screen is built its bar; a trust state takes its place as soon as it holds. */
@Composable
private fun ChatEntry(
    key: ChatKey,
    models: AppModels,
    showing: () -> Boolean,
) {
    val header by remember(key) { models.chats.header(key.instanceId) }.collectAsState(initial = null)
    val trust = header?.link?.let { trustKey(it, key.instanceId) }
    LaunchedEffect(trust) {
        if (trust != null) models.navigator.replace(key, trust)
    }
    header?.let {
        ChatPlaceholder(
            header = it,
            onBack = { if (showing()) models.navigator.back() },
            onTitle = { if (showing()) models.navigator.open(InstanceKey(key.instanceId)) },
        )
    }
}

/** A trust state's screen: "Pair again" pairs from the root and merges into the row; "Remove" removes it. */
@Composable
private fun TrustEntry(
    trust: Trust,
    instanceId: String,
    models: AppModels,
    showing: () -> Boolean,
) {
    val header by remember(instanceId) { models.chats.header(instanceId) }.collectAsState(initial = null)
    val host = header?.record?.host ?: return
    TrustScreen(
        trust = trust,
        host = host,
        onBack = { if (showing()) models.navigator.back() },
        onPairAgain = {
            if (showing()) {
                models.navigator.toRoot()
                models.onboarding.getStarted(mergeInto = instanceId)
            }
        },
        onRemove = { if (showing()) models.chats.remove(instanceId) },
    )
}

/**
 * The App lock setting, its switch off and explained on a phone that cannot hold the lock. Whether it can is
 * asked again each time the screen is resumed, so a screen lock the owner sets in the system's settings
 * turns the switch on as they come back.
 */
@Composable
private fun AppLockEntry(
    services: AppServices,
    models: AppModels,
    showing: () -> Boolean,
) {
    val context = LocalContext.current
    val on by remember(services) { services.settings.settings.map { it.appLock } }.collectAsState(initial = false)
    var available by remember(context) { mutableStateOf(canLock(context)) }
    LifecycleResumeEffect(context) {
        available = canLock(context)
        onPauseOrDispose {}
    }
    AppLockScreen(
        on = on,
        available = available,
        onBack = { if (showing()) models.navigator.back() },
        onChange = { if (showing()) services.setAppLock(it) },
    )
}
