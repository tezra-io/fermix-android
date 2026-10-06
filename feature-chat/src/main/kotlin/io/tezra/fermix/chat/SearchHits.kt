package io.tezra.fermix.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.SearchHit
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.linkPreviewsOf

/** How search shows its hits (design section 13.7): a list, or stepping through them in the chat itself. */
enum class SearchMode { LIST, IN_CHAT }

/** The chips over the hits (design section 13.7): they filter cached rows only, the daemon's index being text. */
enum class SearchChip { ALL, MEDIA, FILES, LINKS }

/** A hit's words around the first match, at most this many characters before it. */
private const val EXCERPT_BEFORE = 40

/** A hit's words at most. */
private const val EXCERPT_LENGTH = 160

private const val ELLIPSIS = "…"

private val WHITESPACE = Regex("\\s+")

/** A web address in a row's words, for the Links chip. */
private val WEB_ADDRESS = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

/** The media kinds the Media chip holds; every other attachment is a file. */
private val MEDIA_KINDS = setOf("image", "video", "audio")

/**
 * One hit as search shows it (the canon's `.hit`): whose row, when, its [excerpt], and the spans of it that
 * matched, as [excerpt]'s own character ranges.
 */
data class ShownHit(
    val serverSeq: ULong,
    val sender: Sender,
    val wallMs: Long?,
    val excerpt: String,
    val marks: List<IntRange>,
)

/**
 * A daemon's hit (`search_results.hits[]`): its ranges count Unicode scalar values (design section 7, the
 * `history_search` row), turned into the excerpt's own; one past its end is cut at it.
 */
fun hitOf(hit: SearchHit): ShownHit {
    val excerpt = hit.excerpt
    val scalars = excerpt.codePointCount(0, excerpt.length)
    val marks =
        hit.ranges.mapNotNull { range ->
            val start = range.start.coerceAtMost(scalars)
            val end = (range.start + range.length).coerceAtMost(scalars)
            if (end <= start) return@mapNotNull null
            excerpt.offsetByCodePoints(0, start) until excerpt.offsetByCodePoints(0, end)
        }
    val sender = if (hit.role == USER_ROLE) Sender.User else Sender.Agent
    return ShownHit(hit.serverSeq, sender, parseInstant(hit.ts)?.toEpochMilli(), excerpt, marks)
}

/**
 * A cached row the local search found for [query] (design section 13.7, offline): its words around the first
 * place a word of the query starts, and every place one does, as the index matches a word or its start.
 */
fun localHitOf(
    row: TimelineRow,
    query: String,
): ShownHit {
    val text = rowText(row).replace(WHITESPACE, " ").trim()
    val words = query.split(WHITESPACE).filter { it.isNotEmpty() }
    val first = words.mapNotNull { word -> text.indexOf(word, ignoreCase = true).takeIf { it >= 0 } }.minOrNull() ?: 0
    val start = (first - EXCERPT_BEFORE).coerceAtLeast(0)
    val end = (start + EXCERPT_LENGTH).coerceAtMost(text.length)
    val lead = if (start > 0) ELLIPSIS else ""
    val tail = if (end < text.length) ELLIPSIS else ""
    val excerpt = lead + text.substring(start, end) + tail
    val message = row as? TimelineRow.Message
    val sender = if (message?.message?.role == USER_ROLE) Sender.User else Sender.Agent
    return ShownHit(row.serverSeq, sender, message?.let(::wallOf), excerpt, marksOf(excerpt, words))
}

/**
 * What search marks in the chat while it steps through it (design section 13.7; the canon's in-chat frame,
 * `<mark>timeout</mark>` in the bubble stepped to): the row of the hit stepped to, and the query's words.
 */
data class InChatMarks(
    val seq: ULong,
    val words: List<String>,
)

/** The marks while [search] steps through the chat at a hit, its query holding words; none otherwise. */
fun inChatMarksOf(search: SearchUi?): InChatMarks? {
    val stepping = search?.takeIf { it.mode == SearchMode.IN_CHAT } ?: return null
    val hit = stepping.hits.getOrNull(stepping.step)
    val words = stepping.query.split(WHITESPACE).filter { it.isNotEmpty() }
    return if (hit == null || words.isEmpty()) null else InChatMarks(hit.serverSeq, words)
}

/** [text] with each place one of [words] occurs, ignoring case, in [style]. */
internal fun withMarks(
    text: AnnotatedString,
    words: List<String>,
    style: SpanStyle,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        marksOf(text.text, words).forEach { addStyle(style, it.first, it.last + 1) }
    }

/** Every place in [excerpt] one of [words] occurs, ignoring case, in order. */
internal fun marksOf(
    excerpt: String,
    words: List<String>,
): List<IntRange> = words.filter { it.isNotEmpty() }.flatMap { occurrences(excerpt, it) }.sortedBy { it.first }

/** Each place [word] occurs in [text]: every look starts past the last, so there are fewer than its characters. */
private fun occurrences(
    text: String,
    word: String,
): List<IntRange> {
    val found = mutableListOf<IntRange>()
    var at = text.indexOf(word, 0, ignoreCase = true)
    while (at >= 0) {
        found += at until at + word.length
        at = text.indexOf(word, at + word.length, ignoreCase = true)
    }
    return found
}

/**
 * Whether [row] passes [chip] (design section 13.7): All every row; Media a row with an image, a video or a
 * voice note; Files one with any other attachment; Links one with a link preview or a web address in its words.
 */
fun passes(
    row: TimelineRow,
    chip: SearchChip,
): Boolean {
    val refs = (row as? TimelineRow.Message)?.message?.mediaRefs.orEmpty()
    return when (chip) {
        SearchChip.ALL -> true
        SearchChip.MEDIA -> refs.any { it.kind in MEDIA_KINDS }
        SearchChip.FILES -> refs.any { it.kind !in MEDIA_KINDS }
        SearchChip.LINKS -> linkPreviewsOf(row).isNotEmpty() || WEB_ADDRESS.containsMatchIn(rowText(row))
    }
}
