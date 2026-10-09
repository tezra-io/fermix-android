package io.tezra.fermix.chat

import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.RingOn
import io.tezra.fermix.design.focusRing

/** Slid this far left, the held recording is cancelled (design section 13.6). */
val CANCEL_SLIDE = 120.dp

/** Slid this far up, the held recording locks: the lock pill's height above the mic (design section 13.6). */
val LOCK_SLIDE = 96.dp

/** The opacity of the recording row's waveform (the canon's `.rec svg.wave`, .7). */
private const val RECORDING_WAVE_ALPHA = 0.7f

/** The recording row's timer (the canon's `.rec .tm`, mono 14/20) and the small mono of a length (12/16). */
private val TIMER = FermixType.mono.copy(fontSize = 14.sp, lineHeight = 20.sp)
internal val SMALL_MONO = FermixType.mono.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** The least touch target (design sections 13.1 and 13.8). */
internal val TOUCH_TARGET = 48.dp

/**
 * The recording row (design section 13.6, the canon's `.rec`): the 10 dp red dot, the timer in mono, the live
 * waveform 120 dp wide, and, while held, "‹ Slide to cancel" at its end, which wraps rather than loses a word in
 * large type.
 */
@Composable
internal fun RecordingRow(recording: VoiceUi.Recording) {
    val colors = LocalFermixColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).background(colors.err, CircleShape))
        Text(durationText(recording.elapsedMs), style = TIMER, color = colors.ink)
        Wave(recording.bars, colors.ink, Modifier.width(120.dp), alpha = RECORDING_WAVE_ALPHA)
        if (!recording.locked) {
            Text(
                stringResource(R.string.chat_slide_to_cancel),
                style = FermixType.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The draft's row (the canon's `.draft`): play, its waveform and its length in mono. */
@Composable
internal fun DraftRow(
    draft: VoiceUi.Draft,
    playing: Playing?,
    onPlay: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val running = playing?.key == DRAFT_KEY && playing.running
    val played = playing?.takeIf { it.key == DRAFT_KEY }?.let(::playedShare)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(running, colors.hairline, colors.ink, RingOn.Surface, onPlay)
        Wave(draft.bars, colors.ink, Modifier.width(160.dp), played)
        Text(durationText(draft.durationMs), style = SMALL_MONO, color = colors.textSecondary)
    }
}

/** The lock pill over the held mic (the canon's `.lockp`): 44 wide, the lock above an up chevron. */
@Composable
internal fun LockPill(modifier: Modifier = Modifier) {
    val colors = LocalFermixColors.current
    Column(
        modifier =
            modifier
                .width(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.tonalSolid)
                .border(1.dp, colors.hairline, RoundedCornerShape(22.dp))
                .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val label = stringResource(R.string.chat_locked)
        Icon(
            painterResource(R.drawable.ic_chat_lock),
            label,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Icon(painterResource(R.drawable.ic_chat_up), null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
    }
}

/**
 * The line over the composer (the canon's `.hint`): "Recording stopped — send or discard" under a draft, or
 * "Microphone is off for Fermix" with "Open settings", underlined, once the owner refused it: the same size as the
 * words beside it, in the ink, it is an action by its underline, as the M51 update's reference player draws a text
 * button.
 */
@Composable
internal fun VoiceHint(
    voice: VoiceUi,
    micOff: Boolean,
    onSettings: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val draft = voice is VoiceUi.Draft
    if (!draft && !micOff) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val words = stringResource(if (draft) R.string.chat_recording_stopped else R.string.chat_mic_off)
        // The words wrap in large type; "Open settings" keeps its width.
        Text(words, style = FermixType.labelSmall, color = colors.textSecondary, modifier = Modifier.weight(1f, false))
        if (!draft) {
            Text(
                stringResource(R.string.chat_open_settings),
                style = FermixType.labelSmall,
                color = colors.ink,
                textDecoration = TextDecoration.Underline,
                modifier =
                    Modifier
                        .focusRing(FermixShapes.chip)
                        .minimumInteractiveComponentSize()
                        .clickable(role = Role.Button, onClick = onSettings),
            )
        }
    }
}

/** A 40 dp control of row 2 (the canon's `.cmp .ib`), filled with the ink for send ([go]), its icon in onInk. */
@Composable
internal fun RowControl(
    icon: Int,
    label: String,
    go: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .focusRing(CircleShape)
                .size(40.dp)
                .background(if (go) colors.ink else Color.Transparent, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = if (go) colors.onInk else colors.ink)
    }
}

/**
 * The mic (design sections 8.5 and 13.6): held, it records; slid left past [CANCEL_SLIDE] it cancels, slid up past
 * [LOCK_SLIDE] it locks, each with `GESTURE_THRESHOLD_ACTIVATE`; let go otherwise, it sends what it still records,
 * with `CONFIRM` once the session took it. A hold the phone takes from the finger (a system gesture, another
 * window) stops the take into a draft; one a rotation or a fold takes, as the activity is made again, locks it.
 * Filled with the ink while it records, its icon in onInk, as the canon's held mic.
 */
@Composable
internal fun MicButton(
    recording: Boolean,
    actions: VoiceActions,
) {
    val colors = LocalFermixColors.current
    val view = LocalView.current
    val activity = LocalActivity.current
    val voice by rememberUpdatedState(actions)
    val label = stringResource(R.string.chat_record)
    val windowChanging = { activity?.isChangingConfigurations == true }
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .background(if (recording) colors.ink else Color.Transparent, CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                }.pointerInput(Unit) {
                    holdToRecord(view, { CANCEL_SLIDE.toPx() }, { LOCK_SLIDE.toPx() }, windowChanging) { voice }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_chat_mic), null, tint = if (recording) colors.onInk else colors.ink)
    }
}

/** How one hold of the mic ended. */
private enum class HoldEnd {
    /** Let go where it was held. */
    RELEASED,

    /** Slid left past the cancel line. */
    CANCELLED,

    /** Slid up past the lock line. */
    LOCKED,

    /** Taken from the finger: the system cancelled the touch, which Compose reads as a consumed lift. */
    TAKEN,
}

/**
 * One hold of the mic: [VoiceActions.onHold] as it goes down, then the slide followed until it ends (holdEndOf);
 * let go, it releases; slid left it discards; slid up it locks; taken from the finger, the take stops into a draft.
 * A hold whose window goes with it, a rotation or a fold ([windowChanging] as the touch is taken, or the gesture
 * cancelled with no end), locks the take instead, so the new window shows it hands-free (pause · stop · send) rather
 * than a mic no finger holds (design section 13.11, rule 3).
 */
private suspend fun PointerInputScope.holdToRecord(
    view: View,
    cancelPx: () -> Float,
    lockPx: () -> Float,
    windowChanging: () -> Boolean,
    actions: () -> VoiceActions,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        actions().onHold()
        var ended = false
        val end =
            try {
                holdEnd(down, cancelPx, lockPx).also { ended = true }
            } finally {
                if (!ended) actions().onLock()
            }
        when (end) {
            HoldEnd.RELEASED -> actions().onRelease { HapticFeedback.perform(view, HapticUse.Send) }
            HoldEnd.CANCELLED -> thresholdThen(view, actions().onDiscard)
            HoldEnd.LOCKED -> thresholdThen(view, actions().onLock)
            HoldEnd.TAKEN -> if (windowChanging()) actions().onLock() else actions().onStop()
        }
    }
}

/** The slide of the hold that went [down], followed until it ends. */
private suspend fun AwaitPointerEventScope.holdEnd(
    down: PointerInputChange,
    cancelPx: () -> Float,
    lockPx: () -> Float,
): HoldEnd {
    var moved = Offset.Zero
    var end: HoldEnd? = null
    while (end == null) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
        moved += change?.positionChange() ?: Offset.Zero
        end = holdEndOf(change, moved, cancelPx(), lockPx())
        change?.consume()
    }
    return end
}

/**
 * Where a hold stands after [change], slid [moved] in all: ended when the finger lifts, its lift consumed when the
 * touch was taken from it, or past a line; none while it is held.
 */
private fun holdEndOf(
    change: PointerInputChange?,
    moved: Offset,
    cancelPx: Float,
    lockPx: Float,
): HoldEnd? =
    when {
        change == null -> HoldEnd.TAKEN
        !change.pressed -> if (change.isConsumed) HoldEnd.TAKEN else HoldEnd.RELEASED
        moved.x < -cancelPx -> HoldEnd.CANCELLED
        moved.y < -lockPx -> HoldEnd.LOCKED
        else -> null
    }

/** A voice threshold crossed: `GESTURE_THRESHOLD_ACTIVATE`, then what it does. */
private fun thresholdThen(
    view: View,
    action: () -> Unit,
) {
    HapticFeedback.perform(view, HapticUse.VoiceThreshold)
    action()
}
