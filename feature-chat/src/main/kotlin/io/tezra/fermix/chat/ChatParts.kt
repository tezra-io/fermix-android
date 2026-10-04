package io.tezra.fermix.chat

import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.Use
import io.tezra.fermix.data.countFrom
import io.tezra.fermix.data.oldest
import io.tezra.fermix.data.releaseStagedUploads
import io.tezra.fermix.data.row
import io.tezra.fermix.data.voiceDraft
import io.tezra.fermix.data.withStagedUploads
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.ApprovalAnswer
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.FetchedMedia
import io.tezra.fermix.session.OneShot
import io.tezra.fermix.session.OutboxAttachment
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.UploadProgress
import io.tezra.fermix.session.answerApproval
import io.tezra.fermix.session.fetchMedia
import io.tezra.fermix.session.pullModels
import io.tezra.fermix.session.search
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.ZoneId

/**
 * A chat's session as the screen uses it: core-session's Session for the app ([SessionChat]), a fake for the
 * tests. A request it takes is in the outbox when the call returns; one it cannot take, since the session
 * ended, returns false and leaves the field as it was.
 */
interface ChatSession : ChatCalls {
    val state: StateFlow<SessionState>
    val diagnostics: StateFlow<List<Diagnostic>>

    /** Each attachment's upload by its `attach_id`, for its bubble's ring and line (Session.uploads). */
    val uploads: StateFlow<Map<String, UploadProgress>>

    /** [request], with the [attachments] its `msg` uploads first, into the outbox (Session.send). */
    suspend fun send(
        request: ClientEvent,
        attachments: List<OutboxAttachment> = emptyList(),
    ): Boolean

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

/** What the chat's cards and controls ask of its session (Session's one-shots and approval answers). */
interface ChatCalls {
    /** The owner's answer to an approval card, its route sent by the session (Session.answerApproval). */
    suspend fun answerApproval(
        approvalId: String,
        approve: Boolean,
    ): ApprovalAnswer

    /** The daemon's full-text search, a page of hits before [beforeSeq] when given (Session.search). */
    suspend fun search(
        query: String,
        beforeSeq: ULong?,
    ): OneShot<ServerEvent.SearchResults>

    /** Every `models` page of one pull (Session.pullModels). */
    suspend fun pullModels(): OneShot<List<ModelEntry>>

    /** The blob [ref] into [into], through the daemon alone, its first chunk to [firstChunk] (Session.fetchMedia). */
    suspend fun fetchMedia(
        ref: String,
        into: File,
        firstChunk: (ByteArray) -> Unit = {},
    ): OneShot<FetchedMedia>
}

/**
 * The app's [ChatSession]: [session]'s calls, of which one that comes as the session ends is refused
 * (SessionCore.requireOpen); that refusal is told to [log] and returned as false, since the next session takes
 * the chat's requests and the screen keeps what the owner typed. Any other failure is thrown. Its calls are
 * [SessionCalls].
 */
class SessionChat(
    private val session: Session,
    private val log: (String, Throwable?) -> Unit,
) : ChatSession,
    ChatCalls by SessionCalls(session, log) {
    override val state: StateFlow<SessionState> get() = session.state
    override val diagnostics: StateFlow<List<Diagnostic>> get() = session.diagnostics
    override val uploads: StateFlow<Map<String, UploadProgress>> get() = session.uploads

    override suspend fun send(
        request: ClientEvent,
        attachments: List<OutboxAttachment>,
    ): Boolean = session.unlessEnded("send", log) { session.send(request, attachments) } != null

    override suspend fun retry(
        failed: ClientEvent,
        newClientMsgId: String,
    ): Boolean = session.unlessEnded("retry", log) { session.retry(failed, newClientMsgId) } != null

    override suspend fun stop(clientMsgId: String): Boolean =
        session.unlessEnded("stop", log) { session.stop(clientMsgId) } == true

    override suspend fun loadOlder(beforeSeq: ULong): Boolean = session.loadOlder(beforeSeq)

    override suspend fun markRead(upToSeq: ULong): Boolean =
        session.unlessEnded("markRead", log) { session.markRead(upToSeq) } != null

    override suspend fun remove(clientMsgId: String): Boolean =
        session.unlessEnded("remove", log) { session.remove(clientMsgId) } == true

    override fun equals(other: Any?): Boolean = other is SessionChat && other.session === session

    override fun hashCode(): Int = System.identityHashCode(session)
}

/** [session]'s one-shots and approval answers, each refused as the session ends as SessionChat's calls are. */
internal class SessionCalls(
    private val session: Session,
    private val log: (String, Throwable?) -> Unit,
) : ChatCalls {
    override suspend fun answerApproval(
        approvalId: String,
        approve: Boolean,
    ): ApprovalAnswer =
        session.unlessEnded("answerApproval", log) { session.answerApproval(approvalId, approve) }
            ?: ApprovalAnswer.NotShown

    override suspend fun search(
        query: String,
        beforeSeq: ULong?,
    ): OneShot<ServerEvent.SearchResults> =
        session.unlessEnded("search", log) { session.search(query, beforeSeq) } ?: OneShot.Offline

    override suspend fun pullModels(): OneShot<List<ModelEntry>> =
        session.unlessEnded("pullModels", log) { session.pullModels() } ?: OneShot.Offline

    override suspend fun fetchMedia(
        ref: String,
        into: File,
        firstChunk: (ByteArray) -> Unit,
    ): OneShot<FetchedMedia> =
        session.unlessEnded("fetchMedia", log) { session.fetchMedia(ref, into, firstChunk) } ?: OneShot.Offline
}

/**
 * [block], a call of this session, unless it ended: a call that comes as it ends is refused
 * (SessionCore.requireOpen), which is told to [log] as [call]'s and is none; any other failure is thrown.
 */
private suspend fun <T : Any> Session.unlessEnded(
    call: String,
    log: (String, Throwable?) -> Unit,
    block: suspend () -> T,
): T? {
    if (state.value is SessionState.Ended) return null
    return try {
        block()
    } catch (refused: IllegalStateException) {
        if (state.value !is SessionState.Ended) throw refused
        log("$call came as the session ended", refused)
        null
    }
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

    /**
     * The local full-text search (design section 13.7): the cached rows holding every word of [query], newest
     * first, at most [limit]; none once the chat is gone.
     */
    suspend fun search(
        query: String,
        limit: Int,
    ): List<TimelineRow>

    /** How many cached rows are at [serverSeq] or after it: how far back the list reaches to hold it. */
    suspend fun countFrom(serverSeq: ULong): Int

    /** The cached row [serverSeq], none when the cache does not hold it. */
    suspend fun row(serverSeq: ULong): TimelineRow?

    /** The oldest row the cache holds, none when it holds none or the chat is gone. */
    suspend fun oldest(): ULong?

    /**
     * The bytes of the blob [sha256] in the media cache, read while the cache is in use, never its file, which
     * a removal or an eviction may take once that use ends; none when it does not hold it.
     */
    suspend fun media(sha256: String): ByteArray?

    /** Keeps [fetched], the blob [sha256], in the media cache, which checks its digest: whether it was kept. */
    suspend fun keepMedia(
        fetched: File,
        sha256: String,
    ): Boolean
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

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<TimelineRow> =
        (
            profiles.withDatabase(
                instanceId,
                profileId,
            ) { it.timeline().search(query, limit) } as? Use.Ran
        )?.value.orEmpty()

    override suspend fun countFrom(serverSeq: ULong): Int =
        (profiles.withDatabase(instanceId, profileId) { it.timeline().countFrom(serverSeq) } as? Use.Ran)?.value ?: 0

    override suspend fun row(serverSeq: ULong): TimelineRow? =
        (profiles.withDatabase(instanceId, profileId) { it.timeline().row(serverSeq) } as? Use.Ran)?.value

    override suspend fun oldest(): ULong? =
        (profiles.withDatabase(instanceId, profileId) { it.timeline().oldest() } as? Use.Ran)?.value

    // ProfileDatabases runs a media cache's block on its queries dispatcher, off the caller's thread.
    override suspend fun media(sha256: String): ByteArray? =
        (profiles.withMediaCache(instanceId, profileId) { it.get(sha256)?.readBytes() } as? Use.Ran)?.value

    override suspend fun keepMedia(
        fetched: File,
        sha256: String,
    ): Boolean {
        val kept =
            profiles.withMediaCache(instanceId, profileId) { cache ->
                fetched.inputStream().use { cache.put(it, sha256) }
            }
        return kept is Use.Ran
    }
}

/**
 * A chat's files beside its database (design section 8.5): each attachment's file staged until its item leaves the
 * outbox, and a cached blob copied out to a file a player or another app opens. The profile's for the app, a fake
 * for the tests.
 */
interface ChatFiles {
    /** Moves [file] in as [attachId]'s staged source: the path the outbox item names; none once the chat is gone. */
    suspend fun stage(
        file: File,
        attachId: String,
    ): String?

    /**
     * Deletes each of [paths] the chat staged for a send its session never took, unless an outbox item names it;
     * nothing once the chat is gone, its staged files with it.
     */
    suspend fun release(paths: List<String>)

    /** Copies the cached blob [sha256] into [into]: whether the media cache held it. */
    suspend fun export(
        sha256: String,
        into: File,
    ): Boolean

    /**
     * The file the chat's voice note records into and its unsent draft stays in, beside its staged uploads, so a
     * draft outlives the chat and the process (design section 13.6, "Drafts persist per chat"); none once the chat
     * is gone, its draft with it.
     */
    suspend fun voiceDraft(): File?
}

/**
 * The app's [ChatFiles]: [instanceId]'s [profileId] in [profiles], staged through ProfileDatabases' staged uploads
 * and copied out of its media cache, each a use a removal waits for; its voice draft in the profile's directory.
 */
class RoomChatFiles(
    private val profiles: ProfileDatabases,
    private val instanceId: String,
    private val profileId: String,
) : ChatFiles {
    override suspend fun stage(
        file: File,
        attachId: String,
    ): String? =
        (profiles.withStagedUploads(instanceId, profileId) { it.stage(file, attachId).path } as? Use.Ran)?.value

    override suspend fun release(paths: List<String>) {
        profiles.releaseStagedUploads(instanceId, profileId, paths)
    }

    override suspend fun export(
        sha256: String,
        into: File,
    ): Boolean {
        val copied =
            profiles.withMediaCache(instanceId, profileId) { cache ->
                cache.get(sha256)?.copyTo(into, overwrite = true) != null
            }
        return (copied as? Use.Ran)?.value == true
    }

    override suspend fun voiceDraft(): File? = (profiles.voiceDraft(instanceId, profileId) as? Use.Ran)?.value
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
 * asked and could not have ([log]), the owner's zone, and where a blob is fetched or an attachment made before
 * the media cache or the outbox takes it ([scratch]). [files], [media], [clip], [recorder] and [player] are the
 * phone's for attachments and voice notes (design section 8.5), fakes in the tests; [io] is where their file work
 * runs.
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
    val files: ChatFiles,
    val media: MediaPipeline,
    val clip: ChatClip,
    val recorder: VoiceRecorder,
    val player: VoicePlayer,
    val zone: () -> ZoneId = ZoneId::systemDefault,
    val scratch: () -> File = { File.createTempFile("fetch", null) },
    val io: CoroutineDispatcher = Dispatchers.IO,
)
