package io.tezra.fermix.chat

import io.tezra.fermix.session.TimelineRow
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** The leaves whose text is words: text, code, punctuation the parser splits out, autolinks and raw HTML as written. */
private val WORD_LEAVES: Set<IElementType> =
    setOf(
        MarkdownTokenTypes.TEXT,
        MarkdownTokenTypes.CODE_LINE,
        MarkdownTokenTypes.CODE_FENCE_CONTENT,
        MarkdownTokenTypes.HTML_BLOCK_CONTENT,
        MarkdownTokenTypes.HTML_TAG,
        MarkdownTokenTypes.SINGLE_QUOTE,
        MarkdownTokenTypes.DOUBLE_QUOTE,
        MarkdownTokenTypes.LPAREN,
        MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LT,
        MarkdownTokenTypes.GT,
        MarkdownTokenTypes.COLON,
        MarkdownTokenTypes.EXCLAMATION_MARK,
        MarkdownTokenTypes.EMPH,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.ESCAPED_BACKTICKS,
        MarkdownTokenTypes.AUTOLINK,
        MarkdownTokenTypes.EMAIL_AUTOLINK,
        MarkdownTokenTypes.URL,
        MarkdownTokenTypes.BAD_CHARACTER,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.HARD_LINE_BREAK,
        GFMTokenTypes.GFM_AUTOLINK,
        GFMTokenTypes.TILDE,
        GFMTokenTypes.DOLLAR,
    )

/** The parts of the syntax that say where a link goes or how it is spelt, never what it says. */
private val SYNTAX_PARTS: Set<IElementType> =
    setOf(
        MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.LINK_TITLE,
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownTokenTypes.FENCE_LANG,
    )

/** A leaf type that is markup in these composites, and text anywhere else ("2 * 3" keeps its star). */
private val MARKUP_IN: Map<IElementType, Set<IElementType>> =
    mapOf(
        MarkdownTokenTypes.EMPH to setOf(MarkdownElementTypes.EMPH, MarkdownElementTypes.STRONG),
        MarkdownTokenTypes.BACKTICK to setOf(MarkdownElementTypes.CODE_SPAN),
        GFMTokenTypes.TILDE to setOf(GFMElementTypes.STRIKETHROUGH),
        MarkdownTokenTypes.LBRACKET to setOf(MarkdownElementTypes.LINK_TEXT, MarkdownElementTypes.LINK_LABEL),
        MarkdownTokenTypes.RBRACKET to setOf(MarkdownElementTypes.LINK_TEXT, MarkdownElementTypes.LINK_LABEL),
        MarkdownTokenTypes.EXCLAMATION_MARK to setOf(MarkdownElementTypes.IMAGE),
        MarkdownTokenTypes.LPAREN to setOf(MarkdownElementTypes.INLINE_LINK),
        MarkdownTokenTypes.RPAREN to setOf(MarkdownElementTypes.INLINE_LINK),
        MarkdownTokenTypes.LT to setOf(MarkdownElementTypes.AUTOLINK),
        MarkdownTokenTypes.GT to setOf(MarkdownElementTypes.AUTOLINK),
    )

/** The blocks, and a table's cells, whose words a space parts from the next one's. */
private val BLOCKS: Set<IElementType> =
    setOf(
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
        MarkdownElementTypes.LIST_ITEM,
        MarkdownElementTypes.BLOCK_QUOTE,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.HTML_BLOCK,
        GFMElementTypes.HEADER,
        GFMElementTypes.ROW,
        GFMTokenTypes.CELL,
    )

/** Where code is: a backslash there is the code's own. */
private val CODE_PARENTS: Set<IElementType> =
    setOf(MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK)

/** A backslash escape of ASCII punctuation (CommonMark 2.4). */
private val ESCAPE = Regex("""\\([!-/:-@\[-`{-~])""")

private val SPACES = Regex("""\s+""")

/** A step of the walk: a node to read under its parent, or a space where a block or a cell ends ([BLOCKS]). */
private sealed interface Step {
    data class Read(
        val node: ASTNode,
        val parent: ASTNode?,
    ) : Step

    data object Space : Step
}

/**
 * A row's words on one line, as the Chats row shows its last message (design section 9.4): the owner's as
 * typed, as their bubble shows them, and the agent's markdown as its plain words (plainWords).
 */
fun rowWords(row: TimelineRow): String =
    when (row) {
        is TimelineRow.Message -> {
            if (row.message.role == USER_ROLE) row.message.content else plainWords(row.message.content)
        }

        is TimelineRow.Reply -> {
            plainWords(row.text)
        }
    }

/**
 * The plain words of the daemon's [markdown] on one line, as the Chats row shows a last message (design
 * section 9.4): the parse the bubble's renderer reads (JetBrains' GFM parser), reduced to its text, with no
 * `**`, backticks, heading or list markers, and a link's words without where it goes. Raw HTML stays as
 * written, as the bubble shows it.
 */
fun plainWords(markdown: String): String {
    val tree = markdownTree(markdown)
    val words = StringBuilder()
    val steps = ArrayDeque<Step>()
    steps.addLast(Step.Read(tree, null))
    // Each node is read once, and a block adds one space: the walk ends within twice the tree's size.
    while (steps.isNotEmpty()) {
        when (val step = steps.removeLast()) {
            Step.Space -> words.append(' ')
            is Step.Read -> read(step, markdown, words, steps)
        }
    }
    return words.toString().replace(SPACES, " ").trim()
}

private fun read(
    step: Step.Read,
    markdown: String,
    words: StringBuilder,
    steps: ArrayDeque<Step>,
) {
    val node = step.node
    if (node.type in SYNTAX_PARTS || isReferenceLabel(node, step.parent)) return
    if (node.children.isEmpty()) {
        words.append(leafWords(node, step.parent, markdown))
        return
    }
    if (node.type in BLOCKS) steps.addLast(Step.Space)
    node.children.asReversed().forEach { steps.addLast(Step.Read(it, node)) }
}

/** `[words][label]`'s label, which names a definition, never what the link says. */
private fun isReferenceLabel(
    node: ASTNode,
    parent: ASTNode?,
): Boolean = node.type == MarkdownElementTypes.LINK_LABEL && parent?.type == MarkdownElementTypes.FULL_REFERENCE_LINK

private fun leafWords(
    leaf: ASTNode,
    parent: ASTNode?,
    markdown: String,
): String {
    val markup = MARKUP_IN[leaf.type]?.contains(parent?.type) == true
    if (leaf.type !in WORD_LEAVES || markup) return ""
    val text = markdown.substring(leaf.startOffset, leaf.endOffset)
    return when {
        leaf.type == MarkdownTokenTypes.ESCAPED_BACKTICKS -> text.replace("\\`", "`")
        leaf.type == MarkdownTokenTypes.TEXT && parent?.type !in CODE_PARENTS -> text.replace(ESCAPE, "$1")
        else -> text
    }
}
