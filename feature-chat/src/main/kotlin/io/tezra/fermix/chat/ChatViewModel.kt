package io.tezra.fermix.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.tezra.fermix.data.Instance
import io.tezra.fermix.instance.Link
import io.tezra.fermix.instance.linkOf
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.UploadProgress
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Rows the list asks of the cache at a time. */
const val PAGE_ROWS = 60

/** Rows the list holds at most: past them the owner searches (Task 12) rather than scrolls. */
const val MAX_ROWS = 3_000

/** What the list is made of, besides the facts of the bar. */
private data class Held(
    val rows: List<TimelineRow>,
    val outbox: List<OutboxItem>,
    val bridged: List<OutboxItem>,
    val live: ChatLive,
    val unreadAt: ULong?,
)

/** What the list reads besides its rows: what was sent, what was seen, an older page asked for, the uploads. */
private data class Asked(
    val sent: Map<String, ClientEvent>,
    val seen: ULong?,
    val older: OlderAsked?,
    val uploads: Map<String, UploadProgress>,
)

/** What was decided as the chat opened: the row the unread divider stands above, none for no divider. */
private data class Opened(
    val unreadAt: ULong?,
)

/** The list's makings once the cache returned its first page and the divider was chosen from it; none before. */
private fun heldOf(
    rows: List<TimelineRow>?,
    outbox: List<OutboxItem>,
    bridged: List<OutboxItem>,
    live: ChatLive,
    opened: Opened?,
): Held? = if (rows == null || opened == null) null else Held(rows, outbox, bridged, live, opened.unreadAt)

/**
 * The daemon's older page asked for, before [beforeSeq], while the fold said [older] of where it starts: it
 * has landed once the cache holds a row before it, the fold says anew, or the link it was asked over is down.
 */
private data class OlderAsked(
    val beforeSeq: ULong,
    val older: Older,
)

/**
 * One chat (design sections 8 and 13.5 to 13.7): its [state], built by chatScreenState from the record, the
 * session's link, the phone's network, the cache's rows newest first and its outbox, and the app's fold of the
 * session's events; its [composer]; the [requests] the owner's hand makes. There is no state until the cache
 * has returned its first page, so the first one the screen draws already holds the rows, and the answers among
 * them that arrived before it opened. The unread divider's row is chosen once, from that first page, empty or
 * not, and the read frontier as the chat opened, and does not move while it is open; reaching the bottom marks
 * the newest row read once the session took it, never backwards. The screen
 * reports the newest row it lists while it is on screen ([listed]), so the announcer answers ON_SCREEN only
 * for a row the list holds. The tray's own files are kept in its [saved] state (ChatAttach).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val parts: ChatParts,
    saved: SavedStateHandle,
) : ViewModel() {
    private val session = parts.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val record: StateFlow<Instance?> =
        parts.records
            .map { all ->
                all.find { it.id == parts.instanceId }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val limit = MutableStateFlow(PAGE_ROWS)
    private val opened = MutableStateFlow<Opened?>(null)
    private val seenUpTo = MutableStateFlow<ULong?>(null)
    private val bridged = MutableStateFlow<List<OutboxItem>>(emptyList())
    private val olderAsked = MutableStateFlow<OlderAsked?>(null)

    // None until the cache returns its first page: an empty list before it would be a chat with no history.
    private val rows: StateFlow<List<TimelineRow>?> =
        limit
            .flatMapLatest {
                parts.store.newest(it)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val outbox = parts.store.pending().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val frontier = parts.store.readFrontier().stateIn(viewModelScope, SharingStarted.Eagerly, 0uL)
    private val live = parts.live.stateIn(viewModelScope, SharingStarted.Eagerly, ChatLive())
    private val link: StateFlow<Link> =
        session.flatMapLatest(::linkFlowOf).stateIn(viewModelScope, SharingStarted.Eagerly, Link.NotOpen)
    private var readUpTo = 0uL

    val requests = ChatRequests(session, viewModelScope, parts.profileId, parts.newId, parts.log)
    val approvals = ChatApprovals(session, viewModelScope, parts.log)
    val models = ChatModels(requests, session, viewModelScope, parts.profileId, parts.newId, parts.log)
    val blobs = ChatBlobs(session, parts)
    val jumps = ChatJumps(parts.store, session, limit, viewModelScope, parts.log)
    val search =
        ChatSearch(
            session,
            parts.store,
            viewModelScope,
            { daemonSearches(link.value, record.value) },
            jumps::to,
            parts.log,
        )
    val composer =
        ChatComposer(
            requests,
            parts.store,
            viewModelScope,
            parts.background,
            {
                record.value
                    ?.caps
                    ?.commands
                    .orEmpty()
            },
        )

    // None until the chat's record is read; a record whose daemon sent no caps holds nothing to a limit.
    private val maxBytes: StateFlow<Long?> =
        record
            .map { read -> read?.let { it.caps?.maxMediaBytes ?: Long.MAX_VALUE } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val attach = ChatAttach(parts, requests, composer, viewModelScope, maxBytes, saved)
    val voice = ChatVoice(parts, requests, viewModelScope)
    val playback = ChatPlayback(parts, viewModelScope)
    val notes = ChatNotes(voice, playback, blobs, parts.io)
    private val uploads: StateFlow<Map<String, UploadProgress>> =
        session
            .flatMapLatest { it?.uploads ?: flowOf(emptyMap()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** What the attachments and voice notes show; whether the microphone is off is the screen's to say. */
    val media: StateFlow<MediaUi> =
        combine(
            attach.ui,
            voice.ui,
            playback.playing,
            blobs.colours,
            combine(voice.bars, playback.bars, voice.lengths, playback.lengths, ::Notes),
        ) { attached, recording, playing, colours, notes ->
            MediaUi(attached, recording, playing, colours, notes.read + notes.bars, notes.played + notes.recorded)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MediaUi())

    val state: StateFlow<ChatScreenState?> =
        combine(
            factsOf(record, link, delayedBanner(link, parts.network), live),
            combine(rows, outbox, bridged, live, opened, ::heldOf),
            link,
            combine(requests.sent, seenUpTo, olderAsked, uploads, ::Asked),
            models.switchPending,
        ) { facts, held, link, asked, switching ->
            if (facts == null || held == null) return@combine null
            val transcripts = record.value?.caps?.transcripts == true
            val inputs = inputsOf(parts, held, link is Link.Up, asked).copy(transcripts = transcripts)
            val state = chatScreenState(facts, inputs, asked.seen, limit.value, loadingOlder = asked.older != null)
            state.copy(switchPending = switching && state.turnRuns)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // The frontier the chat opened on, then the unread divider's row from the first page the cache returned,
        // empty or not: a row that lands while the chat is open gets no divider.
        viewModelScope.launch {
            val frontierAtOpen = parts.store.readFrontier().firstOrNull() ?: return@launch
            seenUpTo.value = frontierAtOpen
            val firstPage = rows.filterNotNull().first()
            opened.value = Opened(unreadAnchor(firstPage, frontierAtOpen))
        }
        viewModelScope.launch { bridge() }
        // The older page asked for lets go once it landed (OlderAsked), so its skeleton goes.
        viewModelScope.launch {
            combine(olderAsked, rows.filterNotNull(), live, link, ::OlderFacts).collect {
                if (olderLanded(it)) olderAsked.value = null
            }
        }
        // A model picked during a turn has switched once no turn runs (design section 8.6, released by turn_done).
        viewModelScope.launch { live.collect { if (it.turns.none { turn -> turn.live }) models.turnsEnded() } }
    }

    /**
     * The list reached its bottom: the owner has seen every row it holds, and the newest is read once the
     * session takes it. With no session yet, or one that ended, nothing is marked, and the screen asks again as
     * a session comes (ListEffects).
     */
    fun reachedBottom() {
        val newest = state.value?.newestSeq ?: return
        seenUpTo.update { seen -> maxOf(seen ?: 0uL, newest) }
        val before = readUpTo
        val chat = session.value
        if (chat == null || newest <= maxOf(before, frontier.value)) return
        readUpTo = newest
        viewModelScope.launch {
            val marked = chat.markRead(newest)
            if (!marked && readUpTo == newest) readUpTo = before
        }
    }

    /**
     * The list reached its top: more of the cache while it holds more, and once it is read to its start, the
     * daemon's older page before the oldest row it holds, or where the last page said the next one starts.
     * The rows asked of the cache grow only once the daemon takes the pull; a pull it cannot take now (no
     * connection, or one on its way) leaves them, and the screen asks again as the link comes up.
     */
    fun reachedTop() {
        val older = state.value?.older == true
        val held = rows.value
        val asking = olderAsked.value != null || limit.value >= MAX_ROWS
        if (!older || held == null || asking) return
        val fold = live.value.older
        val before = olderBefore(fold, held)
        val chat = session.value
        when {
            held.size >= limit.value -> {
                limit.update { minOf(it + PAGE_ROWS, MAX_ROWS) }
            }

            before != null && chat != null -> {
                viewModelScope.launch {
                    if (!chat.loadOlder(before)) return@launch
                    olderAsked.value = OlderAsked(before, fold)
                    limit.update { minOf(it + PAGE_ROWS, MAX_ROWS) }
                }
            }
        }
    }

    /** The screen on screen with [listedUpTo] the newest row its list holds, or none while it is not. */
    fun listed(listedUpTo: ULong?) {
        parts.presence.report(listedUpTo)
    }

    /**
     * Edit on a queued bubble: its words back in the field and its attachments in the tray (design section 13.6),
     * once the item left the outbox unsent.
     */
    fun edit(message: ShownMessage) {
        val id = requireNotNull(message.clientMsgId) { "only an outbox item is edited" }
        viewModelScope.launch {
            val left = attach.takeBack(message.attachments) { requests.withdraw(id) }
            if (left) composer.replace(message.text) else stayed(parts.log, "Edit", id)
        }
    }

    /**
     * Another app's share landing here (design section 13.6, "Share into Fermix"): its items into the tray up to the
     * ten, each copied into a file of the chat's own as it lands, and its words at the end of the draft, cut to what
     * one message carries. Nothing is sent: only the owner's Send sends.
     */
    fun share(shared: Shared) {
        attach.add(shared.uris, PickedFrom.SHARE)
        val words = shared.words ?: return
        viewModelScope.launch {
            composer.restored.first { it }
            val field = composer.field.value.text
            val landed = withSharedWords(field, words, parts.profileId)
            if (landed != field) composer.replace(landed)
            composer.keep()
            val cut =
                when {
                    landed == field -> "None of a share's words fit what one message carries; the draft is as it was"
                    !landed.endsWith(words) -> "A share's words were cut to what one message carries"
                    else -> null
                }
            if (cut != null) parts.log(cut, null)
        }
    }

    /** Remove on a queued bubble, and "Remove from outbox" on a refused one. */
    fun remove(message: ShownMessage) {
        val id = requireNotNull(message.clientMsgId) { "only an outbox item is removed" }
        viewModelScope.launch { if (!requests.withdraw(id)) stayed(parts.log, "Remove", id) }
    }

    /** Info on [message] (design section 13.7), from what this process saw of its turn. */
    fun info(message: ShownMessage): ShownInfo = infoOf(message, live.value)

    /** The words for the model that wrote a bubble, by the chat's caps. */
    fun modelOf(route: Route): String = modelLabel(route, record.value?.caps)

    /** The monotonic clock the working indicator reads, the one the app's fold of the session's events read. */
    fun nowMono(): Long = parts.clock.monoMs()

    override fun onCleared() {
        parts.presence.report(null)
        composer.keep()
        attach.release()
        voice.stop()
        playback.stop()
    }

    /** Bridges the owner's messages `accepted` took out of the outbox to their rows (bridgedAfter). */
    private suspend fun bridge() {
        var before = emptyList<OutboxItem>()
        combine(outbox, rows.filterNotNull(), requests.withdrawn, ::Triple).collect { (now, held, withdrawn) ->
            bridged.value = bridgedAfter(bridged.value, before, now, held, withdrawn)
            before = now
        }
    }
}

/**
 * [action] on [clientMsgId] came once its frame was first written to a socket, or with no session: the request
 * may have reached the daemon, so it stays in the outbox (design section 13.6), its bubble with it; told to [log].
 */
private fun stayed(
    log: (String, Throwable?) -> Unit,
    action: String,
    clientMsgId: String,
) {
    log("$action of $clientMsgId came once its frame was written; it stays in the outbox", null)
}

/** Where the daemon's next older page starts: where the last page said, or else before the oldest row held. */
private fun olderBefore(
    fold: Older,
    held: List<TimelineRow>,
): ULong? = (fold as? Older.Before)?.seq ?: held.minOfOrNull { it.serverSeq }?.takeIf { it > 1uL }

/**
 * The voice notes' bars and lengths, by cache name: those this process recorded ([bars], [recorded]), which win,
 * and those the player read from a note's file ([read], [played]).
 */
private data class Notes(
    val bars: Map<String, List<Float>>,
    val read: Map<String, List<Float>>,
    val recorded: Map<String, Long>,
    val played: Map<String, Long>,
)

/** What says whether an older page asked for has landed. */
private data class OlderFacts(
    val asked: OlderAsked?,
    val rows: List<TimelineRow>,
    val live: ChatLive,
    val link: Link,
)

private fun olderLanded(facts: OlderFacts): Boolean {
    val asked = facts.asked ?: return false
    val held = facts.rows.any { it.serverSeq < asked.beforeSeq }
    return held || facts.live.older != asked.older || facts.link !is Link.Up
}

/** Whether the daemon's index answers a search now: a connection is up, and the daemon has `caps.search`. */
private fun daemonSearches(
    link: Link,
    record: Instance?,
): Boolean = link is Link.Up && record?.caps?.search == true

private fun linkFlowOf(chat: ChatSession?): Flow<Link> =
    if (chat == null) flowOf(Link.NotOpen) else combine(chat.state, chat.diagnostics, ::linkOf)

/**
 * The banner once its condition has held [BANNER_DELAY_MS], and none at once when it clears; none as the chat
 * opens, so the screen does not wait on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun delayedBanner(
    link: Flow<Link>,
    network: Flow<NetworkFacts>,
): Flow<Banner?> =
    combine(link, network, ::bannerOf)
        .distinctUntilChanged()
        .transformLatest { shown ->
            if (shown != null) delay(BANNER_DELAY_MS)
            emit(shown)
        }.onStart { emit(null) }

/** The bar's facts and the banner, none once the record is gone. */
private fun factsOf(
    record: Flow<Instance?>,
    link: Flow<Link>,
    banner: Flow<Banner?>,
    live: Flow<ChatLive>,
): Flow<ChatFacts?> =
    combine(record, link, banner, live) { instance, shownLink, shownBanner, chatLive ->
        instance?.let {
            val working = chatLive.turns.any { turn -> turn.live }
            ChatFacts(ChatHeader(it, shownLink, working), shownBanner)
        }
    }

private fun inputsOf(
    parts: ChatParts,
    held: Held,
    connected: Boolean,
    asked: Asked,
): ChatInputs =
    ChatInputs(
        rows = held.rows,
        outbox = held.outbox,
        bridged = held.bridged,
        live = held.live,
        connected = connected,
        unreadAt = held.unreadAt,
        requests = asked.sent,
        profileId = parts.profileId,
        nowWall = parts.clock.wallMs(),
        zone = parts.zone(),
        uploads = asked.uploads,
    )
