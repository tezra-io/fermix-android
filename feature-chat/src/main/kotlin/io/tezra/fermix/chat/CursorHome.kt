package io.tezra.fermix.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.markdownInlineContent
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.CompositeASTNode

/** Where a streaming answer's cursor stands (design section 13.5): after its last words, after "code…", or below. */
enum class CursorHome { PARAGRAPH, FENCE, OTHER }

/** The blocks the cursor's home is looked for inside: a list, its item, a quote. */
private val CONTAINERS =
    setOf(
        MarkdownElementTypes.UNORDERED_LIST,
        MarkdownElementTypes.ORDERED_LIST,
        MarkdownElementTypes.LIST_ITEM,
        MarkdownElementTypes.BLOCK_QUOTE,
    )

/**
 * Where the cursor of a streaming answer's prose [markdown] stands: in its last paragraph, after its last words,
 * as the renderer lays them out; after the "code…" chip of a fence still open; and for any other last block (a
 * heading, a table being written), below it. The last block is looked for inside lists and quotes, as deep as
 * the parser nests them.
 */
fun cursorHome(markdown: String): CursorHome {
    var node: ASTNode = markdownTree(markdown)
    var home: CursorHome? = null
    var depth = 0
    while (home == null && depth < MAX_NESTING) {
        val last = node.children.lastOrNull { it is CompositeASTNode }
        home = homeOf(last)
        if (last != null) node = last
        depth++
    }
    return home ?: CursorHome.OTHER
}

/** The cursor's home when [block] is the last block, or null for a container it is looked for inside. */
private fun homeOf(block: ASTNode?): CursorHome? =
    when {
        block == null -> CursorHome.OTHER
        block.type == MarkdownElementTypes.PARAGRAPH -> CursorHome.PARAGRAPH
        block.type == MarkdownElementTypes.CODE_FENCE && !closed(block) -> CursorHome.FENCE
        block.type in CONTAINERS -> null
        else -> CursorHome.OTHER
    }

/** How deep cursorHome looks: deeper than any answer nests its lists and quotes. */
private const val MAX_NESTING = 32

/** The beam cursor's slot in a line: a 1 dp gap and the 2 dp beam, 18 dp tall (the canon's `.cur`). */
private val CURSOR_SLOT = 3.dp
private val CURSOR_WIDTH = 2.dp
private val CURSOR_HEIGHT = 18.dp
internal const val CURSOR_ID = "io.tezra.fermix.chat.cursor"

/** What the cursor reads as when the paragraph is copied or spoken: nothing. */
internal const val CURSOR_ALTERNATE = "\u200B"

/** The beam cursor as the paragraph's inline content, in its slot, centred on the line. */
@Composable
internal fun cursorContent(): MarkdownInlineContent {
    val slot =
        with(LocalDensity.current) {
            Placeholder(CURSOR_SLOT.toSp(), CURSOR_HEIGHT.toSp(), PlaceholderVerticalAlign.TextCenter)
        }
    return markdownInlineContent(mapOf(CURSOR_ID to InlineTextContent(slot) { BeamCursor() }))
}

/**
 * The beam cursor (design section 13.5): 2 × 18 dp in the accent ink, blinking every 800 ms, still under
 * reduce-motion.
 */
@Composable
internal fun BeamCursor(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val blink =
        rememberInfiniteTransition(label = "cursor").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec =
                infiniteRepeatable(
                    keyframes {
                        durationMillis = FermixMotion.CURSOR_BLINK_MILLIS
                        1f at 0
                        1f at FermixMotion.CURSOR_BLINK_MILLIS / 2
                        0f at FermixMotion.CURSOR_BLINK_MILLIS / 2 + 1
                    },
                    RepeatMode.Restart,
                ),
            label = "cursor blink",
        )
    val accent = LocalFermixColors.current.accentInk
    Spacer(
        modifier =
            modifier
                .padding(start = 1.dp)
                .size(CURSOR_WIDTH, CURSOR_HEIGHT)
                .drawBehind { drawRect(accent, alpha = if (reduced) 1f else blink.value) },
    )
}
