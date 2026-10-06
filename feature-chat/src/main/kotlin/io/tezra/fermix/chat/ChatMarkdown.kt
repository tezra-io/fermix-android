package io.tezra.fermix.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * The renderer's look in an answer bubble (design sections 8.3 and 13.1): body 16/24 in the ink, headings as
 * bold body, inline code in mono on the hairline, links in the ink, always underlined (the M51 update's 1.3).
 */
@Composable
private fun colorsOf(colors: FermixColors): MarkdownColors =
    markdownColor(
        text = colors.ink,
        codeBackground = Color.Transparent,
        inlineCodeBackground = colors.hairline,
        dividerColor = colors.hairline,
        tableBackground = Color.Transparent,
    )

@Composable
private fun typographyOf(colors: FermixColors): MarkdownTypography {
    val body = FermixType.body.copy(color = colors.ink)
    val heading = body.copy(fontWeight = FontWeight.SemiBold)
    val link = SpanStyle(color = colors.ink, textDecoration = TextDecoration.Underline)
    return markdownTypography(
        h1 = heading,
        h2 = heading,
        h3 = heading,
        h4 = heading,
        h5 = heading,
        h6 = heading,
        text = body,
        code = FermixType.mono,
        inlineCode = FermixType.mono.copy(color = colors.ink),
        quote = body.copy(color = colors.textSecondary),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        textLink = TextLinkStyles(style = link),
        table = body,
    )
}

/** About 6 dp between blocks, as the canon spaces a bubble's paragraphs and lists (a spacer on each side). */
@Composable
private fun padding(): MarkdownPadding =
    markdownPadding(
        block = 3.dp,
        list = 0.dp,
        listItemTop = 0.dp,
        listItemBottom = 0.dp,
        codeBlock = PaddingValues(0.dp),
    )

/**
 * Raw HTML never renders (design section 8.3): an inline tag is its own text, as written, and a markdown image
 * is its source, since the phone fetches nothing a message names. A link that opens nothing (opens, MessageLinks),
 * any but a web address no longer than a link preview's, is its words with no link on them, marked up as the
 * renderer marks a link's words, [codeSpan] on a code span among them; a reference is looked up in [links], the
 * table the renderer looks it up in as it draws it. Everything else is the renderer's.
 */
private class LiteralMarkup(
    codeSpan: SpanStyle,
    private val links: DefinedLinks,
) {
    val annotator: MarkdownAnnotator =
        markdownAnnotator(config = markdownAnnotatorConfig(inlineImageAsBlock = false)) { content, child ->
            drawn(content, child)
        }

    /**
     * How a closed link's words are marked up: as [annotator] marks a message up, so a tag or an image among them
     * stays written, but an autolink among them, at any depth, is its words (autolinkWords), as the words of a link
     * that opens nothing carry no link.
     */
    private val words: AnnotatorSettings =
        DefaultAnnotatorSettings(
            TextLinkStyles(),
            codeSpan,
            markdownAnnotator(config = markdownAnnotatorConfig(inlineImageAsBlock = false)) { content, child ->
                drawnInWords(content, child)
            },
        )

    /** Whether [node], among a closed link's words, was drawn here: an autolink as its words, or as [drawn] says. */
    private fun AnnotatedString.Builder.drawnInWords(
        content: String,
        node: ASTNode,
    ): Boolean {
        if (node.type != MarkdownElementTypes.AUTOLINK) return drawn(content, node)
        buildMarkdownAnnotatedString(content, autolinkWords(node), words)
        return true
    }

    /** Whether [node] was drawn here, as written or as a closed link's words; false leaves it to the renderer. */
    private fun AnnotatedString.Builder.drawn(
        content: String,
        node: ASTNode,
    ): Boolean {
        val written = node.type == MarkdownTokenTypes.HTML_TAG || node.type == MarkdownElementTypes.IMAGE
        val closed = if (written) null else closedWordsOf(node, content)
        if (written) append(node.getTextInNode(content).toString())
        if (closed != null) buildMarkdownAnnotatedString(content, closed, words)
        return written || closed != null
    }

    /** A link's words, when the address the renderer would link them to opens nothing; none for any other node. */
    private fun closedWordsOf(
        node: ASTNode,
        content: String,
    ): List<ASTNode>? =
        when (node.type) {
            MarkdownElementTypes.INLINE_LINK -> {
                closedLinkWords(node, content)
            }

            MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                closedReferenceWords(node, content, links)
            }

            MarkdownElementTypes.AUTOLINK, MarkdownTokenTypes.EMAIL_AUTOLINK, GFMTokenTypes.GFM_AUTOLINK -> {
                closedAutolink(node, content)
            }

            else -> {
                null
            }
        }
}

/**
 * The view configuration a message's prose is drawn under: a link's touch target is its words alone, not the 48 dp
 * Compose grows a smaller target to, which reached into the lines above and below a link, so a tap on the words
 * beside one opened it, and a long-press on them opened it instead of the message's menu (design section 13.7). A
 * link in running text is exempt from a target's minimum size (WCAG 2.5.8, inline), and TalkBack reaches each link
 * on its own; the controls of the cards in the prose are 48 dp tall themselves.
 */
private class InlineLinks(
    base: ViewConfiguration,
) : ViewConfiguration by base {
    override val minimumTouchTargetSize: DpSize get() = DpSize.Zero
}

/**
 * The renderer's parts in a bubble: a fence nested in a list or a quote is a code card, or "code…" while it is
 * still open in a [streaming] answer; a nested table is a table card; an HTML block is its text, as written;
 * any other element no component names (a link's definition) shows nothing. While [streaming], the last
 * paragraph carries the beam cursor after its last words (CursorParagraph), and an open fence after its chip.
 * A paragraph washes each of [marks], the words search marks in the bubble it steps to.
 */
@Composable
private fun componentsOf(
    streaming: Boolean,
    actions: TextActions,
    marks: List<String>,
): MarkdownComponents =
    markdownComponents(
        paragraph = { model -> CursorParagraph(model, streaming && lastBlock(model), marks) },
        codeFence = { model -> NestedFence(model, streaming, actions) },
        table = { model -> TableCard(tableOf(model.node, model.content), FermixShapes.card) },
        image = { model -> Literal(model) },
        custom = { type, model -> if (type == MarkdownElementTypes.HTML_BLOCK) Literal(model) },
    )

/** Whether the model's block is where the answer ends: nothing but blanks follows it. */
private fun lastBlock(model: MarkdownComponentModel): Boolean = model.content.substring(model.node.endOffset).isBlank()

/**
 * A paragraph as the renderer draws one, its style pushed over its annotated words, each of [marks] washed as the
 * canon's `.b mark`; with [cursor], the beam cursor is placed after its last glyph as inline content
 * (cursorContent), so it follows the text as it grows.
 */
@Composable
private fun CursorParagraph(
    model: MarkdownComponentModel,
    cursor: Boolean,
    marks: List<String>,
) {
    val style = model.typography.paragraph
    val settings = annotatorSettings()
    val wash = SpanStyle(background = LocalFermixColors.current.selection)
    val words =
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(content = model.content, node = model.node, annotatorSettings = settings)
            pop()
            if (cursor) appendInlineContent(CURSOR_ID, CURSOR_ALTERNATE)
        }
    val shown = if (marks.isEmpty()) words else withMarks(words, marks, wash)
    MarkdownText(content = shown, node = model.node, style = style, sourceContent = model.content)
}

@Composable
private fun NestedFence(
    model: MarkdownComponentModel,
    streaming: Boolean,
    actions: TextActions,
) {
    if (streaming && !closed(model.node)) {
        CodePlaceholder(cursor = lastBlock(model))
    } else {
        val info = fenceInfo(fenceLanguage(model.node, model.content))
        CodeCard(info, fenceCode(model.node, model.content), "${model.node.startOffset}", actions, FermixShapes.card)
    }
}

@Composable
private fun Literal(model: MarkdownComponentModel) {
    Text(
        model.node.getTextInNode(model.content).toString(),
        style = FermixType.body,
        color = LocalFermixColors.current.ink,
    )
}

/** "code…" while a fence is still open (design section 8.3): a mono chip on the hairline, the [cursor] after it. */
@Composable
internal fun CodePlaceholder(cursor: Boolean) {
    val colors = LocalFermixColors.current
    Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.chat_code_placeholder),
            style = FermixType.mono,
            color = colors.textSecondary,
            modifier =
                Modifier
                    .background(colors.hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        if (cursor) BeamCursor(Modifier.padding(start = 2.dp))
    }
}

/**
 * An answer's prose (MessageProse), under a view configuration that keeps each link's touch target to its words
 * (InlineLinks).
 */
@Composable
internal fun Prose(
    markdown: String,
    streaming: Boolean,
    resets: Int,
    actions: TextActions,
    marks: List<String> = emptyList(),
) {
    val base = LocalViewConfiguration.current
    val inline = remember(base) { InlineLinks(base) }
    CompositionLocalProvider(LocalViewConfiguration provides inline) {
        MessageProse(markdown, streaming, resets, actions, marks)
    }
}

/**
 * An answer's prose: [markdown] as it streams into an append-only state, which starts again when the text was
 * replaced by one that does not extend it ([resets]), or when the part itself no longer extends what the state
 * holds, as when a card was cut out of it; or as it was sealed, parsed at once. A streaming one carries the beam
 * cursor where its text ends (CursorParagraph, CodePlaceholder). Its lines grow by animateContentSize, at once
 * under reduce-motion. Its paragraphs wash [marks], the words search marks while it steps to this bubble. A
 * reference link is looked up in the table its state keeps (DefinedLinks), and the raw HTML and the links that
 * open nothing, a reference looked up in that same table among them, are drawn as LiteralMarkup says.
 */
@Composable
private fun MessageProse(
    markdown: String,
    streaming: Boolean,
    resets: Int,
    actions: TextActions,
    marks: List<String>,
) {
    val colors = LocalFermixColors.current
    val look = colorsOf(colors)
    val padding = padding()
    val typography = typographyOf(colors)
    val components = componentsOf(streaming, actions, marks)
    val reduced = LocalReducedMotion.current
    val animations = markdownAnimations(animateTextSize = { if (reduced) this else animateContentSize() })
    val codeSpan = typography.inlineCode.copy(background = look.inlineCodeBackground).toSpanStyle()
    if (!streaming) {
        // Parsed as it composes: a sealed bubble measures whole on its first frame, so the list's anchor holds.
        val links = remember { DefinedLinks() }
        val sealed = rememberMarkdownState(markdown, referenceLinkHandler = links, immediate = true)
        Markdown(
            sealed,
            look,
            typography,
            Modifier,
            padding,
            annotator = remember(codeSpan, links) { LiteralMarkup(codeSpan, links).annotator },
            components = components,
            animations = animations,
        )
        return
    }
    val cursor = cursorContent()
    var restarts by remember { mutableIntStateOf(0) }
    key(resets, restarts) {
        val links = remember { DefinedLinks() }
        val state = rememberStreamingMarkdownState(referenceLinkHandler = links)
        LaunchedEffect(markdown) {
            val held = state.content
            when {
                !markdown.startsWith(held) -> restarts++
                markdown.length > held.length -> state.append(markdown.substring(held.length))
            }
        }
        Markdown(
            state,
            look,
            typography,
            Modifier,
            padding,
            annotator = remember(codeSpan, links) { LiteralMarkup(codeSpan, links).annotator },
            inlineContent = cursor,
            components = components,
            animations = animations,
        )
    }
}
