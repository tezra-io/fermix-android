package io.tezra.fermix

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.tezra.fermix.attest.DeviceKeys
import io.tezra.fermix.attest.HardwareGate
import io.tezra.fermix.chats.ChatsParts
import io.tezra.fermix.chats.ConversationSync
import io.tezra.fermix.chats.PlatformConversations
import io.tezra.fermix.data.AppSettingsStore
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.appSettingsDataStore
import io.tezra.fermix.data.instanceDataStore
import io.tezra.fermix.data.launchCheck
import io.tezra.fermix.instance.InstanceParts
import io.tezra.fermix.instance.NotificationsPolicy
import io.tezra.fermix.onboarding.OnboardingParts
import io.tezra.fermix.onboarding.PairingWait
import io.tezra.fermix.onboarding.deviceNameRefusal
import io.tezra.fermix.onboarding.handleStarter
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.deviceModel
import io.tezra.fermix.transport.NetworkWatcher
import io.tezra.fermix.transport.WebSocketConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Fermix"

/** Where the records, the settings and each instance's files live: credential-encrypted, no backup (section 6.6). */
private const val RECORDS_FILE = "instances.json"
private const val SETTINGS_FILE = "settings.json"
private const val INSTANCES_DIRECTORY = "instances"

/**
 * What the app runs on for as long as its process lives, made once by [FermixApplication]: the instance
 * records and their databases, the app's settings, the network facts, the device keys, the sessions'
 * [supervisor], the app lock's [lockGate], the conversations, and the two facts the pairing-wait
 * notification follows, [pairingWait] (onboarding's) and [inBackground] (the activity's). Blocking work,
 * the files and the Keystore, runs on [io]; a ceremony and the sessions run on [work]. Unpairing asks a
 * daemon to forget the phone through [sendUnpair], `unpair` over the session's live connection, which a test
 * replaces to see who asks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServices(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    sendUnpair: suspend (Session) -> Boolean = { askToForget(it, ::logFault) },
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val sessionScope = CoroutineScope(SupervisorJob() + work)
    private val databases = ProfileDatabases(context, File(context.noBackupFilesDir, INSTANCES_DIRECTORY))
    val instances = InstanceStore(instanceDataStore(File(context.noBackupFilesDir, RECORDS_FILE), scope), databases)
    val settings = AppSettingsStore(appSettingsDataStore(File(context.noBackupFilesDir, SETTINGS_FILE), scope))
    private val network = NetworkWatcher(context)
    private val keys = DeviceKeys()
    private val identity by lazy { phoneIdentity(context) }
    private val sessionsMade =
        AppSessions(
            databases,
            keys,
            WebSocketConnector(),
            network.facts,
            announcer = { instanceId, database ->
                RowAnnouncer(
                    instanceId,
                    MAIN_PROFILE,
                    database,
                    NO_CHAT_ON_SCREEN,
                    NotificationsToCome,
                    System::currentTimeMillis,
                )
            },
        ) { identity.appVersion }
    val supervisor =
        SessionSupervisor(
            instances.instances,
            sessionsMade,
            SessionEvents(instances, databases),
            sessionScope,
            ::logFault,
            sendUnpair,
        )
    val lockGate = LockGate(canLock = { canLock(context) })
    private val conversations = ConversationSync(PlatformConversations(context, ::conversationIntent))
    private val checkedState = MutableStateFlow(false)

    /** Whether the launch check has run: nothing shows, and no session opens, before it has (section 6.6). */
    val checked: StateFlow<Boolean> = checkedState.asStateFlow()

    /** What Verify waits for, while it shows; the pairing-wait notification's facts (design section 12.5). */
    val pairingWait = MutableStateFlow<PairingWait?>(null)

    /** Whether the activity is out of sight, which is when the pairing wait needs its notification. */
    val inBackground = MutableStateFlow(true)

    /**
     * Starts reading the network, then, off the main thread, drops the records whose keys a restore left
     * behind (data's launchCheck), and only then follows the records with the sessions and the
     * conversations, and the settings with the app lock.
     */
    fun start() {
        network.start()
        scope.launch {
            launchCheck(instances, keys::exists)
            checkedState.value = true
            supervisor.start(io)
            launch { namedRecords().collect { (records, agents) -> conversations.sync(records, agents) } }
            settings.settings.collect { withContext(Dispatchers.Main) { lockGate.lockSetting(it.appLock) } }
        }
    }

    /** The records, and each one's agent name from its main profile's chat, which its conversation's name carries. */
    private fun namedRecords(): Flow<Pair<List<Instance>, Map<String, String?>>> =
        instances.instances.flatMapLatest { records ->
            if (records.isEmpty()) return@flatMapLatest flowOf(records to emptyMap())
            combine(records.map { record -> agentOf(record.id).map { record.id to it } }) { agents ->
                records to agents.toMap()
            }
        }

    /** [instanceId]'s agent name as `hello_ack` gave it, until the instance is removed (ProfileDatabases.observe). */
    private fun agentOf(instanceId: String): Flow<String?> =
        databases
            .observe(instanceId, MAIN_PROFILE) { it.chat().state() }
            .map { it.agentName }
            .distinctUntilChanged()

    /** The App lock switch: the settings say it, and the gate hears it from them. */
    fun setAppLock(on: Boolean) {
        scope.launch { settings.setAppLock(on) }
    }

    /** Onboarding's parts, for the ViewModel the activity keeps. */
    fun onboardingParts(): OnboardingParts =
        OnboardingParts(
            gate = { HardwareGate.check(context.packageManager) },
            pairings = handleStarter(keys) { link -> sessionsMade.pairingParts(link, io, sessionScope) },
            identity = identity,
            instances = instances,
            network = network.facts,
            pairingDispatcher = work,
            pairingWait = pairingWait,
            handover = supervisor,
        )

    /** The Chats list's parts. */
    fun chatsParts(): ChatsParts =
        ChatsParts(
            instances = instances,
            sessions = supervisor.sessions,
            thinking = supervisor.thinking,
            profiles = databases,
            unpair = { supervisor.remove(it, unpair = true, removal = ::forget) },
            remove = { supervisor.remove(it, unpair = false, removal = ::forget) },
        )

    /**
     * The Instance screen's parts. The notifications switch is the record's alone until the notifications
     * change brings the channel and `push_register` behind it.
     */
    fun instanceParts(): InstanceParts =
        InstanceParts(
            instances = instances,
            sessions = supervisor.sessions,
            network = network.facts,
            profiles = databases,
            tester = sessionsMade::test,
            notifications = NotificationsPolicy { _, _ -> },
            unpair = { supervisor.remove(it, unpair = true, removal = ::forget) },
            releaseBuild = !debuggable(context),
        )

    /** Removes [instanceId]'s record with its files, then its key, which only the record named. */
    private suspend fun forget(instanceId: String) {
        val record = instances.instances.first().find { it.id == instanceId } ?: return
        instances.remove(instanceId)
        withContext(io) { keys.delete(record.keyAlias) }
    }

    private fun conversationIntent(conversation: io.tezra.fermix.chats.Conversation) =
        chatIntent(context, conversation.instanceId, conversation.profileId)
}

/** No chat shows its rows yet: the chat's bar is all there is of the Chat screen until it comes. */
private val NO_CHAT_ON_SCREEN = ChatOnScreen { _, _ -> false }

/**
 * The notifications to come: until the notifications change brings the channel's posts, no row can be
 * notified of, so none is announced or acked, and the daemon's push for it still comes (PUSH-2).
 */
private object NotificationsToCome : RowNotifier {
    override fun canNotify(
        instanceId: String,
        profileId: String,
    ): Boolean = false

    override fun notify(
        instanceId: String,
        profileId: String,
        row: TimelineRow,
    ): Unit = error("no notification is posted before the notifications change")
}

/** A fault the app logs and lives with: a session it cannot open now. */
private fun logFault(
    message: String,
    fault: Throwable?,
) {
    Log.w(TAG, message, fault)
}

/**
 * This phone to a daemon (design section 6.3): the name its owner gave it in Settings, or its model when
 * that name is none `pair_request` can carry; `Build.MANUFACTURER + Build.MODEL`; and the app's version.
 */
private fun phoneIdentity(context: Context): PhoneIdentity {
    val named = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()
    val deviceName = if (named.isNullOrEmpty() || deviceNameRefusal(named) != null) Build.MODEL else named
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    return PhoneIdentity(
        deviceName = deviceName,
        model = deviceModel(Build.MANUFACTURER, Build.MODEL),
        appVersion = checkNotNull(version) { "the app's manifest names its version" },
    )
}
