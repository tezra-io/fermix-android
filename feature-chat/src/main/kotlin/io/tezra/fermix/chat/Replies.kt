package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.MAX_HEADER_BYTES

/**
 * The most a reply's quote holds, in code points as withSharedWords counts them, its line breaks and [QUOTE_MARK]
 * among them: enough to give the model the message back whole, as one it no longer holds must come back with the
 * question (the owner, 2026-10-12: up to 2,000 characters). The design names none. [QUOTE_MAX_BYTES] holds the quote
 * to the frame as well, which a script past one byte a character reaches first.
 */
const val QUOTE_MAX_CHARS = 2_000

/**
 * The most a reply's quote weighs in `msg.text` as a frame carries it (escapedSize): each line after its "> ", the line
 * breaks and the blank line that ends the quote, as JSON escapes them in UTF-8. Half a frame's header
 * ([MAX_HEADER_BYTES]), so the question keeps the other half less the `msg`'s own fields, some 1,450 bytes with a short
 * profile id, whatever the quoted message's script: 2,000 characters three bytes long weigh 6,000. ASCII prose of
 * [QUOTE_MAX_CHARS] on fifteen lines or fewer fits it whole. The app's number.
 */
const val QUOTE_MAX_BYTES = MAX_HEADER_BYTES / 2

/** The lines of a quote the strip over the composer and the chip in the owner's bubble show, of all those sent. */
const val QUOTE_SHOWN_LINES = 3

/** What ends a quote's last line where lines or characters were left out, and the third shown line when more went. */
const val QUOTE_MARK = "…"

/** A line's leading quote markers: up to three spaces, a `>` and the one space after it, as many as there are. */
private val QUOTE_MARKERS = Regex("^(?: {0,3}> ?)+")

/**
 * The words a reply quotes (step 1 of the owner's reply, 2026-10-11): the quoted message's [lines], each one line of
 * words and none blank, the last ending in [QUOTE_MARK] when the bound [cut] a line or a character out.
 */
data class ReplyQuote(
    val lines: List<String>,
    val cut: Boolean,
) {
    init {
        require(lines.isNotEmpty()) { "a quote holds a line" }
        require(lines.none { it.isBlank() || '\n' in it }) { "a quote's lines are words, one line each" }
    }
}

/**
 * A text that starts with a quote, as [withQuote] writes one: the quote's [lines], their markers gone, and the words
 * after the blank line that ends it ([rest]), the owner's question.
 */
data class LeadingQuote(
    val lines: List<String>,
    val rest: String,
)

/**
 * A reply as the strip over the composer draws it: [to], the name it says, the Fermix's for the agent's message and
 * none for the owner's own; the [lines] it shows (shownLines); and the [revision] of the reply, which moves as each
 * reply starts, so the field takes the focus each time.
 */
data class ReplyUi(
    val to: String?,
    val lines: List<String>,
    val revision: Int,
)

/**
 * The quote of a message's [text]: its lines, CR LF read as LF, after a leading quote is dropped (an owner's reply
 * quotes its question, never the context it carried, leadingQuote), each line's quote markers stripped, repeated, so a
 * quote that is itself quoted stays flat, and its blank lines dropped; the agent's markdown otherwise as written, as
 * the agent reads its own markup. Every line goes, up to [QUOTE_MAX_CHARS] and [QUOTE_MAX_BYTES] in all (cutLines).
 * None for a text with no words.
 */
fun quoteOf(text: String): ReplyQuote? {
    val body = leadingQuote(text)?.rest ?: text
    val lines =
        body
            .lineSequence()
            .map { it.replaceFirst(QUOTE_MARKERS, "") }
            .filter { it.isNotBlank() }
    return cutLines(lines)
}

/** A quote's size in its two bounds' measures: its code points and its bytes in a frame. */
private data class QuoteSize(
    val points: Int,
    val bytes: Int,
) {
    fun fits(room: QuoteSize): Boolean = points <= room.points && bytes <= room.bytes

    operator fun plus(other: QuoteSize): QuoteSize = QuoteSize(points + other.points, bytes + other.bytes)
}

/** The most a quote holds, in code points and in bytes. */
private val QUOTE_ROOM = QuoteSize(QUOTE_MAX_CHARS, QUOTE_MAX_BYTES)

/**
 * A quote of no lines, so that adding each line's [lineSize] gives the quote's: no line break before the first line,
 * and the blank line's that ends the quote.
 */
private val NO_LINES = QuoteSize(-1, escapedSize("\n"))

/** What [QUOTE_MARK] adds to a quote's last line. */
private val MARK_SIZE = QuoteSize(QUOTE_MARK.codePointCount(0, QUOTE_MARK.length), escapedSize(QUOTE_MARK))

/** What [line] adds to a quote: its code points and the break after it; its "> ", itself and that break escaped. */
private fun lineSize(line: String): QuoteSize =
    QuoteSize(line.codePointCount(0, line.length) + 1, escapedSize("> $line\n"))

/**
 * [lines] within [QUOTE_ROOM], the line breaks between them counted: whole when they fit, else cut (marked); none for
 * no lines. Read lazily, so a long message is read no further than the line past the bound.
 */
private fun cutLines(lines: Sequence<String>): ReplyQuote? {
    val read = mutableListOf<String>()
    val ends = mutableListOf<QuoteSize>()
    // Each line is read once, and the loop stops at the first past the bound.
    for (line in lines) {
        val end = (ends.lastOrNull() ?: NO_LINES) + lineSize(line)
        read += line
        ends += end
        if (!end.fits(QUOTE_ROOM)) break
    }
    if (read.isEmpty()) return null
    return if (ends.last().fits(QUOTE_ROOM)) ReplyQuote(read, cut = false) else marked(read, ends)
}

/**
 * [read]'s lines, the last of them past the bound, with [ends] the size of the quote up to each line's end, cut to
 * [QUOTE_ROOM] with the mark: at the end of the last line that fits whole with it, or the first line cut on a
 * character's edge when none does.
 */
private fun marked(
    read: List<String>,
    ends: List<QuoteSize>,
): ReplyQuote {
    val room = QuoteSize(QUOTE_ROOM.points - MARK_SIZE.points, QUOTE_ROOM.bytes - MARK_SIZE.bytes)
    val whole = ends.count { it.fits(room) }
    if (whole == 0) return ReplyQuote(listOf(firstCut(read.first(), room) + QUOTE_MARK), cut = true)
    val kept = read.take(whole)
    return ReplyQuote(kept.dropLast(1) + (kept.last() + QUOTE_MARK), cut = true)
}

/**
 * The most of [line], the quote's first, that fits [room], cut on a character's edge. Each character is weighed once,
 * and the loop ends at the first past the room: at most [QUOTE_MAX_CHARS] of them, as each adds a code point.
 */
private fun firstCut(
    line: String,
    room: QuoteSize,
): String {
    var size = NO_LINES + lineSize("")
    var end = 0
    while (end < line.length) {
        val next = line.offsetByCodePoints(end, 1)
        val more = size + QuoteSize(1, escapedSize(line.substring(end, next)))
        if (!more.fits(room)) break
        size = more
        end = next
    }
    return line.substring(0, end)
}

/**
 * The text a reply sends (step 1, while the engine takes no reference): [quote]'s lines each after "> ", joined by LF,
 * a blank line, then [words] trimmed, which markdown reads as a blockquote and a paragraph. The one shape the daemon
 * sees, made in one place (ChatComposer's send, through composedOf).
 */
fun withQuote(
    quote: ReplyQuote,
    words: String,
): String {
    val question = words.trim()
    require(question.isNotEmpty()) { "a reply asks something" }
    return quote.lines.joinToString("\n") { "> $it" } + "\n\n" + question
}

/**
 * The quote a [text] starts with, as [withQuote] writes one: the lines from its start while each starts with `>`, a
 * space after it optional, their markers stripped and the blank ones left out, ending at a blank line, and the words
 * after it. None when the text does not start with `>`, when the lines run on into words with no blank line, when no
 * words follow (a text that is only a quote is the owner's own words, drawn as words), or when the quote holds none.
 */
fun leadingQuote(text: String): LeadingQuote? {
    if (!text.startsWith(">")) return null
    val lines = text.lines()
    val quoted = lines.takeWhile { it.startsWith(">") }
    val rest = lines.drop(quoted.size + 1).joinToString("\n").trim()
    val ends = lines.getOrNull(quoted.size)?.isBlank() == true
    val words = quoted.map { it.removePrefix(">").removePrefix(" ") }.filter { it.isNotBlank() }
    return if (!ends || rest.isEmpty() || words.isEmpty()) null else LeadingQuote(words, rest)
}

/**
 * The lines of a quote that the strip and the chip show: the first [QUOTE_SHOWN_LINES] of [lines], the last shown
 * ending in [QUOTE_MARK] when more were sent.
 */
fun shownLines(lines: List<String>): List<String> {
    if (lines.size <= QUOTE_SHOWN_LINES) return lines
    val shown = lines.take(QUOTE_SHOWN_LINES)
    return shown.dropLast(1) + (shown.last() + QUOTE_MARK)
}

/** Whether [message] takes a reply: it has words to quote (quoteOf) and is sealed, as a streaming bubble is not. */
fun takesReply(message: ShownMessage): Boolean = !message.streaming && quoteOf(message.text) != null

/** [draft] as the strip draws it, to [title] for the agent's message and to "your message" for the owner's. */
fun replyUiOf(
    draft: ReplyDraft,
    title: String,
): ReplyUi = ReplyUi(title.takeIf { draft.sender == Sender.Agent }, shownLines(draft.quote.lines), draft.revision)
