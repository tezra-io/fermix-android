package io.tezra.fermix

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.attest.DeviceKeys
import io.tezra.fermix.attest.HardwareGate
import io.tezra.fermix.chat.ChatClock
import io.tezra.fermix.chat.ChatLive
import io.tezra.fermix.chat.ChatParts
import io.tezra.fermix.chat.PhoneClip
import io.tezra.fermix.chat.PhoneMedia
import io.tezra.fermix.chat.PhonePlayer
import io.tezra.fermix.chat.PhoneRecorder
import io.tezra.fermix.chat.RoomChatFiles
import io.tezra.fermix.chat.RoomChatStore
import io.tezra.fermix.chat.SessionChat
import io.tezra.fermix.chats.ChatsParts
import io.tezra.fermix.chats.ConversationSync
import io.tezra.fermix.chats.PlatformConversations
import io.tezra.fermix.data.AppSettingsStore
import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.Use
import io.tezra.fermix.data.appSettingsDataStore
import io.tezra.fermix.data.instanceDataStore
import io.tezra.fermix.data.launchCheck
import io.tezra.fermix.instance.InstanceParts
import io.tezra.fermix.onboarding.OnboardingParts
import io.tezra.fermix.onboarding.PairingWait
import io.tezra.fermix.onboarding.deviceNameRefusal
import io.tezra.fermix.onboarding.handleStarter
import io.tezra.fermix.push.PushLog
import io.tezra.fermix.push.TrialDecrypt
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.session.Session
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "Fermix"

/** Where the records, the settings and each instance's files live: credential-encrypted, no backup (section 6.6). */
private const val RECORDS_FILE = "instances.json"
private const val SETTINGS_FILE = "settings.json"
private const val INSTANCES_DIRECTORY = "instances"

/**
 * What the app runs on for as long as its process lives, made once by [FermixApplication]: the instance
 * records and their databases, the app's settings, the network facts, the device keys, the sessions'
 * [supervisor], the app lock's [lockGate], the conversations, and the two facts the pairing-wait
 * notification follows, [pairingWait] (onboarding's) and [inBackground] (the activity's), and the
 * notifications: their one owner, a push's inbox ([push]) and the push registrations ([registrations]).
 * Blocking work, the files and the Keystore, runs on [io]; a ceremony and the sessions run on [work].
 * Unpairing asks a daemon to forget the phone through [sendUnpair], `unpair` over the session's live
 * connection, which a test replaces to see who asks; whether a session has an upload in flight is
 * [uploadingOf]'s to say, Session.uploading, which a test replaces to have one; and the device keys are
 * the Keystore's ([keys]), which a test replaces with software keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServices(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    sendUnpair: suspend (Session) -> Boolean = { askToForget(it, ::logFault) },
    private val uploadingOf: (Session) -> Flow<Boolean> = { it.uploading },
    private val keys: DeviceKeyFacade = DeviceKeys(),
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val sessionScope = CoroutineScope(SupervisorJob() + work)
    internal val databases = ProfileDatabases(context, File(context.noBackupFilesDir, INSTANCES_DIRECTORY))
    val instances = InstanceStore(instanceDataStore(File(context.noBackupFilesDir, RECORDS_FILE), scope), databases)

    /**
     * The records as one collector reads them from the moment the services are made, before anything writes
     * them, shared by the notifications and the chats' followers: a DataStore collector that starts while a
     * write is under way was seen to miss that write for good (FermixMessagingServiceTest, on Robolectric).
     */
    private val records = instances.instances.shareIn(scope, SharingStarted.Eagerly, replay = 1)
    val settings = AppSettingsStore(appSettingsDataStore(File(context.noBackupFilesDir, SETTINGS_FILE), scope))
    private val network = NetworkWatcher(context)
    private val identity by lazy { phoneIdentity(context) }
    private val onScreen = OnScreenChats()
    private val posted = PostedNotifications(context)
    private val notifications =
        Notifications(
            posted,
            records.stateIn(scope, SharingStarted.Eagerly, emptyList()),
            databases,
            NotificationCopy.of(context.resources),
            locked = { settings.settings.first().appLock },
            now = System::currentTimeMillis,
        )
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
                    onScreen,
                    notifications,
                    System::currentTimeMillis,
                )
            },
        ) { identity.appVersion }
    private val folds = ChatFolds(AppClock, ::newestKept)
    private val fcmToken = FcmToken { askFcmForToken(it, ::logFault) }

    /** This phone's push registration with each daemon, over the sessions the supervisor holds. */
    val registrations: PushRegistrations =
        PushRegistrations(
            RegistrationParts(
                instances,
                // The supervisor's, which is made below: read at each step, never while this is made.
                sessions = { supervisor.sessions.value },
                canShow = { posted.canShow(conversationId(it.id, MAIN_PROFILE)) },
                token = fcmToken::current,
                now = System::currentTimeMillis,
                log = ::logFault,
            ),
        )
    val supervisor: SessionSupervisor =
        SessionSupervisor(
            instances.instances,
            sessionsMade,
            SessionEvents(
                instances,
                databases,
                folds,
                ApprovalAlerts(onScreen, notifications, System::currentTimeMillis),
                notifications,
                registrations,
            ),
            sessionScope,
            ::logFault,
            sendUnpair,
        )

    /** What a push comes to (FermixMessagingService), and the diagnostics ring of each one's lines. */
    val pushLog = PushLog()
    val push =
        PushInbox(
            PushParts(
                instances,
                TrialDecrypt(keys, ::logFault),
                notifications,
                databases,
                onScreen,
                pushLog,
                keystore = scope,
                now = System::currentTimeMillis,
            ),
        )
    val lockGate = LockGate(canLock = { canLock(context) })
    private val conversations =
        ConversationSync(PlatformConversations(context) { chatIntent(context, it.instanceId, it.profileId) })
    private val checkedState = MutableStateFlow(false)

    /** Whether the launch check has run: nothing shows, and no session opens, before it has (section 6.6). */
    val checked: StateFlow<Boolean> = checkedState.asStateFlow()

    /** What Verify waits for, while it shows; the pairing-wait notification's facts (design section 12.5). */
    val pairingWait = MutableStateFlow<PairingWait?>(null)

    /** Whether the activity is out of sight, which is when the pairing wait needs its notification. */
    val inBackground = MutableStateFlow(true)

    /**
     * A share the share entry took (ShareTarget), until the activity takes it (ShareModel): handed over in the
     * process, never through an intent, so nothing another app sends the activity is taken for a share.
     */
    val shares = MutableStateFlow<Share?>(null)

    /**
     * Starts reading the network, then, off the main thread, drops the records whose keys a restore left
     * behind (data's launchCheck), and only then follows the records with the sessions and the
     * conversations, whose names carry each one's agent, the app lock and each chat's previews with the
     * notifications showing, and the settings with the app lock.
     */
    fun start() {
        network.start()
        scope.launch {
            launchCheck(instances, keys::exists)
            checkedState.value = true
            supervisor.start(io, uploadingOf)
            launch { supervisor.inSight.filter { it }.collect { registrations.refresh() } }
            val agents = chatsOf().map { (listed, chats) -> listed to chats.mapValues { it.value.agentName } }
            launch { agents.distinctUntilChanged().collect { (listed, named) -> conversations.sync(listed, named) } }
            val looks =
                combine(settings.settings, chatsOf()) { set, (_, chats) ->
                    set.appLock to chats.mapValues { it.value.previews }
                }
            launch { looks.distinctUntilChanged().collect { notifications.restyled() } }
            settings.settings.collect { withContext(Dispatchers.Main) { lockGate.lockSetting(it.appLock) } }
        }
    }

    /**
     * The records, and each one's main chat as its profile keeps it, until the instance is removed
     * (ProfileDatabases.observe): its agent's name, as `hello_ack` gave it, and its previews switch.
     */
    private fun chatsOf(): Flow<Pair<List<Instance>, Map<String, ChatState>>> =
        records.flatMapLatest { listed ->
            if (listed.isEmpty()) return@flatMapLatest flowOf(listed to emptyMap())
            val chats = listed.map { record -> databases.observe(record.id, MAIN_PROFILE) { it.chat().state() } }
            combine(chats) { states -> listed to listed.map { it.id }.zip(states).toMap() }
        }

    /** The App lock switch: the settings say it, and the gate hears it from them. */
    fun setAppLock(on: Boolean) {
        scope.launch { settings.setAppLock(on) }
    }

    /**
     * FCM handed this app's [token] (FermixMessagingService.onRegistered): a changed one, or one nobody asked
     * for, registers every daemon with it again; the one asked for registers those that waited for it.
     */
    fun fcmRegistered(token: String) {
        val changed = fcmToken.registered(token)
        scope.launch { if (changed) registrations.newToken() else registrations.refresh() }
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
            notifications = registrations::apply,
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

    /** The Instance screen's parts: its Notifications switch registers or unregisters this phone's push. */
    fun instanceParts(): InstanceParts =
        InstanceParts(
            instances = instances,
            sessions = supervisor.sessions,
            network = network.facts,
            profiles = databases,
            tester = sessionsMade::test,
            notifications = registrations,
            unpair = { supervisor.remove(it, unpair = true, removal = ::forget) },
            releaseBuild = !debuggable(context),
        )

    /**
     * A chat's parts: [instanceId]'s session while the supervisor holds one, the app's fold of its events, its
     * [profileId]'s cache and staged uploads, the network, where it reports itself on screen, the clocks, the app's
     * scope, which keeps its draft as it leaves, the phone's media, clipboard, microphone and player, and the cache
     * directory a fetched blob or a made attachment lands in before the media cache or the outbox takes it.
     */
    fun chatParts(
        instanceId: String,
        profileId: String,
    ): ChatParts =
        ChatParts(
            instanceId = instanceId,
            profileId = profileId,
            records = instances.instances,
            session =
                supervisor.sessions
                    .map { held -> held[instanceId]?.let { SessionChat(it, ::logFault) } }
                    .distinctUntilChanged(),
            live = folds.chats.map { it[instanceId] ?: ChatLive() }.distinctUntilChanged(),
            store = RoomChatStore(databases, instanceId, profileId),
            network = network.facts,
            presence = onScreen.presence(instanceId, profileId),
            clock = AppClock,
            background = scope,
            newId = { UUID.randomUUID().toString() },
            log = ::logFault,
            files = RoomChatFiles(databases, instanceId, profileId),
            media = PhoneMedia(context, ::logFault),
            clip = PhoneClip(context, ::logFault),
            recorder = PhoneRecorder(context, ::logFault),
            player = PhonePlayer(),
            scratch = { File.createTempFile("fetch", null, context.cacheDir) },
        )

    /** The newest row [instanceId]'s main profile keeps, 0 when none or once it is removed. */
    private suspend fun newestKept(instanceId: String): ULong {
        val newest =
            databases.withDatabase(instanceId, MAIN_PROFILE) { profile ->
                profile
                    .timeline()
                    .newest(1)
                    .first()
                    .firstOrNull()
                    ?.serverSeq
            }
        return when (newest) {
            is Use.Ran -> newest.value ?: 0uL

            // A Fermix removed meanwhile keeps no row: its chat is folded with nothing held.
            Use.Gone -> 0uL
        }
    }

    /** Removes [instanceId]'s record with its files, then its key, which only the record named. */
    private suspend fun forget(instanceId: String) {
        val record = instances.instances.first().find { it.id == instanceId } ?: return
        instances.remove(instanceId)
        withContext(io) { keys.delete(record.keyAlias) }
    }
}

/** The clocks a chat reads: the monotonic one since boot, and the wall's. */
private object AppClock : ChatClock {
    override fun monoMs(): Long = SystemClock.elapsedRealtime()

    override fun wallMs(): Long = System.currentTimeMillis()
}

/** A fault the app logs and lives with: a session it cannot open now, a chat's request that did not go. */
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
