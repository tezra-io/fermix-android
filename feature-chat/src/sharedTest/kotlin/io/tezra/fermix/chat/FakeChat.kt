package io.tezra.fermix.chat

import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** Connected over the tailnet and caught up: the chat's subtitle reads "Tailscale · 38 ms". */
internal val UP = SessionState.Connected(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true)

/** The phone on a network with no VPN. */
internal val ONLINE = NetworkFacts(defaultNetwork = 1L, false, false, false)

/**
 * A chat's cache as the profile's database keeps it: its rows newest first, its outbox, its read frontier and
 * its own row, each a flow the test moves, and every draft kept, in order ([drafts]).
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

    override suspend fun setDraft(text: String?): Boolean {
        val kept = text?.takeIf { it.isNotBlank() }
        drafts.update { it + kept }
        state.update { it.copy(draft = kept) }
        return true
    }
}

/**
 * A session as the chat uses it, over [store]: a request it takes goes to the outbox, as Session.send's does;
 * [remove] takes an item out while it was never written or was refused. Every call is recorded, in order.
 */
class FakeChatSession(
    private val store: FakeChatStore,
    initial: SessionState = UP,
) : ChatSession {
    override val state = MutableStateFlow(initial)
    override val diagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())

    val sent = MutableStateFlow<List<ClientEvent>>(emptyList())
    val retried = MutableStateFlow<List<Pair<ClientEvent, String>>>(emptyList())
    val stopped = MutableStateFlow<List<String>>(emptyList())
    val read = MutableStateFlow<List<ULong>>(emptyList())
    val older = MutableStateFlow<List<ULong>>(emptyList())
    val removed = MutableStateFlow<List<String>>(emptyList())

    /** Whether a connection is up for what is sent once and never queued: Stop and an older page's pull. */
    val connected = MutableStateFlow(true)

    override suspend fun send(request: ClientEvent): Boolean {
        sent.update { it + request }
        store.outbox.update { it + OutboxItem(request) }
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
        if (connected.value) older.update { it + beforeSeq }
        return connected.value
    }

    override suspend fun markRead(upToSeq: ULong): Boolean {
        read.update { it + upToSeq }
        return true
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

/** What the chat told its log, in order. */
class FakeLog {
    val lines = MutableStateFlow<List<String>>(emptyList())

    val log: (String, Throwable?) -> Unit = { message, _ -> lines.update { it + message } }
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
        zone = { UTC },
    )
}
