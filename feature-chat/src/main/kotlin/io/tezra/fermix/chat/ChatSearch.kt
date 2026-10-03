package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.MAX_QUERY_SCALARS
import io.tezra.fermix.session.OneShot
import io.tezra.fermix.session.isAnswerRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long after the owner stops typing the search runs (design section 13.7). */
const val SEARCH_DEBOUNCE_MS = 300L

/** The cached rows the local search reads at most: the local index has no pages. */
internal const val LOCAL_HITS = 200

/**
 * Search as the screen shows it (design section 13.7): the [query]; the list or the stepping [mode]; the
 * [chip]; whether the hits are the phone's cache alone, under the pinned line ([cachedOnly]); the hits, newest
 * first; whether they are on their way ([searching]); whether the daemon has an older page ([more]); whether the
 * daemon's search, or its older page, came to nothing while it is up ([failed], with "Try again"); and in the
 * chat, the hit stepped to ([step]).
 */
data class SearchUi(
    val query: String = "",
    val mode: SearchMode = SearchMode.LIST,
    val chip: SearchChip = SearchChip.ALL,
    val cachedOnly: Boolean = false,
    val hits: List<ShownHit> = emptyList(),
    val searching: Boolean = false,
    val more: Boolean = false,
    val step: Int = 0,
    val failed: Boolean = false,
) {
    /** Whether the daemon's older page may be asked for: it has one, and none is on its way or failed. */
    val olderAskable: Boolean get() = more && !searching && !failed
}

/**
 * The chat's search (design section 13.7, D24): [SEARCH_DEBOUNCE_MS] after the owner stops typing, the hits
 * [SearchPages] finds, the daemon's or the cache's. A hit picked opens the stepping mode at it, and every step
 * [jump]s the chat to its row. The query, at most the 256 scalar values a search takes, goes to the daemon and
 * the phone's index and nowhere else: nothing here logs it.
 */
class ChatSearch(
    session: StateFlow<ChatSession?>,
    store: ChatStore,
    private val scope: CoroutineScope,
    daemonSearches: () -> Boolean,
    private val jump: (ULong) -> Unit,
    log: (String, Throwable?) -> Unit,
) {
    private val shown = MutableStateFlow<SearchUi?>(null)
    private val pages = SearchPages(session, store, daemonSearches, shown, log)
    private var running: Job? = null

    /** Search, none while it is closed. */
    val state: StateFlow<SearchUi?> = shown.asStateFlow()

    fun open() {
        if (shown.value == null) shown.value = SearchUi(cachedOnly = !pages.daemonSearches())
    }

    fun close() {
        running?.cancel()
        shown.value = null
    }

    /** The query as the owner typed it, cut to what a search takes: it runs once they stop. */
    fun query(text: String) {
        val now = shown.value ?: return
        shown.value = now.copy(query = boundedQuery(text))
        restart(debounce = true)
    }

    fun chip(chip: SearchChip) {
        val now = shown.value ?: return
        shown.value = now.copy(chip = chip)
        restart(debounce = false)
    }

    /** The list reached its end: the daemon's older page, when it has one and none is on its way or failed. */
    fun more() {
        val now = shown.value ?: return
        val before = pages.nextBefore
        if (!now.olderAskable || before == null) return
        shown.value = now.copy(searching = true)
        running = scope.launch { pages.olderPage(now.query.trim(), before) }
    }

    /** "Try again" once the daemon's search failed: its first page again, or the older page that failed. */
    fun retry() {
        val now = shown.value ?: return
        if (!now.failed) return
        shown.value = now.copy(failed = false)
        if (now.hits.isEmpty()) restart(debounce = false) else more()
    }

    /** Hit [index] picked from the list: the chat, stepping from it. */
    fun pick(index: Int) {
        val now = shown.value ?: return
        val hit = now.hits.getOrNull(index) ?: return
        shown.value = now.copy(mode = SearchMode.IN_CHAT, step = index)
        jump(hit.serverSeq)
    }

    /** ▲ ([older]) or ▼ in the chat: the next hit that way, the older page asked for at the loaded end. */
    fun step(older: Boolean) {
        val now = shown.value ?: return
        val to = if (older) now.step + 1 else now.step - 1
        if (older && to > now.hits.lastIndex) more()
        val hit = now.hits.getOrNull(to) ?: return
        shown.value = now.copy(step = to)
        jump(hit.serverSeq)
    }

    /** Back: from the chat to the list, and from the list out of search. */
    fun back() {
        val now = shown.value ?: return
        if (now.mode == SearchMode.IN_CHAT) shown.value = now.copy(mode = SearchMode.LIST) else close()
    }

    private fun restart(debounce: Boolean) {
        running?.cancel()
        running =
            scope.launch {
                if (debounce) delay(SEARCH_DEBOUNCE_MS)
                val now = shown.value ?: return@launch
                pages.run(now.query.trim(), now.chip)
            }
    }
}

/**
 * The hits for a query, into [shown] (design section 13.7, D24): the daemon's index over the whole history
 * while a connection is up and the daemon has `caps.search` ([daemonSearches]), 20 hits a page and the older
 * page before [nextBefore]; otherwise, or once the connection is gone, the local index, under the pinned line.
 * A page the daemon does not give while it is up is told to [log] by how it ended, never by its words, and the
 * list says it failed. The chips filter cached rows only. No hit is ever an approval's answer, whose words held
 * the card's token: the session drops the daemon's (Session.search), and this drops one whose row the cache
 * holds as an answer's.
 */
private class SearchPages(
    private val session: StateFlow<ChatSession?>,
    private val store: ChatStore,
    val daemonSearches: () -> Boolean,
    private val shown: MutableStateFlow<SearchUi?>,
    private val log: (String, Throwable?) -> Unit,
) {
    /** The daemon's cursor for its next older page, none without one. */
    var nextBefore: ULong? = null
        private set

    /** [words] under [chip]: the daemon's first page when it searches, else the cache's hits. */
    suspend fun run(
        words: String,
        chip: SearchChip,
    ) {
        nextBefore = null
        val chat = session.value
        val daemon = chip == SearchChip.ALL && daemonSearches() && chat != null
        shown.update { it?.copy(searching = words.isNotEmpty(), step = 0, failed = false) }
        when {
            words.isEmpty() -> shown.found(emptyList(), cachedOnly = !daemonSearches(), more = false)
            daemon -> firstPage(checkNotNull(chat), words)
            else -> shown.found(cachedHits(store, words, chip), cachedOnly = !daemonSearches(), more = false)
        }
    }

    /**
     * The daemon's first page for [words]; once the connection is gone, the cache's hits under the pinned line,
     * which is then true; and with the connection up and no page, the list says the search failed.
     */
    private suspend fun firstPage(
        chat: ChatSession,
        words: String,
    ) {
        when (val page = chat.search(words, null)) {
            is OneShot.Answered -> {
                nextBefore = page.value.nextBeforeSeq
                shown.found(shownHits(store, page.value), cachedOnly = false, more = nextBefore != null)
            }

            OneShot.Offline, OneShot.Interrupted -> {
                shown.found(cachedHits(store, words, SearchChip.ALL), cachedOnly = true, more = false)
            }

            else -> {
                failed(page)
                shown.update { it?.copy(hits = emptyList(), cachedOnly = false, more = false) }
            }
        }
    }

    /** The daemon's older page before [before], after the hits held; the list says so when it does not come. */
    suspend fun olderPage(
        words: String,
        before: ULong,
    ) {
        val page = session.value?.search(words, before) ?: OneShot.Offline
        if (page !is OneShot.Answered) return failed(page)
        nextBefore = page.value.nextBeforeSeq
        val held = shown.value?.hits.orEmpty()
        shown.found(held + shownHits(store, page.value), cachedOnly = false, more = nextBefore != null)
    }

    /** A page that did not come, told to [log] by how it ended, never by its words; the list says it failed. */
    private fun failed(outcome: OneShot<ServerEvent.SearchResults>) {
        log("A search page did not come: ${outcome::class.simpleName}", null)
        shown.update { it?.copy(searching = false, failed = true) }
    }
}

/** [text] cut to the [MAX_QUERY_SCALARS] scalar values a search takes (design section 7, the `history_search` row). */
internal fun boundedQuery(text: String): String {
    val scalars = text.codePointCount(0, text.length)
    return if (scalars <= MAX_QUERY_SCALARS) text else text.substring(0, text.offsetByCodePoints(0, MAX_QUERY_SCALARS))
}

/** The daemon's hits of [page], but one whose row the cache holds as an approval's answer. */
private suspend fun shownHits(
    store: ChatStore,
    page: ServerEvent.SearchResults,
): List<ShownHit> = page.hits.filterNot { hit -> store.row(hit.serverSeq)?.let(::isAnswerRow) == true }.map(::hitOf)

/** The cache's rows for [words] that pass [chip], an approval's answer never among them. */
private suspend fun cachedHits(
    store: ChatStore,
    words: String,
    chip: SearchChip,
): List<ShownHit> =
    store
        .search(words, LOCAL_HITS)
        .filterNot(::isAnswerRow)
        .filter { passes(it, chip) }
        .map { localHitOf(it, words) }

/** Search with [hits] found, from the cache alone or not, and an older page or not. */
private fun MutableStateFlow<SearchUi?>.found(
    hits: List<ShownHit>,
    cachedOnly: Boolean,
    more: Boolean,
) {
    update { it?.copy(hits = hits, cachedOnly = cachedOnly, more = more, searching = false) }
}
