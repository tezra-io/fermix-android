package io.tezra.fermix.chat

import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.APPROVAL_ANSWER_PREFIX
import io.tezra.fermix.session.ApprovalAnswer
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.FetchedMedia
import io.tezra.fermix.session.MAX_QUERY_SCALARS
import io.tezra.fermix.session.OneShot
import io.tezra.fermix.session.OutboxAttachment
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.UploadProgress
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** A blob's chunk on the wire, 60 KiB. */
private const val CHUNK_BYTES = 61_440

/** Connected over the tailnet and caught up: the chat's subtitle reads "Tailscale · 38 ms". */
internal val UP = SessionState.Connected(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true)

/** The phone on a network with no VPN. */
internal val ONLINE = NetworkFacts(defaultNetwork = 1L, false, false, false)

/**
 * A chat's cache as the profile's database keeps it: its rows newest first, its outbox, its read frontier and
 * its own row, each a flow the test moves, and every draft kept, in order ([drafts]); its media cache by
 * SHA-256 ([media]), and the local search, a row holding every word of the query, ignoring case.
 */
class FakeChatStore(
    rows: List<TimelineRow> = emptyList(),
    draft: String? = null,
    frontier: ULong = 0uL,
) : ChatStore {
    val rows = MutableStateFlow(rows.sortedByDescending { it.serverSeq })
    val outbox = MutableStateFlow<List<OutboxItem>>(emptyList())
    val frontier = MutableStateFlow(frontier)
    val state = MutableStateFlow(ChatState(draft = draft, agentName = null, previews = true))
    val drafts = MutableStateFlow<List<String?>>(emptyList())

    override fun newest(limit: Int): Flow<List<TimelineRow>> = rows.map { it.take(limit) }

    override fun pending(): Flow<List<OutboxItem>> = outbox

    override fun readFrontier(): Flow<ULong> = frontier

    override fun chat(): Flow<ChatState> = state

    val media = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    override suspend fun setDraft(text: String?): Boolean {
        val kept = text?.takeIf { it.isNotBlank() }
        drafts.update { it + kept }
        state.update { it.copy(draft = kept) }
        return true
    }

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<TimelineRow> {
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return rows.value.filter { row -> words.all { rowText(row).contains(it, ignoreCase = true) } }.take(limit)
    }

    override suspend fun countFrom(serverSeq: ULong): Int = rows.value.count { it.serverSeq >= serverSeq }

    override suspend fun row(serverSeq: ULong): TimelineRow? = rows.value.find { it.serverSeq == serverSeq }

    override suspend fun oldest(): ULong? = rows.value.minOfOrNull { it.serverSeq }

    override suspend fun media(sha256: String): ByteArray? = media.value[sha256]

    override suspend fun keepMedia(
        fetched: File,
        sha256: String,
    ): Boolean {
        val kept = fetched.readBytes()
        media.update { it + (sha256 to kept) }
        return true
    }
}

/**
 * A session as the chat uses it, over [store]: a request it takes goes to the outbox, as Session.send's does;
 * [remove] takes an item out while it was never written or was refused. Every call is recorded, in order. The
 * one-shots answer only while [connected], as Session's never queue: a search, its query at most
 * [MAX_QUERY_SCALARS] as Session's, with [searchPage] or as [searchFails] says, a pull with [models], a fetch
 * with [blobs]' bytes; an older page's pull runs [onOlder]; an approval's answer goes to the outbox as
 * Session's does, recorded in [answers], its route never seen here.
 */
class FakeChatSession(
    private val store: FakeChatStore,
    initial: SessionState = UP,
) : ChatSession {
    override val state = MutableStateFlow(initial)
    override val diagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())
    override val uploads = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())

    val sent = MutableStateFlow<List<ClientEvent>>(emptyList())
    val retried = MutableStateFlow<List<Pair<ClientEvent, String>>>(emptyList())
    val stopped = MutableStateFlow<List<String>>(emptyList())
    val read = MutableStateFlow<List<ULong>>(emptyList())
    val older = MutableStateFlow<List<ULong>>(emptyList())
    val removed = MutableStateFlow<List<String>>(emptyList())

    /** Whether a connection is up for what is sent once and never queued: Stop and an older page's pull. */
    val connected = MutableStateFlow(true)

    /** Whether a send is taken; false as a session that ends as the request comes refuses it (SessionChat.send). */
    var takes = true

    val answers = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList())
    val searches = MutableStateFlow<List<Pair<String, ULong?>>>(emptyList())
    val pulls = MutableStateFlow(0)
    val fetched = MutableStateFlow<List<String>>(emptyList())

    /** The daemon's page for a query and its before_seq. */
    var searchPage: (String, ULong?) -> ServerEvent.SearchResults = { query, _ ->
        ServerEvent.SearchResults(PROFILE, query, emptyList())
    }
    var models: OneShot<List<ModelEntry>> = OneShot.Answered(emptyList())
    var blobs: Map<String, ByteArray> = emptyMap()

    /** How a search ends instead of with [searchPage]'s page: none while the daemon answers. */
    var searchFails: OneShot<Nothing>? = null

    /** What the daemon does as an older page is asked for before a seq: a test that needs its rows sets it. */
    var onOlder: (ULong) -> Unit = {}

    override suspend fun send(
        request: ClientEvent,
        attachments: List<OutboxAttachment>,
    ): Boolean {
        if (!takes) return false
        sent.update { it + request }
        store.outbox.update { it + OutboxItem(request, attachments = attachments) }
        return true
    }

    override suspend fun retry(
        failed: ClientEvent,
        newClientMsgId: String,
    ): Boolean {
        retried.update { it + (failed to newClientMsgId) }
        return true
    }

    override suspend fun stop(clientMsgId: String): Boolean {
        if (connected.value) stopped.update { it + clientMsgId }
        return connected.value
    }

    override suspend fun loadOlder(beforeSeq: ULong): Boolean {
        if (!connected.value) return false
        older.update { it + beforeSeq }
        onOlder(beforeSeq)
        return true
    }

    override suspend fun markRead(upToSeq: ULong): Boolean {
        read.update { it + upToSeq }
        return true
    }

    override suspend fun answerApproval(
        approvalId: String,
        approve: Boolean,
    ): ApprovalAnswer {
        answers.update { it + (approvalId to approve) }
        return ApprovalAnswer.Sent("$APPROVAL_ANSWER_PREFIX$approvalId:fake")
    }

    override suspend fun search(
        query: String,
        beforeSeq: ULong?,
    ): OneShot<ServerEvent.SearchResults> {
        require(query.codePointCount(0, query.length) <= MAX_QUERY_SCALARS) { "a query past $MAX_QUERY_SCALARS" }
        if (!connected.value) return OneShot.Offline
        searches.update { it + (query to beforeSeq) }
        return searchFails ?: OneShot.Answered(searchPage(query, beforeSeq))
    }

    override suspend fun pullModels(): OneShot<List<ModelEntry>> {
        if (!connected.value) return OneShot.Offline
        pulls.update { it + 1 }
        return models
    }

    override suspend fun fetchMedia(
        ref: String,
        into: File,
        firstChunk: (ByteArray) -> Unit,
    ): OneShot<FetchedMedia> {
        val gone: OneShot<FetchedMedia> = if (connected.value) OneShot.Refused("media_gone") else OneShot.Offline
        val bytes = blobs[ref]?.takeIf { connected.value } ?: return gone
        fetched.update { it + ref }
        if (bytes.isNotEmpty()) firstChunk(bytes.copyOf(minOf(bytes.size, CHUNK_BYTES)))
        into.writeBytes(bytes)
        return OneShot.Answered(FetchedMedia("image", "image/png", bytes.size.toLong(), ref, null))
    }

    override suspend fun remove(clientMsgId: String): Boolean {
        val item = store.outbox.value.find { it.clientMsgId == clientMsgId }
        val removable = item != null && (!item.written || item.failure != null)
        if (removable) {
            store.outbox.update { items -> items.filterNot { it.clientMsgId == clientMsgId } }
            removed.update { it + clientMsgId }
        }
        return removable
    }
}

/** A clock the test moves, from its own thread or the main one: the monotonic one and the wall's, from [MORNING]. */
class FakeChatClock(
    @Volatile var mono: Long = 0L,
    @Volatile var wall: Long = MORNING.toEpochMilli(),
) : ChatClock {
    override fun monoMs(): Long = mono

    override fun wallMs(): Long = wall
}

/** The newest row the screen reported listing, every report in order, none once it left. */
class FakePresence : ChatPresence {
    val reports = MutableStateFlow<List<ULong?>>(emptyList())

    override fun report(listedUpTo: ULong?) {
        reports.update { it + listedUpTo }
    }
}

/** What the chat told its log, in order: each line, and each throwable a line came with ([thrown]). */
class FakeLog {
    val lines = MutableStateFlow<List<String>>(emptyList())
    val thrown = MutableStateFlow<List<Throwable>>(emptyList())

    val log: (String, Throwable?) -> Unit = { message, failure ->
        lines.update { it + message }
        if (failure != null) thrown.update { it + failure }
    }
}

/**
 * The chat of [record] over the fakes, its draft kept on [background], ids "m1", "m2", …: no turn, online, a
 * presence, a clock and a log no test reads; a test that sets them copies the parts with its own.
 */
internal fun fakeParts(
    record: Instance,
    session: FakeChatSession?,
    store: FakeChatStore,
    background: CoroutineScope,
): ChatParts {
    val ids = AtomicInteger(0)
    return ChatParts(
        instanceId = record.id,
        profileId = PROFILE,
        records = MutableStateFlow(listOf(record)),
        session = MutableStateFlow(session),
        live = MutableStateFlow(ChatLive()),
        store = store,
        network = MutableStateFlow(ONLINE),
        presence = FakePresence(),
        clock = FakeChatClock(),
        background = background,
        newId = { "m${ids.incrementAndGet()}" },
        log = FakeLog().log,
        files = FakeChatFiles(store),
        media = FakePipeline(),
        clip = FakeClip(),
        recorder = FakeRecorder(),
        player = FakePlayer(),
        zone = { UTC },
    )
}
