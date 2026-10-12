package io.tezra.fermix.instance

import android.graphics.ImageDecoder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.EYE_STYLES
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.TINT_NAMES
import io.tezra.fermix.data.Use
import io.tezra.fermix.data.setEyes
import io.tezra.fermix.data.setTint
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import io.tezra.fermix.transport.reachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.IOException

/**
 * How long the screen's watch outlives its last collector: a rotation re-subscribes well within it, and a
 * screen left in the activity's ViewModels stops watching the session once it is gone.
 */
private const val WATCH_LINGER_MILLIS = 5_000L

/**
 * What turns a Fermix's notifications on and off beyond its record's switch: `push_register` /
 * `push_unregister` over its live connection (design sections 10 and 13.7), the app's PushRegistrations.
 * The record holds the answer before it is applied.
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
 * this is a release build; and for the avatar's photo (the owner, 2026-10-10), the [photo] a picked URI is read
 * from, only through the platform's ContentResolver, the wall clock [now] that versions it, and the [log] a photo
 * that could not be used is told to.
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
    val photo: (String) -> ImageDecoder.Source,
    val now: () -> Long,
    val log: (String, Throwable?) -> Unit,
)

/**
 * What the Instance screen shows of [record] (design section 13.7): its [link] and the session's
 * [diagnostics], the candidates whose dot is ok ([reachable]), the other Fermixes a nickname is held
 * against, whether notifications show [previews], the media cache's size once read, the last connection
 * test, the build, and whether the last photo picked this visit could not be used ([pictureFailed]).
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
    val pictureFailed: Boolean = false,
)

/**
 * A session's state and diagnostics together, and the candidate its live connection went over; none of
 * them for an instance without a session.
 */
data class Watched(
    val state: SessionState?,
    val diagnostics: List<Diagnostic>,
    val live: Candidate?,
)

/**
 * What the screen itself holds: the last connection test, the media cache's size once read, and whether the last
 * photo picked could not be used.
 */
data class ScreenFacts(
    val test: TestState,
    val cacheBytes: Long?,
    val pictureFailed: Boolean = false,
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
        pictureFailed = phone.screen.pictureFailed,
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

    /** The photo's last write or removal, which the next one cancels and waits out: the owner's last word wins. */
    private var picturing: Job? = null

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

    /** A new visit to the screen: no test result or photo's failure from a visit before, and the cache measured now. */
    fun entered() {
        testing?.cancel()
        testing = null
        facts.update { it.copy(test = TestState.Idle, pictureFailed = false) }
        viewModelScope.launch { measureCache(parts, instanceId, facts) }
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

    /**
     * The previews switch. A Fermix removed meanwhile has no chat to set ([Use.Gone]); its screen goes with its
     * record.
     */
    fun setPreviews(on: Boolean) {
        viewModelScope.launch { parts.profiles.withDatabase(instanceId, MAIN_PROFILE) { it.chat().setPreviews(on) } }
    }

    /** "Clear media cache", then the size again; a Fermix removed meanwhile has neither ([Use.Gone]). */
    fun clearCache() {
        viewModelScope.launch {
            val cleared = parts.profiles.withMediaCache(instanceId, MAIN_PROFILE) { it.clear() }
            if (cleared is Use.Ran) measureCache(parts, instanceId, facts)
        }
    }

    /** A tint of the Picture sheet's, one of data's TINT_NAMES, on the record. */
    fun setTint(tint: String) {
        require(tint in TINT_NAMES) { "tint '$tint' is not one of $TINT_NAMES" }
        viewModelScope.launch { parts.instances.setTint(instanceId, tint) }
    }

    /** Eyes of the Picture sheet's, one of data's EYE_STYLES, on the record. */
    fun setEyes(eyes: String) {
        require(eyes in EYE_STYLES) { "eyes '$eyes' are not one of $EYE_STYLES" }
        viewModelScope.launch { parts.instances.setEyes(instanceId, eyes) }
    }

    /**
     * The photo the owner picked ([uri], the Photo Picker's) as the avatar: its square written by data's
     * AvatarPictures, then the record told when, which every screen's avatar reads it by. One that cannot be read, is
     * past the pixel bound, takes past the landing's time, that the app may not read (data's avatarSource) or may no
     * longer read, or whose provider fails, leaves the record as it was, is told to the log, and the screen says so. A
     * record a removal took meanwhile takes the photo's file with it (InstanceStore's setPicture). A photo still on its
     * way, a slow provider's, is cancelled first and never lands ([after]).
     */
    fun setPicture(uri: String) {
        picturing =
            after(viewModelScope, picturing) {
                facts.update { it.copy(pictureFailed = false) }
                if (madePicture(parts, instanceId, uri)) {
                    parts.instances.setPicture(instanceId, parts.now())
                } else {
                    facts.update { it.copy(pictureFailed = true) }
                }
            }
    }

    /** "Remove photo": the record has none, and its file goes; the avatar is its tint again, a photo on its way too. */
    fun removePicture() {
        picturing = after(viewModelScope, picturing) { parts.instances.setPicture(instanceId, null) }
    }

    /** "Unpair" in the dialog: the app asks the daemon to forget this phone and removes the instance. */
    fun unpair() {
        viewModelScope.launch { parts.unpair(instanceId) }
    }
}

/**
 * [instanceId]'s cache's size into [facts], unless a removal took the files meanwhile: the record went first, and the
 * screen with it. Beside the ViewModel, as detekt holds a class to eleven functions.
 */
private suspend fun measureCache(
    parts: InstanceParts,
    instanceId: String,
    facts: MutableStateFlow<ScreenFacts>,
) {
    val measured = parts.profiles.withMediaCache(instanceId, MAIN_PROFILE) { it.size() }
    if (measured is Use.Ran) facts.update { it.copy(cacheBytes = measured.value) }
}

/**
 * [write] launched in [scope] once [before], the photo's write or removal before it, has ended: that one cancelled
 * now, so a photo still on its way never lands after the owner's next word (its part goes with it, AvatarPictures),
 * and waited out, so the two never write at once.
 */
private fun after(
    scope: CoroutineScope,
    before: Job?,
    write: suspend () -> Unit,
): Job {
    before?.cancel()
    return scope.launch {
        before?.join()
        write()
    }
}

/** [session]'s state and diagnostics, and the candidate its connection went over while it is connected. */
private fun watch(session: Session): Flow<Watched> =
    combine(session.state, session.diagnostics, session.lastSuccessful) { state, diagnostics, last ->
        Watched(state, diagnostics, live = last.takeIf { state is SessionState.Connected })
    }

/**
 * Whether [uri]'s photo was made [instanceId]'s avatar picture file; if not, logged by the exception's class alone, as
 * the platform's and the provider's words can name the URI whole, its path among it, which another app picks. A
 * provider's failure is what the binder carries back from it, the other app's; a cancellation, an
 * IllegalStateException too, goes on.
 */
private suspend fun madePicture(
    parts: InstanceParts,
    instanceId: String,
    uri: String,
): Boolean =
    try {
        parts.instances.pictures.write(instanceId, parts.photo(uri))
        true
    } catch (unreadable: IOException) {
        parts.log("A photo could not be made the avatar: ${unreadable.javaClass.simpleName}", null)
        false
    } catch (refused: SecurityException) {
        parts.log("A photo was refused, or its grant is gone: ${refused.javaClass.simpleName}", null)
        false
    } catch (expected: RuntimeException) {
        currentCoroutineContext().ensureActive()
        parts.log("A photo's provider failed: ${expected.javaClass.simpleName}", null)
        false
    }
