package io.tezra.fermix.chat

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/**
 * A part of an answer as the chat draws it (design section 8.3; the visual canon's chat, where a fence is a
 * card grouped under its bubble): prose in a bubble, and each top-level code fence and table as a card of its
 * own. [start] is where it begins in the answer, which keys it while the answer streams.
 */
sealed interface Segment {
    val start: Int

    data class Prose(
        override val start: Int,
        val markdown: String,
    ) : Segment

    data class Code(
        override val start: Int,
        val info: FenceInfo,
        val code: String,
    ) : Segment

    data class Table(
        override val start: Int,
        val table: TableCells,
    ) : Segment
}

/** A table as its card draws it (design section 13.5): the header's cells and each row's, as plain words. */
data class TableCells(
    val header: List<String>,
    val rows: List<List<String>>,
)

/** The parser the chat reads markdown with, GitHub's flavour as the renderer reads it. */
internal fun markdownTree(markdown: String): ASTNode {
    val parser = MarkdownParser(GFMFlavourDescriptor(), false, CancellationToken.NonCancellable)
    // The CharSequence overload: the String one is deprecated.
    val source: CharSequence = markdown
    return parser.buildMarkdownTreeFromString(source)
}

/**
 * [markdown] as its parts, in order: each top-level table, and each top-level fence once it is closed, is a
 * card; what lies between is prose. While [streaming], an open fence stays in the prose, where the bubble shows
 * "code…" until it closes; a sealed answer's open fence runs to its end, as CommonMark reads it, and is a card.
 */
fun segmentsOf(
    markdown: String,
    streaming: Boolean,
): List<Segment> {
    val segments = mutableListOf<Segment>()
    var prose = 0
    for (node in markdownTree(markdown).children) {
        val card = cardOf(node, markdown, streaming) ?: continue
        val between = markdown.substring(prose, node.startOffset)
        if (between.isNotBlank()) segments += Segment.Prose(prose, between)
        segments += card
        prose = node.endOffset
    }
    val rest = markdown.substring(prose)
    if (rest.isNotBlank()) segments += Segment.Prose(prose, rest)
    return segments
}

/**
 * How many line ends back the held text is settled: its last line may be half written, and the two before it
 * decide where a card ends.
 */
private const val SETTLING_LINE_ENDS = 3

/**
 * [markdown]'s parts (segmentsOf, while [streaming]), read again only past what is settled. [before] are the
 * parts of [markdown]'s first [held] characters, read the same way. A part that starts two whole lines before
 * the held text's last line stands with every part before it: the lines that ended the part before it, a
 * table's next line or a fence's closing one, were whole, and appended text never reaches back over whole lines
 * into a block they ended. The parser reads the rest again, from where that part starts. With no such part past
 * the first, as in a prose-only answer, nothing is settled and the whole text is read again: the renderer's own
 * streaming state is what re-parses only the unstable tail of a bubble's prose (design section 8.3).
 */
fun segmentsAfter(
    before: List<Segment>,
    held: Int,
    markdown: String,
    streaming: Boolean,
): List<Segment> {
    require(held in 0..markdown.length) { "the held text is a prefix of the answer" }
    val settled = settledUpTo(markdown, held)
    val cut = before.indexOfLast { it.start <= settled }
    if (cut <= 0) return segmentsOf(markdown, streaming)
    val from = before[cut].start
    val tail = segmentsOf(markdown.substring(from), streaming).map { it.shiftedBy(from) }
    return before.take(cut) + tail
}

/** Where the third line from the end of [markdown]'s first [held] characters starts, or -1 with fewer lines. */
private fun settledUpTo(
    markdown: String,
    held: Int,
): Int {
    var lineEnd = held
    repeat(SETTLING_LINE_ENDS) {
        if (lineEnd <= 0) return -1
        lineEnd = markdown.lastIndexOf('\n', lineEnd - 1)
    }
    return lineEnd + 1
}

private fun Segment.shiftedBy(offset: Int): Segment =
    when (this) {
        is Segment.Prose -> copy(start = start + offset)
        is Segment.Code -> copy(start = start + offset)
        is Segment.Table -> copy(start = start + offset)
    }

private fun cardOf(
    node: ASTNode,
    markdown: String,
    streaming: Boolean,
): Segment? =
    when {
        node.type == GFMElementTypes.TABLE -> Segment.Table(node.startOffset, tableOf(node, markdown))
        node.type != MarkdownElementTypes.CODE_FENCE -> null
        streaming && !closed(node) -> null
        else -> Segment.Code(node.startOffset, fenceInfo(fenceLanguage(node, markdown)), fenceCode(node, markdown))
    }

/** Whether a fence has its closing line: one without it is still being written. */
fun closed(fence: ASTNode): Boolean = fence.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }

/** A fence's info string, empty when it has none. */
fun fenceLanguage(
    fence: ASTNode,
    markdown: String,
): String =
    fence.children
        .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.let { markdown.substring(it.startOffset, it.endOffset) }
        .orEmpty()

/** A fence's code: its lines between the fences, as written. */
fun fenceCode(
    fence: ASTNode,
    markdown: String,
): String {
    val lines = fence.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
    return if (lines.isEmpty()) "" else markdown.substring(lines.first().startOffset, lines.last().endOffset)
}

/** A GFM table's cells, each as its plain words (plainWords); a row shorter than the header is padded. */
fun tableOf(
    table: ASTNode,
    markdown: String,
): TableCells {
    val cellsOf = { row: ASTNode ->
        row.children
            .filter { it.type == GFMTokenTypes.CELL }
            .map { plainWords(markdown.substring(it.startOffset, it.endOffset)) }
    }
    val header =
        table.children
            .firstOrNull { it.type == GFMElementTypes.HEADER }
            ?.let(cellsOf)
            .orEmpty()
    val rows =
        table.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
            val cells = cellsOf(row)
            cells + List((header.size - cells.size).coerceAtLeast(0)) { "" }
        }
    return TableCells(header, rows)
}
