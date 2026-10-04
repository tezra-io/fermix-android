package io.tezra.fermix.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The error card's left rule, in the error colour (the canon's `.er`). */
private val ERROR_RULE = 3.dp

/** A notice's words, 400 13/18 (the canon's `.pill`). */
private val PILL_STYLE = FermixType.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)

/**
 * An error card (design sections 13.5 and 13.9): the agent's surface with the error colour's 3 dp rule on the
 * left, the deck's sentence for its code, the follow-up line when it has one, and its one action. [host] and
 * [model] fill the sentence.
 */
@Composable
internal fun ErrorCard(
    error: ShownError,
    host: String,
    model: String,
    onAction: (ShownError) -> Unit,
) {
    val colors = LocalFermixColors.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(FermixShapes.card)
                .background(colors.agentBubble)
                .drawBehind { drawRect(colors.err, size = Size(ERROR_RULE.toPx(), size.height)) }
                .padding(start = 12.dp + ERROR_RULE, top = 10.dp, end = 12.dp, bottom = 4.dp),
    ) {
        Text(
            errorTitle(error.line, host, model),
            style = FermixType.label.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
        )
        errorBody(error.line)?.let { body ->
            Text(
                body,
                style = FermixType.bodyMedium,
                color = colors.inkSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val action = actionWords(error.action)
        if (action == null) {
            Spacer(Modifier.height(8.dp))
        } else {
            TextButton(onClick = { onAction(error) }, modifier = Modifier.offset(x = (-12).dp).heightIn(min = 48.dp)) {
                Text(action, style = FermixType.label, color = colors.accentInk)
            }
        }
    }
}

@Composable
private fun errorTitle(
    line: ErrorLine,
    host: String,
    model: String,
): String =
    when (line) {
        ErrorLine.NOT_SENT -> stringResource(R.string.chat_not_sent)
        ErrorLine.TIMEOUT -> stringResource(R.string.chat_error_timeout)
        ErrorLine.MODEL_UNAVAILABLE -> stringResource(R.string.chat_error_model_unavailable, model, host)
        ErrorLine.UNSUPPORTED -> stringResource(R.string.chat_error_unsupported, host)
        ErrorLine.GENERIC -> stringResource(R.string.chat_error_generic, host)
    }

@Composable
private fun errorBody(line: ErrorLine): String? =
    when (line) {
        ErrorLine.TIMEOUT -> stringResource(R.string.chat_error_timeout_body)
        ErrorLine.GENERIC -> stringResource(R.string.chat_error_generic_body)
        ErrorLine.NOT_SENT, ErrorLine.MODEL_UNAVAILABLE, ErrorLine.UNSUPPORTED -> null
    }

@Composable
private fun actionWords(action: ErrorAction): String? =
    when (action) {
        ErrorAction.RETRY_SENDING -> stringResource(R.string.chat_retry_sending)
        ErrorAction.RUN_AGAIN -> stringResource(R.string.chat_run_again)
        ErrorAction.RESET_TO_DEFAULT -> stringResource(R.string.chat_reset_to_default)
        ErrorAction.NONE -> null
    }

/** A centred line (design section 8.4): "Stopped", a notice, a model change and its note, on the agent's surface. */
@Composable
internal fun CentredPill(
    text: PillText,
    modifier: Modifier = Modifier,
) {
    val words =
        when (text) {
            PillText.Stopped -> stringResource(R.string.chat_stopped)
            is PillText.Notice -> text.text
            is PillText.Switched -> stringResource(R.string.chat_switched, text.label)
            is PillText.BackToDefault -> stringResource(R.string.chat_back_to_default, text.label)
            is PillText.Note -> text.text
        }
    Pill(words, PILL_STYLE, modifier)
}

/** A day header (the canon's `.day`): its day's words (dayWords). */
@Composable
internal fun DayPill(
    date: LocalDate,
    context: TimelineContext,
    modifier: Modifier = Modifier,
) {
    Pill(dayWords(date, context), FermixType.labelSmall, modifier)
}

/** A day as the timeline names it: today, yesterday, or the date in the owner's form. */
@Composable
internal fun dayWords(
    date: LocalDate,
    context: TimelineContext,
): String =
    when (date) {
        context.today -> stringResource(R.string.chat_today)
        context.today.minusDays(1) -> stringResource(R.string.chat_yesterday)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(context.locale).format(date)
    }

@Composable
private fun Pill(
    words: String,
    style: TextStyle,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    // Its words wide, centred, never past the column's 88 %.
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.fillMaxWidth(FermixSpacing.AGENT_BUBBLE_MAX_WIDTH),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = words,
                style = style,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .background(colors.agentBubble, RoundedCornerShape(percent = 50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The line under the owner's bubble (the canon's `.state`): queued, pending, or not sent in the error colour with
 * its ⚠; at the end of the column, as wide as the bubble may be, so a line that wraps stays under it, flush to its
 * end, the glyph beside its first words.
 */
@Composable
internal fun StateLine(
    words: String,
    error: Boolean,
) {
    val colors = LocalFermixColors.current
    val tint = if (error) colors.err else colors.inkSecondary
    Box(
        modifier = Modifier.fillMaxWidth(FermixSpacing.USER_BUBBLE_MAX_WIDTH).padding(top = 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (error) {
            GlyphLine(words, R.drawable.ic_chat_warn, leads = true, tint)
        } else {
            Text(words, style = FermixType.labelSmall, color = tint, textAlign = TextAlign.End)
        }
    }
}

/** The inline glyph's place: 16 dp, and the 4 dp between it and the words. */
private val GLYPH = 16.dp
private val GLYPH_GAP = 4.dp

private const val GLYPH_ID = "glyph"

/**
 * A state line's [words] in [tint] with its 16 dp glyph [icon], before them when it [leads] and after them
 * otherwise, set in the text itself, so a line that wraps in large type keeps the glyph beside its words; flush to
 * the end. The glyph keeps its 16 dp at any font scale.
 */
@Composable
internal fun GlyphLine(
    words: String,
    @DrawableRes icon: Int,
    leads: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val place =
        with(density) { Placeholder((GLYPH + GLYPH_GAP).toSp(), GLYPH.toSp(), PlaceholderVerticalAlign.TextCenter) }
    val side = if (leads) Alignment.CenterStart else Alignment.CenterEnd
    val glyph =
        InlineTextContent(place) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = side) {
                Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(GLYPH))
            }
        }
    val text =
        buildAnnotatedString {
            if (leads) appendInlineContent(GLYPH_ID)
            append(words)
            if (!leads) appendInlineContent(GLYPH_ID)
        }
    Text(
        text,
        style = FermixType.labelSmall,
        color = tint,
        textAlign = TextAlign.End,
        inlineContent = mapOf(GLYPH_ID to glyph),
        modifier = modifier,
    )
}

/** A time of day in the owner's own form. */
internal fun timeOf(
    wallMs: Long,
    context: TimelineContext,
): String =
    DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(context.locale)
        .format(Instant.ofEpochMilli(wallMs).atZone(context.zone))
