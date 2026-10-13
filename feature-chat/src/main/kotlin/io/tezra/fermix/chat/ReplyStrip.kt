package io.tezra.fermix.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalFermixMotion
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.iconFocusRing

/** The bar at a quote's start, in the strip and in the chip. */
private val QUOTE_BAR = 2.dp

/** The chip's lines on the owner's bubble: onInk at this share, as the quote stands back from the question. */
private const val QUOTED_ALPHA = 0.8f

/**
 * The reply held over the composer, the pill's first row (the owner, 2026-10-11): a bar in the ink at its start,
 * "Replying to {title}" or "Replying to your message", the quote's lines as the strip shows them (shownLines) in the
 * secondary text, and at its end "Drop reply", which puts it down; back does not. TalkBack hears the name and the
 * lines as one, and the button as its own. It comes and goes on the standard scheme, at once under reduce-motion.
 */
@Composable
internal fun ReplyStrip(
    reply: ReplyUi?,
    onDrop: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val motion = LocalFermixMotion.current
    // The strip going out draws the reply it last showed, kept once each composition is applied.
    val last = remember { mutableStateOf(reply) }
    SideEffect { if (reply != null) last.value = reply }
    val shown = reply ?: last.value
    AnimatedVisibility(
        visible = reply != null,
        enter = expandVertically(motion.defaultSpatial.spec(reduced)) + fadeIn(motion.defaultEffects.spec(reduced)),
        exit = shrinkVertically(motion.defaultSpatial.spec(reduced)) + fadeOut(motion.defaultEffects.spec(reduced)),
        label = "reply strip",
    ) {
        shown?.let { StripRow(it, onDrop) }
    }
}

@Composable
private fun StripRow(
    reply: ReplyUi,
    onDrop: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val name =
        reply.to?.let { stringResource(R.string.chat_replying_to, it) }
            ?: stringResource(R.string.chat_replying_to_you)
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(QUOTE_BAR)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .background(colors.ink),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp).semantics(mergeDescendants = true) {}) {
            Text(
                name,
                style = FermixType.labelSmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                reply.lines.joinToString("\n"),
                style = FermixType.bodyMedium,
                color = colors.textSecondary,
                maxLines = QUOTE_SHOWN_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDrop, modifier = Modifier.iconFocusRing()) {
            Icon(painterResource(R.drawable.ic_chat_x), stringResource(R.string.chat_reply_drop), tint = colors.ink)
        }
    }
}

/**
 * The focus the composer's field takes as a reply starts, by its revision: a field made over a reply already held,
 * as a rotation makes it again, takes none.
 */
@Composable
internal fun rememberReplyFocus(reply: ReplyUi?): FocusRequester {
    val requester = remember { FocusRequester() }
    val asked = remember { mutableIntStateOf(reply?.revision ?: 0) }
    val revision = reply?.revision
    LaunchedEffect(revision) {
        if (revision == null || revision == asked.intValue) return@LaunchedEffect
        asked.intValue = revision
        requester.requestFocus()
    }
    return requester
}

/**
 * The owner's words in [ink], as their bubble and a caption draw them: when [text] starts with a reply's quote
 * (leadingQuote, parsed here and nowhere else), the quote above the question, a bar at its start and its lines at 80 %
 * (shownLines), at most three lines drawn, heard as "Quoting: {lines}" before the words; then [words] draws the
 * question, or the text whole when it starts with none. The chip is not tappable: step 1 has nothing to jump to.
 */
@Composable
internal fun QuotedWords(
    text: String,
    ink: Color,
    words: @Composable (String) -> Unit,
) {
    val quote = remember(text) { leadingQuote(text) }
    if (quote == null) {
        words(text)
        return
    }
    Column {
        QuoteBlock(shownLines(quote.lines), ink)
        words(quote.rest)
    }
}

@Composable
private fun QuoteBlock(
    lines: List<String>,
    ink: Color,
) {
    val shown = lines.joinToString("\n")
    val heard = stringResource(R.string.chat_quoted, shown)
    Row(
        modifier =
            Modifier
                .height(IntrinsicSize.Min)
                .padding(bottom = 6.dp)
                .clearAndSetSemantics { text = AnnotatedString(heard) },
    ) {
        Box(modifier = Modifier.width(QUOTE_BAR).fillMaxHeight().background(ink))
        Text(
            shown,
            style = FermixType.bodyMedium,
            color = ink.copy(alpha = QUOTED_ALPHA),
            maxLines = QUOTE_SHOWN_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
