package io.tezra.fermix.instance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceGone
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import io.tezra.fermix.transport.reachability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How long the screen's watch outlives its last collector: a rotation re-subscribes well within it, and a
 * screen left in the activity's ViewModels stops watching the session once it is gone.
 */
private const val WATCH_LINGER_MILLIS = 5_000L

/**
 * What turns a Fermix's notifications on and off beyond its record's switch: its channel and
 * `push_register` / `push_unregister` (design sections 10 and 13.7). The app's does nothing yet; the
 * notifications change brings the rest.
 */
fun interface NotificationsPolicy {
    suspend fun apply(
        instance: Instance,
        on: Boolean,
    )
}

/**
 * What the Instance screen runs on, all of it the app's: the records, the sessions the app keeps by
 * instance id, the network facts, the profiles' files, where each instance's chat settings and media cache
 * are read through [ProfileDatabases], which orders them against the instance's removal, the connection
 * test, the notifications policy, the unpairing (which sends `unpair` and removes the instance), and whether
 * this is a release build.
 */
data class InstanceParts(
    val instances: InstanceStore,
    val sessions: StateFlow<Map<String, Session>>,
    val network: StateFlow<NetworkFacts>,
    val profiles: ProfileDatabases,
    val tester: ConnectionTester,
    val notifications: NotificationsPolicy,
    val unpair: suspend (String) -> Unit,
    val releaseBuild: Boolean,
)

/**
 * What the Instance screen shows of [record] (design section 13.7): its [link] and the session's
 * [diagnostics], the candidates whose dot is ok ([reachable]), the other Fermixes a nickname is held
 * against, whether notifications show [previews], the media cache's size once read, the last connection
 * test, and the build.
 */
data class InstanceUi(
    val record: Instance,
    val others: List<Instance>,
    val link: Link,
    val diagnostics: List<Diagnostic>,
    val reachable: Set<Candidate>,
    val previews: Boolean,
    val cacheBytes: Long?,
    val test: TestState,
    val releaseBuild: Boolean,
)

/** Whether [this]'s daemon pushes through FCM: `hello_ack.caps.push` once known, else the pairing's. */
val Instance.pushReady: Boolean get() = PushPlatform.FCM in (caps?.push ?: pushPlatforms)

/**
 * A session's state and diagnostics together, and the candidate its live connection went over; none of
 * them for an instance without a session.
 */
data class Watched(
    val state: SessionState?,
    val diagnostics: List<Diagnostic>,
    val live: Candidate?,
)

/** What the screen itself holds: the last connection test, and the media cache's size once read. */
data class ScreenFacts(
    val test: TestState,
    val cacheBytes: Long?,
)

/** What the phone holds besides the records and the session: the chat's settings, the [screen]'s facts, the network. */
data class PhoneFacts(
    val chat: ChatState,
    val screen: ScreenFacts,
    val network: NetworkFacts,
)

/**
 * The Instance screen's state for [instanceId] among [all], none once its record is gone, from the
 * session [watched], what the [phone] holds and the build.
 */
fun instanceUiOf(
    all: List<Instance>,
    instanceId: String,
    watched: Watched,
    phone: PhoneFacts,
    releaseBuild: Boolean,
): InstanceUi? {
    val record = all.find { it.id == instanceId } ?: return null
    val test = phone.screen.test
    return InstanceUi(
        record = record,
        others = all - record,
        link = linkOf(watched.state, watched.diagnostics),
        diagnostics = watched.diagnostics,
        reachable =
            reachableCandidates(record.candidates, watched.live, test, reachability(phone.network, record.candidates)),
        previews = phone.chat.previews,
        cacheBytes = phone.screen.cacheBytes,
        test = test,
        releaseBuild = releaseBuild,
    )
}

/**
 * The Instance screen of [instanceId] (design section 13.7). [ui] is none until the record and the chat's
 * settings are read, and none again once the record is gone, which takes the screen off the stack. The
 * activity keeps it across visits, so each visit begins with [entered].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstanceViewModel(
    private val parts: InstanceParts,
    private val instanceId: String,
) : ViewModel() {
    private val facts = MutableStateFlow(ScreenFacts(TestState.Idle, cacheBytes = null))
    private var testing: Job? = null

    private val watched: Flow<Watched> =
        parts.sessions
            .map { it[instanceId] }
            .distinctUntilChanged()
            .flatMapLatest { session ->
                if (session == null) flowOf(Watched(null, emptyList(), null)) else watch(session)
            }

    private val chat: Flow<ChatState> = parts.profiles.observe(instanceId, MAIN_PROFILE) { it.chat().state() }

    val ui: StateFlow<InstanceUi?> =
        combine(parts.instances.instances, watched, chat, facts, parts.network) { all, link, chatState, own, network ->
            instanceUiOf(all, instanceId, link, PhoneFacts(chatState, own, network), parts.releaseBuild)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(WATCH_LINGER_MILLIS), null)

    init {
        require(instanceId.isNotBlank()) { "the Instance screen names its instance" }
    }

    /** A new visit to the screen: no test result from a visit before, and the cache measured now. */
    fun entered() {
        testing?.cancel()
        testing = null
        facts.update { it.copy(test = TestState.Idle) }
        viewModelScope.launch { measureCache() }
    }

    /** The owner's nickname, checked by the dialog against data's rule, or none: "Reset to gateway name". */
    fun rename(nickname: String?) {
        viewModelScope.launch {
            val refusal = parts.instances.rename(instanceId, nickname)
            check(refusal == null) { "the rename dialog offered a nickname the rule refuses: $refusal" }
        }
    }

    /** "Test connection": one race at a time, its outcome reported in place. */
    fun testConnection() {
        if (facts.value.test == TestState.Running) return
        val record = ui.value?.record ?: return
        facts.update { it.copy(test = TestState.Running) }
        testing =
            viewModelScope.launch {
                val outcome = parts.tester.test(record)
                facts.update { it.copy(test = TestState.Done(outcome)) }
            }
    }

    /** The Notifications switch: the record says it, then the policy acts on it. */
    fun setNotifications(on: Boolean) {
        viewModelScope.launch {
            parts.instances.update(instanceId) { it.copy(notificationsEnabled = on) }
            val record =
                parts.instances.instances
                    .first()
                    .find { it.id == instanceId } ?: return@launch
            parts.notifications.apply(record, on)
        }
    }

    fun setPreviews(on: Boolean) {
        viewModelScope.launch {
            whilePaired { parts.profiles.withDatabase(instanceId, MAIN_PROFILE) { it.chat().setPreviews(on) } }
        }
    }

    /** "Clear media cache", then the size again. */
    fun clearCache() {
        viewModelScope.launch {
            whilePaired { parts.profiles.withMediaCache(instanceId, MAIN_PROFILE) { it.clear() } }
            measureCache()
        }
    }

    /** "Unpair" in the dialog: the app asks the daemon to forget this phone and removes the instance. */
    fun unpair() {
        viewModelScope.launch { parts.unpair(instanceId) }
    }

    private suspend fun measureCache() {
        whilePaired {
            val bytes = parts.profiles.withMediaCache(instanceId, MAIN_PROFILE) { it.size() }
            facts.update { it.copy(cacheBytes = bytes) }
        }
    }

    /**
     * [use] of the instance's files, unless a removal took them meanwhile (ProfileDatabases.delete): the record
     * went first, so the screen leaves with it, and there is nothing left to set, clear or measure.
     */
    private suspend fun whilePaired(use: suspend () -> Unit) {
        try {
            use()
        } catch (expected: InstanceGone) {
            // The instance was removed while the screen asked; its screen goes with its record.
        }
    }
}

/** [session]'s state and diagnostics, and the candidate its connection went over while it is connected. */
private fun watch(session: Session): Flow<Watched> =
    combine(session.state, session.diagnostics, session.lastSuccessful) { state, diagnostics, last ->
        Watched(state, diagnostics, live = last.takeIf { state is SessionState.Connected })
    }
