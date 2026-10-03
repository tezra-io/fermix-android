package io.tezra.fermix.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Rows past the one jumped to that the list holds as well, so it does not stand at the list's very top. */
private const val JUMP_MARGIN = PAGE_ROWS / 2

/** How often, and how many times, a jump looks for each older page it asked for in the cache: 10 s a page. */
private const val JUMP_POLL_MS = 250L
private const val JUMP_POLLS = 40

/** Older pages a jump pulls at most: the 3,000 rows the list holds ([MAX_ROWS]), 50 a page (Session.loadOlder). */
private const val JUMP_PAGES = 60

/** A jump to row [seq]; [nonce] counts them, so a second jump to one row moves the list again. */
data class Jump(
    val seq: ULong,
    val nonce: Int,
)

/**
 * A search hit's jump (design section 13.7): a row the cache does not hold is loaded with the pages down to it,
 * each the one before the oldest row the cache holds (`history_pull.before_seq`), so the rows between stay whole
 * and the chat's older cursor never passes a gap; one newer than the oldest held, which only the session's
 * catch-up brings, is waited for instead. Then the list grows to hold it, at most [MAX_ROWS], and the screen
 * scrolls to it and pulses it ([jump]). One the daemon cannot load now, or past the rows the list holds, is told
 * to [log].
 */
class ChatJumps(
    private val store: ChatStore,
    private val session: StateFlow<ChatSession?>,
    private val limit: MutableStateFlow<Int>,
    private val scope: CoroutineScope,
    private val log: (String, Throwable?) -> Unit,
) {
    private val asked = MutableStateFlow<Jump?>(null)
    private var running: Job? = null

    /** The latest jump, none before the first. */
    val jump: StateFlow<Jump?> = asked.asStateFlow()

    fun to(seq: ULong) {
        require(seq > 0uL) { "there is no row 0" }
        running?.cancel()
        running =
            scope.launch {
                val held = store.row(seq) != null || loaded(seq)
                if (!held) log("Row $seq could not be loaded to jump to", null)
                val reach = store.countFrom(seq) + JUMP_MARGIN
                if (reach > MAX_ROWS) log("Row $seq is past the $MAX_ROWS rows the list holds", null)
                limit.update { maxOf(it, minOf(reach, MAX_ROWS)) }
                asked.value = Jump(seq, (asked.value?.nonce ?: 0) + 1)
            }
    }

    /**
     * Asks the daemon for older pages, each before the oldest row the cache holds (an empty cache's, the one
     * that ends at [seq]), until the cache holds [seq], [JUMP_PAGES] at most: whether it does. A row above the
     * oldest held, which no older page holds, is waited for as the catch-up brings it ([caughtUp]).
     */
    private suspend fun loaded(seq: ULong): Boolean {
        val oldest = store.oldest()
        if (oldest != null && seq > oldest) return caughtUp(seq)
        var held = false
        var landed = true
        var pages = 0
        while (landed && !held && pages < JUMP_PAGES) {
            landed = pageLanded(store.oldest() ?: (seq + 1u))
            held = store.row(seq) != null
            pages++
        }
        return held
    }

    /**
     * Waits for the cache to hold [seq], a row newer than every one it holds, which the session's forward
     * catch-up after a reconnect brings: [JUMP_POLLS] looks at most, then whether it came.
     */
    private suspend fun caughtUp(seq: ULong): Boolean {
        var held = false
        var polls = 0
        while (!held && polls < JUMP_POLLS) {
            delay(JUMP_POLL_MS)
            held = store.row(seq) != null
            polls++
        }
        return held
    }

    /** Asks for the page before [before] and waits for the cache to hold a row under it: whether it came. */
    private suspend fun pageLanded(before: ULong): Boolean {
        val pulled = session.value?.loadOlder(before) == true
        var landed = false
        var polls = 0
        while (pulled && !landed && polls < JUMP_POLLS) {
            delay(JUMP_POLL_MS)
            landed = (store.oldest() ?: before) < before
            polls++
        }
        return landed
    }
}
