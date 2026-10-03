package io.tezra.fermix.chat

import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.Use
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

/**
 * A chat's session as the screen uses it: core-session's Session for the app ([SessionChat]), a fake for the
 * tests. A request it takes is in the outbox when the call returns; one it cannot take, since the session
 * ended, returns false and leaves the field as it was.
 */
interface ChatSession {
    val state: StateFlow<SessionState>
    val diagnostics: StateFlow<List<Diagnostic>>

    suspend fun send(request: ClientEvent): Boolean

    suspend fun retry(
        failed: ClientEvent,
        newClientMsgId: String,
    ): Boolean

    /** Stop, sent once if connected and never queued (Session.stop): whether it went. */
    suspend fun stop(clientMsgId: String): Boolean

    suspend fun loadOlder(beforeSeq: ULong): Boolean

    suspend fun markRead(upToSeq: ULong): Boolean

    suspend fun remove(clientMsgId: String): Boolean
}

/**
 * The app's [ChatSession]: [session]'s calls, of which one that comes as the session ends is refused
 * (SessionCore.requireOpen); that refusal is told to [log] and returned as false, since the next session takes
 * the chat's requests and the screen keeps what the owner typed. Any other failure is thrown.
 */
class SessionChat(
    private val session: Session,
    private val log: (String, Throwable?) -> Unit,
) : ChatSession {
    override val state: StateFlow<SessionState> get() = session.state
    override val diagnostics: StateFlow<List<Diagnostic>> get() = session.diagnostics

    override suspend fun send(request: ClientEvent): Boolean = unlessEnded("send") { session.send(request) } != null

    override suspend fun retry(
        failed: ClientEvent,
        newClientMsgId: String,
    ): Boolean = unlessEnded("retry") { session.retry(failed, newClientMsgId) } != null

    override suspend fun stop(clientMsgId: String): Boolean = unlessEnded("stop") { session.stop(clientMsgId) } == true

    override suspend fun loadOlder(beforeSeq: ULong): Boolean = session.loadOlder(beforeSeq)

    override suspend fun markRead(upToSeq: ULong): Boolean =
        unlessEnded("markRead") { session.markRead(upToSeq) } != null

    override suspend fun remove(clientMsgId: String): Boolean =
        unlessEnded("remove") { session.remove(clientMsgId) } == true

    private suspend fun <T : Any> unlessEnded(
        call: String,
        block: suspend () -> T,
    ): T? {
        if (session.state.value is SessionState.Ended) return null
        return try {
            block()
        } catch (refused: IllegalStateException) {
            if (session.state.value !is SessionState.Ended) throw refused
            log("$call came as the session ended", refused)
            null
        }
    }

    override fun equals(other: Any?): Boolean = other is SessionChat && other.session === session

    override fun hashCode(): Int = System.identityHashCode(session)
}

/** A chat's cached rows, outbox, read frontier and draft: the profile's database for the app, a fake for the tests. */
interface ChatStore {
    /** The newest [limit] rows, newest first, again on every change. */
    fun newest(limit: Int): Flow<List<TimelineRow>>

    /** The outbox, in enqueue order, again on every change. */
    fun pending(): Flow<List<OutboxItem>>

    /** The read frontier, again on every change. */
    fun readFrontier(): Flow<ULong>

    /** The chat's own row: its draft and the host-owned agent's name. */
    fun chat(): Flow<ChatState>

    /** Keeps [text] as the chat's draft, none for a blank one; false when the chat is gone, its draft with it. */
    suspend fun setDraft(text: String?): Boolean
}

/**
 * The app's [ChatStore]: [instanceId]'s [profileId] in [profiles], read through ProfileDatabases.observe, whose
 * flows end once the instance is removed. A draft kept after the removal has no chat to go to ([Use.Gone]).
 */
class RoomChatStore(
    private val profiles: ProfileDatabases,
    private val instanceId: String,
    private val profileId: String,
) : ChatStore {
    override fun newest(limit: Int): Flow<List<TimelineRow>> =
        profiles.observe(instanceId, profileId) { it.timeline().newest(limit) }

    override fun pending(): Flow<List<OutboxItem>> = profiles.observe(instanceId, profileId) { it.pending() }

    override fun readFrontier(): Flow<ULong> = profiles.observe(instanceId, profileId) { it.readFrontier() }

    override fun chat(): Flow<ChatState> = profiles.observe(instanceId, profileId) { it.chat().state() }

    override suspend fun setDraft(text: String?): Boolean =
        profiles.withDatabase(instanceId, profileId) { it.chat().setDraft(text) } is Use.Ran
}

/**
 * The clocks a chat reads: [monoMs] the monotonic one the working indicator and Info's durations count on,
 * shared with the app's fold of the session's events (ChatLive), and [wallMs] the wall clock a bubble's time
 * is read on.
 */
interface ChatClock {
    fun monoMs(): Long

    fun wallMs(): Long
}

/**
 * Where the Chat screen tells the app it is on screen (design section 10): resumed with its window focused,
 * with [listedUpTo] the newest row in its list state, or none when it is not on screen. The app's announcer
 * answers a row ON_SCREEN only once it is listed (PUSH-2: persisted, shown, then acked).
 */
fun interface ChatPresence {
    fun report(listedUpTo: ULong?)
}

/**
 * What a chat runs on, all of it the app's: the [records], [instanceId]'s session while it has one, the app's
 * fold of its events ([live]), its [store], the phone's [network], the [presence] it reports, its [clock],
 * the [background] scope its draft is kept on as it leaves, new client_msg_ids, where it tells what the owner
 * asked and could not have ([log]), and the owner's zone.
 */
data class ChatParts(
    val instanceId: String,
    val profileId: String,
    val records: Flow<List<Instance>>,
    val session: Flow<ChatSession?>,
    val live: Flow<ChatLive>,
    val store: ChatStore,
    val network: Flow<NetworkFacts>,
    val presence: ChatPresence,
    val clock: ChatClock,
    val background: CoroutineScope,
    val newId: () -> String,
    val log: (String, Throwable?) -> Unit,
    val zone: () -> ZoneId = ZoneId::systemDefault,
)
