package io.tezra.fermix.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.Sender

/** The caption under a card's images (the canon's `.cap`, 400 16/24, padded 8 12 10). */
private val CAPTION_PADDING = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp)

/**
 * A message with blobs (design section 13.5): its images in one card, the owner's at most 78 % wide and the
 * agent's 88 %, the message's words as the card's caption inside it; each document as its 64 dp row; the owner's
 * voice note as a bubble of its own; words with no image to caption them in a bubble after the documents. Under
 * them the time and the mark, the upload's line while it goes up, and a queued or refused item's line. [modifier],
 * the message's tap and long-press, goes on the image card, the note and the words, not on the column they sit in;
 * the lines under them lie on the row's wash while it is [selected].
 */
@Composable
internal fun MediaMessage(
    item: ChatItem.Message,
    context: TimelineContext,
    modifier: Modifier,
    selected: Boolean,
) {
    val message = item.message
    val user = message.sender == Sender.User
    val images = message.media.filter { it.shape == MediaShape.IMAGE }
    val documents = message.media.filter { it.shape == MediaShape.DOCUMENT }
    val notes = message.media.filter { it.shape == MediaShape.VOICE }
    val width = if (user) FermixSpacing.USER_BUBBLE_MAX_WIDTH else FermixSpacing.AGENT_BUBBLE_MAX_WIDTH
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(FermixSpacing.withinGroup),
    ) {
        if (images.isNotEmpty()) ImagesCard(images, item, context, modifier.fillMaxWidth(width))
        documents.forEach { DocumentRow(it, user, context, Modifier.fillMaxWidth(width)) }
        val side = if (user) Alignment.CenterEnd else Alignment.CenterStart
        notes.forEach {
            Box(modifier = Modifier.fillMaxWidth(width), contentAlignment = side) {
                VoiceBubble(it, message, context, modifier)
            }
        }
        val loose = images.isEmpty() && notes.isEmpty() && message.text.isNotBlank()
        if (loose) WordsBubble(message, context, modifier.fillMaxWidth(width))
        MediaLines(message, context, stamped = notes.isEmpty(), washed = selected)
    }
}

/** A message's images in their grid, and its words under them as their caption, inside the one card. */
@Composable
private fun ImagesCard(
    images: List<ShownMedia>,
    item: ChatItem.Message,
    context: TimelineContext,
    modifier: Modifier,
) {
    val message = item.message
    Column(modifier = modifier.clip(FermixShapes.card)) {
        ImageGrid(images, item.key, context)
        if (message.text.isNotBlank()) Caption(message, context)
    }
}

/** The card's caption (the canon's `.cap`): the owner's in onInk on the ink, the agent's on its bubble's tone. */
@Composable
private fun Caption(
    message: ShownMessage,
    context: TimelineContext,
) {
    val colors = LocalFermixColors.current
    val user = message.sender == Sender.User
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (user) colors.ink else colors.agentBubble,
                ).then(CAPTION_PADDING),
    ) {
        if (user) {
            Text(message.text, style = FermixType.body, color = colors.onInk)
        } else {
            Prose(message.text, false, message.resets, context.text, context.marksIn(message))
        }
    }
}

/** Words that caption no image, a document's message's, in the sender's bubble. */
@Composable
private fun WordsBubble(
    message: ShownMessage,
    context: TimelineContext,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    val user = message.sender == Sender.User
    Box(
        modifier =
            modifier
                .clip(FermixShapes.card)
                .background(if (user) colors.ink else colors.agentBubble)
                .padding(
                    horizontal = FermixSpacing.bubblePaddingHorizontal,
                    vertical = FermixSpacing.bubblePaddingVertical,
                ),
    ) {
        if (user) {
            Text(message.text, style = FermixType.body, color = colors.onInk)
        } else {
            Prose(message.text, false, message.resets, context.text, context.marksIn(message))
        }
    }
}

/**
 * The owner's voice note (design section 13.5, the canon's `.vn`): play in its circle, the 40-bar waveform, the
 * part played solid, its length, the speed chip; its upload's share while it goes up; under it the transcript,
 * "Transcribing…" until it comes; the time and the mark at the bubble's end. As wide as its widest line, at most its
 * column's 78 %, where the waveform gives up its width first, so the length and the chip keep one line each.
 */
@Composable
private fun VoiceBubble(
    media: ShownMedia,
    message: ShownMessage,
    context: TimelineContext,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    val shown = context.media
    val playing = shown.ui.playing?.takeIf { it.key == media.cacheName }
    val known = playing?.durationMs ?: shown.ui.lengths[media.cacheName]
    val measured by produceState(known, media.cacheName, context.linkUp) {
        if (value == null) value = shown.actions.length(media)
    }
    val length = (known ?: measured)?.let(::durationText).orEmpty()
    val label = stringResource(R.string.chat_voice_note, length)
    val onInk = colors.onInk
    val wash = onInk.copy(alpha = ON_INK_WASH)
    Column(
        modifier =
            modifier
                .width(IntrinsicSize.Max)
                .clip(FermixShapes.card)
                .background(colors.ink)
                .padding(
                    horizontal = FermixSpacing.bubblePaddingHorizontal,
                    vertical = FermixSpacing.bubblePaddingVertical,
                ),
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            modifier = Modifier.align(Alignment.Start).semantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayButton(playing?.running == true, wash, onInk) { shown.actions.onPlay(media) }
            val bars = noteBars(shown.ui.bars[media.cacheName])
            val played = playing?.let(::playedShare) ?: 0f
            Wave(bars, onInk, Modifier.weight(1f, fill = false).width(NOTE_WAVE), played)
            val lengthInk = onInk.copy(alpha = LENGTH_ALPHA)
            Text(length, style = SMALL_MONO, color = lengthInk, maxLines = 1, softWrap = false)
            SpeedChip(playing?.speed ?: SPEEDS.first(), wash, onInk, shown.actions.onSpeed)
        }
        media.sent?.let { UploadBar(it, wash, onInk, Modifier.padding(top = 6.dp)) }
        Transcript(message, Modifier.fillMaxWidth().padding(top = 6.dp), onInk)
        Stamp(message, context, onInk)
    }
}

/** The waveform of a note in its bubble (the canon's `.vn .wv`, 132 wide). */
private val NOTE_WAVE = 132.dp

/** The note's controls' wash on the ink, onInk at 22 % (the canon's rgba(255,255,255,.22)), and its length's .8. */
private const val ON_INK_WASH = 0.22f
private const val LENGTH_ALPHA = 0.8f

/** The transcript's type and opacity (the canon's `.tr`, 400 14/20 at .86). */
private val TRANSCRIPT_TYPE = FermixType.body.copy(fontSize = 14.sp, lineHeight = 20.sp)
private const val TRANSCRIPT_ALPHA = 0.86f

/** The note's transcript line (the canon's `.tr`, 400 14/20 at .86): its words, or "Transcribing…", or none. */
@Composable
private fun Transcript(
    message: ShownMessage,
    modifier: Modifier,
    ink: Color,
) {
    val words = if (message.transcribing) stringResource(R.string.chat_transcribing) else message.text
    if (words.isBlank()) return
    Text(words, style = TRANSCRIPT_TYPE, color = ink.copy(alpha = TRANSCRIPT_ALPHA), modifier = modifier)
}

/**
 * The lines under a message with blobs (the canon's `.state`): the upload's line while it goes up, or else the
 * time and mark when no bubble of it holds them ([stamped]); and a queued, pending or refused item's own, but
 * "Queued" under an interrupted upload, whose line says it waits for a connection. They lie on the canvas, so on a
 * selected row's wash ([washed]) they are the ink, whole (rowLine).
 */
@Composable
private fun MediaLines(
    message: ShownMessage,
    context: TimelineContext,
    stamped: Boolean,
    washed: Boolean,
) {
    val colors = LocalFermixColors.current
    val user = message.sender == Sender.User
    // An upload's line takes the stamp's place, as the canon's one `.state` line does.
    val line = message.uploadLine
    when {
        line != null -> UploadLineRow(line, context.host, washed)
        stamped -> Stamp(message, context, colors.ink, faded = !washed)
    }
    // An interrupted upload's line says why the item waits for a connection; "Queued" would say it twice.
    val waitSaid = message.uploadLine == UploadLine.INTERRUPTED && message.delivery == Delivery.PENDING
    if (!user || waitSaid) return
    when (message.delivery) {
        Delivery.QUEUED -> StateLine(stringResource(R.string.chat_queued_behind_reply), error = false, washed = washed)
        Delivery.PENDING -> StateLine(stringResource(R.string.chat_queued), error = false, washed = washed)
        Delivery.FAILED -> StateLine(stringResource(R.string.chat_not_sent), error = true, washed = washed)
        else -> Unit
    }
}

/**
 * An upload's line (design section 13.9): its words and glyph in the secondary text, at the column's end, or the
 * ink on a selected row's wash ([washed], rowLine). The canon's clock leads the interrupted line; the duplicate's
 * tick follows its words, as a mark does.
 */
@Composable
private fun UploadLineRow(
    line: UploadLine,
    host: String,
    washed: Boolean,
) {
    val colors = LocalFermixColors.current
    val tint = rowLine(colors, colors.textSecondary, washed)
    when (line) {
        UploadLine.INTERRUPTED -> {
            val words = stringResource(R.string.chat_upload_interrupted)
            GlyphLine(words, R.drawable.ic_chat_clock, leads = true, tint, Modifier.padding(top = 4.dp))
        }

        UploadLine.DUPLICATE -> {
            val words = stringResource(R.string.chat_duplicate, host)
            GlyphLine(words, R.drawable.ic_chat_check, leads = false, tint, Modifier.padding(top = 4.dp))
        }
    }
}

/** An attachment's upload as a line where no image holds a ring: the share [sent] of its [track] in [fill]. */
@Composable
internal fun UploadBar(
    sent: Float,
    track: Color,
    fill: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(UPLOAD_BAR).clip(RoundedCornerShape(UPLOAD_BAR / 2))) {
        drawRect(track)
        drawRect(fill, size = size.copy(width = size.width * sent.coerceIn(0f, 1f)))
    }
}

/** The upload line's height under a document or a note. */
private val UPLOAD_BAR = 3.dp
