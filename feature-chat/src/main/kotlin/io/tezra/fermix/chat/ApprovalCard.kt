package io.tezra.fermix.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.focusRing
import kotlinx.coroutines.delay
import java.util.Locale

/** The countdown's step: the line and the bar move four times a second. */
private const val TICK_MS = 250L

/** At this many seconds or fewer the countdown turns to the warn colour (design section 13.5). */
internal const val WARN_SECONDS = 10

/** TalkBack reads the countdown as it reaches each of these (design section 13.8). */
internal val ANNOUNCED_SECONDS = listOf(30, WARN_SECONDS)

/** The detail's box (the canon's `.ap .dt`): radius 10 on the canvas. */
private val DETAIL_SHAPE = RoundedCornerShape(10.dp)

/** The buttons' room either side of their word: Material's 24 dp leaves "Approve" no line at 200 % type. */
private val ANSWER_PADDING = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/**
 * What the countdown says after a frame at [left] seconds (design section 13.8), from what it said before
 * ([said]) and the seconds the frame before showed ([before], none on the card's first frame): as it crosses
 * 30 s or 10 s ([ANNOUNCED_SECONDS]), the seconds it then has, so a frame that came late says its true time; a
 * card first drawn under 30 s says its seconds, once; and otherwise what it said stays, said once.
 */
internal fun announcementAfter(
    said: Int?,
    before: Int?,
    left: Int,
): Int? {
    val crossed = ANNOUNCED_SECONDS.any { left <= it && (before == null || before > it) }
    return if (crossed) left else said
}

/** The most seconds a countdown that shows [seconds] has as many digits for: 99 for 60, 9 for 8. */
internal fun widestSeconds(seconds: Int): Int = "9".repeat(seconds.coerceAtLeast(0).toString().length).toInt()

/**
 * An approval card (design section 13.5, "Approval poll"; the canon's `.ap`), or the receipt it became: the
 * card morphs into its receipt line in 350 ms, at once under reduce-motion, and a card whose time ran out
 * before the screen drew it is its receipt from the start (gotcha 18). The countdown is read from the
 * monotonic clock, never counted down, so a rotation or a fold keeps it where it stands; it ticks only while
 * the card waits and the clock moves, so it ends with the card's time, and a preview draws one frame of it.
 */
@Composable
internal fun ApprovalItem(
    card: ShownApproval,
    context: TimelineContext,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(context.nowMono()) }
    val receipt = card.receiptAt(now)
    if (receipt == null && !LocalInspectionMode.current) {
        // Re-keyed by each tick that moved the clock: never a loop of its own.
        LaunchedEffect(now) {
            delay(TICK_MS)
            now = context.nowMono()
        }
    }
    val time = if (LocalReducedMotion.current) 0 else FermixMotion.APPROVAL_RESOLVE_MILLIS
    Box(modifier = modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = receipt,
            transitionSpec = {
                val spec = if (time == 0) snap() else tween<Float>(time)
                (fadeIn(spec) togetherWith fadeOut(spec)).using(SizeTransform(clip = true))
            },
            label = "approval to receipt",
        ) { shown ->
            if (shown == null) PendingCard(card, now, context) else ReceiptLine(shown, card.ttlS)
        }
    }
}

/**
 * The waiting card: icon and kind, text, the detail in mono, Deny and Approve, the countdown and its bar. One
 * focus group for TalkBack, its one stop, with Approve and Deny as its custom actions while they take a press.
 */
@Composable
private fun PendingCard(
    card: ShownApproval,
    now: Long,
    context: TimelineContext,
) {
    val colors = LocalFermixColors.current
    val left = card.secondsLeft(now)
    val answerable = card.answerable(now)
    val approve = stringResource(R.string.chat_approve)
    val deny = stringResource(R.string.chat_deny)
    val actions =
        if (answerable) {
            listOf(
                CustomAccessibilityAction(approve) { true.also { context.cards.onAnswer(card.approvalId, true) } },
                CustomAccessibilityAction(deny) { true.also { context.cards.onAnswer(card.approvalId, false) } },
            )
        } else {
            emptyList()
        }
    Box {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(FermixSpacing.AGENT_BUBBLE_MAX_WIDTH)
                    .clip(FermixShapes.card)
                    .background(colors.agentBubble)
                    .semantics(mergeDescendants = true) { customActions = actions }
                    .padding(12.dp),
        ) {
            CardHead(card, context.locale)
            Text(card.text, style = FermixType.body, color = colors.ink, modifier = Modifier.padding(top = 6.dp))
            card.detail?.let { Detail(it) }
            Answers(answerable, onApprove = { context.cards.onAnswer(card.approvalId, true) }) {
                context.cards.onAnswer(card.approvalId, false)
            }
            Countdown(left, card.ttlS)
        }
        // Outside the card's group, so its words alone are read, and only as they change.
        Announcement(left)
    }
}

@Composable
private fun CardHead(
    card: ShownApproval,
    locale: Locale,
) {
    val colors = LocalFermixColors.current
    val icon = if (card.kind == CardKind.SOUL) R.drawable.ic_chat_pencil else R.drawable.ic_chat_shield
    val word =
        when (card.kind) {
            CardKind.SANDBOX -> stringResource(R.string.chat_approval_sandbox)
            CardKind.SOUL -> stringResource(R.string.chat_approval_soul)
            CardKind.OTHER -> card.kindWord.replaceFirstChar { it.titlecase(locale) }
        }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        Text(word, style = FermixType.labelSmall, color = colors.textSecondary)
    }
}

@Composable
private fun Detail(detail: String) {
    val colors = LocalFermixColors.current
    Text(
        detail,
        style = FermixType.mono,
        color = colors.ink,
        modifier =
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(DETAIL_SHAPE)
                .background(colors.canvas)
                .border(FermixSpacing.hairline, colors.hairline, DETAIL_SHAPE)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * Deny, tonal, and Approve, filled, each half the row at 44 dp, its word on one line; neither takes a press once
 * answered or expired. Each is a touch's alone: hidden from TalkBack, which answers through the card's custom
 * actions, so the card is its one stop (design section 13.8).
 */
@Composable
private fun Answers(
    enabled: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val answer = Modifier.heightIn(min = 44.dp).semantics { hideFromAccessibility() }
    Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onDeny,
            enabled = enabled,
            modifier = Modifier.weight(1f).then(answer).focusRing(FermixShapes.button),
            colors = ButtonDefaults.buttonColors(containerColor = colors.agentBubble, contentColor = colors.ink),
            contentPadding = ANSWER_PADDING,
        ) { AnswerWord(stringResource(R.string.chat_deny)) }
        Button(
            onClick = onApprove,
            enabled = enabled,
            modifier = Modifier.weight(1f).then(answer).focusRing(FermixShapes.button),
            colors = ButtonDefaults.buttonColors(containerColor = colors.ink, contentColor = colors.onInk),
            contentPadding = ANSWER_PADDING,
        ) { AnswerWord(stringResource(R.string.chat_approve)) }
    }
}

/** A button's word on one line: one that still does not fit ends in "…" rather than breaking in the word. */
@Composable
private fun AnswerWord(word: String) {
    Text(word, style = FermixType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * "Expires in {s} s · approving resumes the paused turn" over the 3 dp bar of the time left, both in the warn
 * colour at [WARN_SECONDS] and under. The line keeps the height it has at the most seconds the card shows
 * (widestSeconds, its figures tabular), so the card never changes height as it counts down and the
 * bottom-anchored list never moves its buttons under the owner's thumb.
 */
@Composable
private fun Countdown(
    left: Int,
    ttlS: Int,
) {
    val colors = LocalFermixColors.current
    val tint = if (left <= WARN_SECONDS) colors.warn else colors.textSecondary
    val fill = if (left <= WARN_SECONDS) colors.warn else colors.ink
    val fraction = (left.toFloat() / ttlS.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier = Modifier.padding(top = 10.dp)) {
        // Unseen and unread: only its height counts.
        Text(
            stringResource(R.string.chat_approval_expires, widestSeconds(maxOf(ttlS, left))),
            style = FermixType.labelSmall,
            color = Color.Transparent,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(stringResource(R.string.chat_approval_expires, left), style = FermixType.labelSmall, color = tint)
    }
    Box(
        modifier =
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.hairline),
    ) {
        Spacer(Modifier.fillMaxWidth(fraction).height(3.dp).background(fill, RoundedCornerShape(2.dp)))
    }
}

/**
 * TalkBack reads the countdown's line as it crosses 30 s and 10 s (design section 13.8), with the seconds it
 * then has (announcementAfter), from a live region there from the card's first frame, so each change of its
 * words is announced, the first one too. What it said is kept across a rotation, so a rotation says nothing.
 */
@Composable
private fun Announcement(left: Int) {
    var before by rememberSaveable { mutableStateOf<Int?>(null) }
    var said by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(left) {
        said = announcementAfter(said, before, left)
        before = left
    }
    val words = said?.let { stringResource(R.string.chat_approval_expires, it) }
    Box(
        modifier =
            Modifier.size(1.dp).semantics {
                words?.let { contentDescription = it }
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * The receipt line (the canon's `.rc`): its words, approved in the ok colour, denied in the error colour,
 * wrapping within the agent's 88 % lane, as the card it came from.
 */
@Composable
private fun ReceiptLine(
    receipt: Receipt,
    ttlS: Int,
) {
    val colors = LocalFermixColors.current
    val (words, tint) =
        when (receipt) {
            Receipt.APPROVED -> stringResource(R.string.chat_approval_approved) to colors.ok
            Receipt.DENIED -> stringResource(R.string.chat_approval_denied) to colors.errText
            Receipt.EXPIRED -> stringResource(R.string.chat_approval_expired, ttlS) to colors.textSecondary
            Receipt.CLOSED -> stringResource(R.string.chat_approval_closed) to colors.textSecondary
        }
    Box(modifier = Modifier.fillMaxWidth(FermixSpacing.AGENT_BUBBLE_MAX_WIDTH)) {
        Text(
            words,
            style = FermixType.label,
            color = tint,
            modifier =
                Modifier
                    .clip(FermixShapes.card)
                    .background(colors.agentBubble)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
