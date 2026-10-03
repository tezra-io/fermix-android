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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
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
import org.intellij.markdown.ast.getTextInNode

/**
 * The renderer's look in an answer bubble (design sections 8.3 and 13.1): body 16/24 in the ink, headings as
 * bold body, inline code in mono on the hairline, links in the accent ink, underlined.
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
    val link = SpanStyle(color = colors.accentInk, textDecoration = TextDecoration.Underline)
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
        quote = body.copy(color = colors.inkSecondary),
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
 * is its source, since the phone fetches nothing a message names; everything else is the renderer's.
 */
private val LITERAL_HTML: MarkdownAnnotator =
    markdownAnnotator(config = markdownAnnotatorConfig(inlineImageAsBlock = false)) { content, child ->
        val literal = child.type == MarkdownTokenTypes.HTML_TAG || child.type == MarkdownElementTypes.IMAGE
        if (literal) append(child.getTextInNode(content).toString())
        literal
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
    val wash = SpanStyle(background = LocalFermixColors.current.accentInk.copy(alpha = MARK_ALPHA))
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
            color = colors.inkSecondary,
            modifier =
                Modifier
                    .background(colors.hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        if (cursor) BeamCursor(Modifier.padding(start = 2.dp))
    }
}

/**
 * An answer's prose: [markdown] as it streams into an append-only state, which starts again when the text was
 * replaced by one that does not extend it ([resets]), or when the part itself no longer extends what the state
 * holds, as when a card was cut out of it; or as it was sealed, parsed at once. A streaming one carries the beam
 * cursor where its text ends (CursorParagraph, CodePlaceholder). Its lines grow by animateContentSize, at once
 * under reduce-motion. Its paragraphs wash [marks], the words search marks while it steps to this bubble.
 */
@Composable
internal fun Prose(
    markdown: String,
    streaming: Boolean,
    resets: Int,
    actions: TextActions,
    marks: List<String> = emptyList(),
) {
    val colors = LocalFermixColors.current
    val look = colorsOf(colors)
    val padding = padding()
    val typography = typographyOf(colors)
    val components = componentsOf(streaming, actions, marks)
    val reduced = LocalReducedMotion.current
    val animations = markdownAnimations(animateTextSize = { if (reduced) this else animateContentSize() })
    if (!streaming) {
        // Parsed as it composes: a sealed bubble measures whole on its first frame, so the list's anchor holds.
        val sealed = rememberMarkdownState(markdown, immediate = true)
        Markdown(
            sealed,
            look,
            typography,
            Modifier,
            padding,
            annotator = LITERAL_HTML,
            components = components,
            animations = animations,
        )
        return
    }
    val cursor = cursorContent()
    var restarts by remember { mutableIntStateOf(0) }
    key(resets, restarts) {
        val state = rememberStreamingMarkdownState()
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
            annotator = LITERAL_HTML,
            inlineContent = cursor,
            components = components,
            animations = animations,
        )
    }
}
