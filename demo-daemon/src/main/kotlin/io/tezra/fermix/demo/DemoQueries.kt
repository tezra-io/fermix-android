package io.tezra.fermix.demo

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.MAX_RAW_BYTES
import io.tezra.fermix.protocol.MatchRange
import io.tezra.fermix.protocol.RequestOutcome
import io.tezra.fermix.protocol.SearchHit
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.turnIdOf
import java.text.Normalizer

/** A history page's rows hold at most this many bytes of words (PROTOCOL.md "History pages"). */
private const val PAGE_BYTES = 256 * 1024

/** A `history_pull`'s rows at most: the wire's bound on `limit`. */
private const val MAX_PAGE_ROWS = 200

/** A search's limit at most (design section 7: 1 to 50). */
private const val MAX_HITS = 50

/** The most characters an excerpt keeps before a match, and after it, cut back to the nearest word's edge. */
private const val EXCERPT_BEFORE = 40
private const val EXCERPT_AFTER = 80

/** What marks a cut excerpt's end, as the engine's FTS5 snippet marks one. */
private const val ELLIPSIS = "…"

/**
 * What a phone asks of a demo Fermix and is answered at once, from what the Fermix keeps: history pages forward
 * and backward, a search over every row, a blob as its chunks, how requests ended, the rows changed in place, and
 * the model list.
 */
internal class DemoQueries(
    private val connection: DemoConnection,
) {
    private val home = connection.home
    private val parts = connection.parts

    fun answer(event: ClientEvent) {
        when (event) {
            is ClientEvent.HistoryPull -> {
                connection.send(page(event))
            }

            is ClientEvent.HistorySearch -> {
                connection.send(search(event))
            }

            is ClientEvent.MediaFetch -> {
                fetch(event.ref)
            }

            is ClientEvent.RequestStatus -> {
                connection.send(
                    ServerEvent.RequestStatusPage(event.clientMsgIds.mapNotNull(::outcome)),
                )
            }

            is ClientEvent.MutationsPull -> {
                connection.send(mutations(event))
            }

            ClientEvent.ModelsPull -> {
                connection.send(modelsOf(home))
            }

            else -> {
                error("${nameOf(event)} is not a query")
            }
        }
    }

    /** A page after `after_seq`, oldest first, or the page just before `before_seq` (design section 7). */
    private fun page(pull: ClientEvent.HistoryPull): ServerEvent.HistoryPage {
        val limit = pull.limit.coerceIn(1, MAX_PAGE_ROWS)
        val before = pull.beforeSeq
        if (before == null) {
            val after = pull.afterSeq ?: 0uL
            val rows = bounded(home.rows.filter { it.serverSeq > after }, limit)
            return ServerEvent.HistoryPage(MAIN, rows, rows.lastOrNull()?.serverSeq ?: after, home.head)
        }
        val older = home.rows.filter { it.serverSeq < before }
        val rows = bounded(older.asReversed(), limit).asReversed()
        val more = rows.isNotEmpty() && older.first().serverSeq < rows.first().serverSeq
        val last = rows.lastOrNull()?.serverSeq ?: (before - 1uL)
        return ServerEvent.HistoryPage(MAIN, rows, last, home.head, if (more) rows.first().serverSeq else null)
    }

    /** [rows] in their order up to [limit] and the page's bytes, never fewer than one when there is one. */
    private fun bounded(
        rows: List<HistoryMessage>,
        limit: Int,
    ): List<HistoryMessage> {
        var bytes = 0
        return rows.take(limit).filterIndexed { index, row ->
            bytes += row.content.encodeToByteArray().size
            index == 0 || bytes <= PAGE_BYTES
        }
    }

    /**
     * The rows the query matches as the engine's index matches them ([hitOf]), newest first, before `before_seq` when
     * it is given: as many as the limit lets in, each an excerpt around its first match with every match in it
     * ranged; `next_before_seq` when more are left.
     */
    private fun search(search: ClientEvent.HistorySearch): ServerEvent.SearchResults {
        val query = search.query.trim()
        val before = search.beforeSeq ?: ULong.MAX_VALUE
        val found =
            home.rows
                .asReversed()
                .filter { it.serverSeq < before }
                .mapNotNull { row -> hitOf(row, query) }
        val hits = found.take(search.limit.coerceIn(1, MAX_HITS))
        val next = if (found.size > hits.size) hits.last().serverSeq else null
        return ServerEvent.SearchResults(MAIN, search.query, hits, next)
    }

    /**
     * Streams the blob [ref] names (PROTOCOL.md "Media downloads"), one chunk a frame, or refuses a ref that no row
     * of this Fermix names.
     */
    private fun fetch(ref: String) {
        val row = home.rows.lastOrNull { names(it, ref) }
        val blob = parts.stored[ref]
        if (row == null ||
            blob == null
        ) {
            return connection.send(ServerEvent.Error("not_found", "no such blob", ref = ref))
        }
        val seq = row.serverSeq
        connection.send(ServerEvent.MediaBegin(ref, seq, blob.kind, blob.mime, blob.size, blob.ref, blob.filename))
        // One chunk's copy at a time: the demo lives in the app's heap.
        val size = blob.size.toInt()
        (0 until size step MAX_RAW_BYTES).forEachIndexed { index, at ->
            connection.sendWithRaw(ServerEvent.MediaChunk(ref, index), blob.slice(at, minOf(at + MAX_RAW_BYTES, size)))
        }
        connection.send(ServerEvent.MediaEnd(ref, blob.ref))
    }

    private fun outcome(clientMsgId: String): RequestOutcome? {
        val claim = home.claims[clientMsgId] ?: return null
        return RequestOutcome(clientMsgId, claim.state, turnIdOf(clientMsgId), claim.resultSeq, claim.error)
    }

    private fun mutations(pull: ClientEvent.MutationsPull): ServerEvent.MutationsPage {
        val after = home.mutations.filter { it.mutationSeq > pull.afterMutationSeq }
        val rows = after.take(pull.limit.coerceAtLeast(1))
        return ServerEvent.MutationsPage(rows, if (after.size > rows.size) rows.last().mutationSeq else null)
    }
}

/** Whether [row] names the blob [ref], among its media or as a preview's image. */
private fun names(
    row: HistoryMessage,
    ref: String,
): Boolean = row.mediaRefs.any { it.ref == ref } || row.linkPreviews.orEmpty().any { it.imageRef == ref }

/** A word as the engine's index tokenizes one (FTS5's unicode61): a run of letters and digits. */
private val WORD = Regex("[\\p{L}\\p{N}]+")

/** The combining marks a decomposed letter carries: its accents, which unicode61 folds away. */
private val ACCENTS = Regex("\\p{Mn}+")

/** A word of a row, where it lies in the row's words, and its folded form, which a query's words are matched to. */
private class Token(
    val start: Int,
    val end: Int,
    val folded: String,
)

/**
 * [row] as a search hit for [query], or none, matched as the engine's index matches (mobile_sql.ex `search`): each
 * whitespace-separated token of the query with a letter or a digit is a phrase of its words, the last one a prefix,
 * and the row holds every phrase, each at a word's start, case and accents folded. The excerpt lies around the
 * first match, its cuts on words' edges, each marked with an ellipsis as the engine's snippet marks them, and every
 * match inside it is ranged, in Unicode scalar values, a prefix's whole word as the engine's highlight ranges it.
 * The engine's snippet takes sixteen tokens around its best match; the demo's takes characters around the first.
 */
internal fun hitOf(
    row: HistoryMessage,
    query: String,
): SearchHit? {
    val phrases = phrasesOf(query)
    val tokens = tokensOf(row.content)
    val matches = phrases.map { matchesOf(tokens, it) }
    if (phrases.isEmpty() || matches.any { it.isEmpty() }) return null
    return hitAround(row, matches.flatten().sortedBy { it.first })
}

/** The query's phrases: each whitespace-separated token's words, folded; a token with no letter or digit has none. */
private fun phrasesOf(query: String): List<List<String>> =
    query
        .split(Regex("\\s+"))
        .map { token -> tokensOf(token).map { it.folded } }
        .filter { it.isNotEmpty() }

private fun tokensOf(words: String): List<Token> =
    WORD.findAll(words).map { Token(it.range.first, it.range.last + 1, fold(it.value)) }.toList()

private fun fold(word: String): String {
    val decomposed = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
    return decomposed.replace(ACCENTS, "")
}

/** Where [phrase] matches [tokens]: its words one after another, the last a prefix, each match's span of the words. */
private fun matchesOf(
    tokens: List<Token>,
    phrase: List<String>,
): List<IntRange> =
    (0..tokens.size - phrase.size)
        .filter { at -> phrase.indices.all { k -> wordMatches(tokens[at + k], phrase, k) } }
        .map { at -> tokens[at].start until tokens[at + phrase.lastIndex].end }

/** Whether [token] is word [k] of [phrase]: the same word, or for the phrase's last one a word it starts. */
private fun wordMatches(
    token: Token,
    phrase: List<String>,
    k: Int,
): Boolean = if (k == phrase.lastIndex) token.folded.startsWith(phrase[k]) else token.folded == phrase[k]

/** The hit on [row] whose excerpt lies around the first of [spans], with every span inside it ranged. */
private fun hitAround(
    row: HistoryMessage,
    spans: List<IntRange>,
): SearchHit {
    val words = row.content
    val first = spans.first()
    val from = wordStartFrom(words, (first.first - EXCERPT_BEFORE).coerceAtLeast(0), first.first)
    val to = wordEndBefore(words, (first.last + 1 + EXCERPT_AFTER).coerceAtMost(words.length), first.last + 1)
    val before = if (from > 0) ELLIPSIS else ""
    val excerpt = before + words.substring(from, to) + if (to < words.length) ELLIPSIS else ""
    val ranges =
        spans
            .filter { it.first >= from && it.last < to }
            .distinct()
            .map { span ->
                val start = before.length + words.codePointCount(from, span.first)
                MatchRange(start, words.codePointCount(span.first, span.last + 1))
            }
    return SearchHit(row.serverSeq, row.role, row.ts, excerpt, ranges)
}

/** The first word's start at or after [index] in [words], never past [limit]: a cut that leaves no word in half. */
private fun wordStartFrom(
    words: String,
    index: Int,
    limit: Int,
): Int {
    if (index == 0 || words[index - 1].isWhitespace()) return index
    val space = (index until limit).firstOrNull { words[it].isWhitespace() }
    return if (space == null) limit else space + 1
}

/** The last word's end at or before [index] in [words], never before [floor]: a cut that leaves no word in half. */
private fun wordEndBefore(
    words: String,
    index: Int,
    floor: Int,
): Int {
    if (index == words.length || words[index].isWhitespace()) return index
    return (index - 1 downTo floor).firstOrNull { words[it].isWhitespace() } ?: floor
}
