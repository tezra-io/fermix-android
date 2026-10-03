package io.tezra.fermix.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxTheme
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.GroupPosition
import io.tezra.fermix.design.LocalFermixColors

/** The code card's ink and its header's (the canon's `.code` and `.code .hd`), the same in both themes. */
private val CODE_INK = Color(0xFFE6E7EB)
private val HEADER_INK = Color(0xFFB5B8C2)

/** Where the fold's fade reaches the card's colour (the canon's `.code .fold`, 70 %). */
private const val FOLD_SOLID = 0.7f

/** The language chip's fill: white at 10 % on the card. */
private val CHIP_FILL = Color(0x1AFFFFFF)

/** The canon's five tints on the card (`.code .k .s .n .c .f`), as highlights' theme. */
private val CANON_THEME =
    SyntaxTheme(
        key = "fermix",
        code = 0xE6E7EB,
        keyword = 0xB69CFF,
        string = 0x8FD6A4,
        literal = 0xF0B775,
        comment = 0x7C808B,
        metadata = 0x7FB2FF,
        multilineComment = 0x7C808B,
        punctuation = 0xE6E7EB,
        mark = 0x7FB2FF,
    )

/** What the owner does with a card's text: copy it, with the toast and the haptic, or share it. */
data class TextActions(
    val copy: (String) -> Unit,
    val share: (String) -> Unit,
)

/**
 * A code card (design section 13.5): `#16171B` in both themes, 16 dp, its header with the language chip, the
 * file it names, Copy and Share; no soft wrap, the code pans; past 14 lines it folds, and a tap on the fold
 * shows it all. The design's lexers that highlights has are tinted (tintOf); the rest stay untinted.
 * [key] keeps whether it is unfolded across a rotation.
 */
@Composable
internal fun CodeCard(
    info: FenceInfo,
    code: String,
    key: String,
    actions: TextActions,
    shape: Shape,
) {
    var unfolded by rememberSaveable(key) { mutableStateOf(false) }
    val foldable = folds(code)
    val shown = if (foldable && !unfolded) folded(code) else code
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(LocalFermixColors.current.codeCard),
    ) {
        CodeHeader(info, code, actions)
        Box {
            CodeBody(tintOf(info.language)?.name, shown)
            if (foldable && !unfolded) Fold(Modifier.align(Alignment.BottomCenter))
        }
        if (foldable) FoldToggle(unfolded, code.lines().size) { unfolded = !unfolded }
    }
}

@Composable
private fun CodeHeader(
    info: FenceInfo,
    code: String,
    actions: TextActions,
) {
    Row(
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        info.language?.let { language ->
            Text(
                text = language,
                style = FermixType.mono.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = CODE_INK,
                modifier =
                    Modifier
                        .background(
                            CHIP_FILL,
                            FermixShapes.chip,
                        ).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text(
            text = info.filename.orEmpty(),
            style = FermixType.mono.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = HEADER_INK,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CardButton(R.drawable.ic_chat_copy, stringResource(R.string.chat_code_copy)) { actions.copy(code) }
        CardButton(R.drawable.ic_chat_share, stringResource(R.string.chat_code_share)) { actions.share(code) }
    }
}

@Composable
private fun CardButton(
    icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), label, tint = HEADER_INK, modifier = Modifier.size(20.dp))
    }
}

/**
 * The code itself, 13.5/20 mono: through the renderer's highlighted code for a tinted [lexer], on the card's
 * own colour and with the canon's padding, or as plain text for any other.
 */
@Composable
private fun CodeBody(
    lexer: String?,
    code: String,
) {
    val style = FermixType.mono.copy(color = CODE_INK)
    if (lexer == null) {
        Text(
            text = code,
            style = style,
            softWrap = false,
            modifier =
                Modifier
                    .horizontalScroll(
                        rememberScrollState(),
                    ).padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 12.dp),
        )
        return
    }
    val builder = remember { Highlights.Builder().theme(CANON_THEME) }
    CompositionLocalProvider(
        LocalMarkdownColors provides markdownColor(text = CODE_INK, codeBackground = Color.Transparent),
        LocalMarkdownDimens provides markdownDimens(codeBackgroundCornerSize = 0.dp),
        // The renderer adds 8 dp above and below its code; the canon's 2 dp above and 12 dp below are made up here.
        LocalMarkdownPadding provides
            markdownPadding(codeBlock = PaddingValues(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 4.dp)),
    ) {
        MarkdownHighlightedCode(
            code,
            lexer,
            style,
            builder,
            showHeader = false,
            immediate = LocalInspectionMode.current,
        )
    }
}

/** The fold's fade over the last lines (the canon's `.code .fold`): 36 dp into the card's colour. */
@Composable
private fun Fold(modifier: Modifier) {
    val card = LocalFermixColors.current.codeCard
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .background(Brush.verticalGradient(0f to card.copy(alpha = 0f), FOLD_SOLID to card)),
    )
}

@Composable
private fun FoldToggle(
    unfolded: Boolean,
    lines: Int,
    onToggle: () -> Unit,
) {
    val label =
        if (unfolded) {
            stringResource(
                R.string.chat_code_less,
            )
        } else {
            pluralStringResource(R.plurals.chat_code_more, lines, lines)
        }
    Text(
        text = label,
        style = FermixType.labelSmall,
        color = HEADER_INK,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 16.dp),
    )
}

/** The card's corners in a group under its bubble (design section 13.1): 6 dp on the agent's side at the top. */
internal val GROUPED_CARD = RoundedCornerShape(topStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)

/** A card's corners in its group (the canon's `.card.g`): 6 dp at the top on the agent's side, joined to one above. */
internal fun cardShape(position: GroupPosition): Shape =
    if (position == GroupPosition.Middle || position == GroupPosition.Last) GROUPED_CARD else RoundedCornerShape(16.dp)
