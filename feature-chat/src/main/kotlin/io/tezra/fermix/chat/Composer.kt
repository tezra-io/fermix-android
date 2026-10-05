package io.tezra.fermix.chat

import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.Edge
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.controlPlane
import kotlinx.coroutines.flow.drop

/** The field's most lines before it scrolls (design section 13.6). */
private const val FIELD_LINES = 6

/** The send ↔ stop cross-rotation's turn: the leaving control turns this far out, the coming one this far in. */
private const val CROSS_ROTATION = 90f

/**
 * What the composer does: the field's change, send, stop, the palette (long-press send, Ctrl+K), the model chip's
 * sheet, the attachments' sheet, tray and keyboard images ([attach]) and the voice note ([voice]). Send takes what
 * to do once the session took the request, where the composer plays its haptic.
 */
data class ComposerActions(
    val onField: (TextFieldValue) -> Unit,
    val onSend: (onTaken: () -> Unit) -> Unit,
    val onStop: () -> Unit,
    val onPalette: () -> Unit,
    val onModel: () -> Unit = {},
    val attach: AttachActions = AttachActions(),
    val voice: VoiceActions = VoiceActions(),
)

/**
 * How the composer looks: the [title] its placeholder names, whether send is stop ([stops]), the model chip, the
 * attachments and voice note ([media]), and the latest change the chat, not the owner's typing, made to the
 * field ([written]).
 */
data class ComposerLook(
    val title: String,
    val stops: Boolean,
    val chip: ModelChip?,
    val media: MediaUi = MediaUi(),
    val written: FieldWrite = FieldWrite(),
)

/** What row 2's last control is: send, stop, or the mic while the field and the tray are empty. */
private enum class EndControl { SEND, STOP, MIC }

/**
 * The composer (design section 13.6): a floating 28 dp tonal pill. Over row 1, the tray of picked items; row 1
 * is the field, "Message {title}…", up to six lines, or the recording row, or the voice draft; row 2 (40 dp)
 * holds the attach +, the model chip (design section 8.6), none when the daemon says no model, and at its end the
 * mic while the field and the tray are empty, else the send ↑ in a filled circle, which is stop while a turn
 * runs and there is nothing to send, cross-rotating in 200 ms. Locked, row 2 is pause, stop and send; under a
 * draft, its trash and send. The lock pill hangs over the held mic and the voice line over the pill. Enter sends
 * and Shift+Enter breaks the line on a hardware keyboard; Ctrl+K opens the palette. Only the chip is ever
 * disabled, with no connection. [inSheet]: the palette's sheet holds it, on the canvas inside the sheet's tone
 * (the canon's `.sheet .cmp`).
 */
@Composable
internal fun Composer(
    field: TextFieldValue,
    look: ComposerLook,
    actions: ComposerActions,
    inSheet: Boolean,
) {
    val colors = LocalFermixColors.current
    val surface =
        if (inSheet) {
            Modifier.background(
                colors.canvas,
                FermixShapes.dock,
            )
        } else {
            Modifier.controlPlane(Edge.Around, colors)
        }
    val voice = look.media.voice
    VoiceHint(voice, look.media.micOff, actions.voice.onSettings)
    Box {
        Column(
            modifier =
                Modifier
                    .padding(start = 12.dp, top = if (inSheet) 8.dp else 0.dp, end = 12.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .then(surface)
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 6.dp),
        ) {
            val picked = look.media.attach.picked
            if (picked.isNotEmpty() &&
                voice == VoiceUi.Idle
            ) {
                Tray(picked, actions.attach.thumbnail, actions.attach.onRemove)
            }
            when (voice) {
                is VoiceUi.Recording -> RecordingRow(voice)
                is VoiceUi.Draft -> DraftRow(voice, look.media.playing, actions.voice.onPlayDraft)
                VoiceUi.Idle -> Field(field, look, actions)
            }
            SecondRow(field, look, actions)
        }
        if (voice is VoiceUi.Recording && !voice.locked) {
            LockPill(Modifier.align(Alignment.TopEnd).offset(x = (-20).dp, y = (-80).dp))
        }
    }
}

/** Row 2: the attach +, the chip and the end control; or, locked, pause, stop and send; or, a draft, trash and send. */
@Composable
private fun SecondRow(
    field: TextFieldValue,
    look: ComposerLook,
    actions: ComposerActions,
) {
    val view = LocalView.current
    val voice = look.media.voice
    val handsFree = voice is VoiceUi.Draft || (voice is VoiceUi.Recording && voice.locked)
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (handsFree) {
            HandsFreeControls(voice, actions.voice)
        } else {
            RowControl(R.drawable.ic_chat_plus, stringResource(R.string.chat_attach), onClick = actions.attach.onOpen)
            look.chip?.let { ModelChipButton(it, actions.onModel) }
        }
        Spacer(Modifier.weight(1f))
        if (handsFree) {
            RowControl(
                R.drawable.ic_chat_send,
                stringResource(R.string.chat_send),
                go = true,
                onClick = { actions.voice.onSend { HapticFeedback.perform(view, HapticUse.Send) } },
            )
        } else {
            val empty =
                field.text.isBlank() &&
                    look.media.attach.picked
                        .isEmpty()
            val recording = voice is VoiceUi.Recording
            val end =
                when {
                    recording -> EndControl.MIC
                    look.stops -> EndControl.STOP
                    empty -> EndControl.MIC
                    else -> EndControl.SEND
                }
            EndControls(end, recording, actions)
        }
    }
}

/** Locked: pause or resume, then stop; under a draft: its trash. */
@Composable
private fun HandsFreeControls(
    voice: VoiceUi,
    actions: VoiceActions,
) {
    if (voice is VoiceUi.Draft) {
        RowControl(R.drawable.ic_chat_trash, stringResource(R.string.chat_discard), onClick = actions.onDiscard)
        return
    }
    val paused = (voice as? VoiceUi.Recording)?.paused == true
    if (paused) {
        RowControl(R.drawable.ic_chat_play, stringResource(R.string.chat_resume_recording), onClick = actions.onResume)
    } else {
        RowControl(R.drawable.ic_chat_pause, stringResource(R.string.chat_pause_recording), onClick = actions.onPause)
    }
    RowControl(R.drawable.ic_chat_stop, stringResource(R.string.chat_stop_recording), onClick = actions.onStop)
}

/**
 * The field (design section 13.6): its words are the chat's (ChatComposer), kept here in a TextFieldState while the
 * owner types, every change handed back ([ComposerActions.onField]); a change the chat makes ([ComposerLook.written])
 * is written into it once, by its revision, so a frame that brings the owner's typing back late never undoes it. The
 * TextFieldState is remembered, never saved: a saved state goes to the system through the binder as the app stops,
 * whose 1 MB a paste outgrows, and that stops the app (Task 14c); the chat brings the words back itself, from its
 * ViewModel as a rotation makes the field again and from its draft once the process is gone.
 * The TextFieldState takes the keyboard's images ([AttachActions.onKeyboard], IME `commitContent`), which the
 * field over a TextFieldValue refuses.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Field(
    field: TextFieldValue,
    look: ComposerLook,
    actions: ComposerActions,
) {
    val colors = LocalFermixColors.current
    val view = LocalView.current
    val placeholder = stringResource(R.string.chat_placeholder, look.title)
    val state = remember { TextFieldState(field.text, field.selection) }
    val scroll = rememberScrollState()
    val applied = remember { mutableIntStateOf(look.written.revision) }
    val onField by rememberUpdatedState(actions.onField)
    val onKeyboard by rememberUpdatedState(actions.attach.onKeyboard)
    val written = look.written
    LaunchedEffect(state) { showCaretAtEnd(state, scroll) }
    LaunchedEffect(written.revision) {
        if (written.revision == applied.intValue) return@LaunchedEffect
        applied.intValue = written.revision
        if (state.text.toString() == written.value.text) return@LaunchedEffect
        state.edit {
            replace(0, length, written.value.text)
            selection = written.value.selection
        }
        showCaretAtEnd(state, scroll)
    }
    LaunchedEffect(state) {
        snapshotFlow { TextFieldValue(state.text.toString(), state.selection, state.composition) }
            .drop(1)
            .collect { onField(it) }
    }
    BasicTextField(
        state = state,
        scrollState = scroll,
        textStyle = FermixType.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accentInk),
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = FIELD_LINES),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .contentReceiver { content -> keyboardContent(content, onKeyboard) }
                .onPreviewKeyEvent { keys(it, actions) { HapticFeedback.perform(view, HapticUse.Send) } }
                .semantics { contentDescription = placeholder },
        decorator = { inner ->
            Box {
                if (state.text.isEmpty()) Text(placeholder, style = FermixType.body, color = colors.inkSecondary)
                inner()
            }
        },
    )
}

/**
 * Once the field has laid out its words, a caret at their end is brought into view: a restored draft or Edit's
 * words longer than six lines open on their last line, where the owner goes on typing, as the field does while
 * focused.
 */
private suspend fun showCaretAtEnd(
    state: TextFieldState,
    scroll: ScrollState,
) {
    withFrameNanos {}
    if (state.selection.end == state.text.length) scroll.scrollTo(scroll.maxValue)
}

/**
 * The keyboard's committed content: each item with a URI goes to [onKeyboard], with [content] itself, whose extras
 * hold the read grant the platform revokes once they are collected; the rest back to the field.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun keyboardContent(
    content: TransferableContent,
    onKeyboard: (List<String>, Any) -> Unit,
): TransferableContent? {
    val clip = content.clipEntry.clipData
    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.toString() }
    if (uris.isEmpty()) return content
    onKeyboard(uris, content)
    return content.consume { it.uri != null }
}

/**
 * A hardware keyboard's keys (design section 13.6): Enter sends, [onTaken] once the session took it; Shift+Enter
 * is left to the field, which breaks the line; Ctrl+K opens the palette. Any other key is the field's.
 */
private fun keys(
    event: KeyEvent,
    actions: ComposerActions,
    onTaken: () -> Unit,
): Boolean {
    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
    val sends = enter && !event.isShiftPressed
    val palette = event.key == Key.K && event.isCtrlPressed
    val handled = sends || palette
    if (handled && event.type == KeyEventType.KeyDown) {
        if (sends) actions.onSend(onTaken) else actions.onPalette()
    }
    return handled
}

/**
 * The end control (design sections 13.1 and 13.6): send ↔ stop ↔ mic, the leaving control turning out a quarter
 * and fading as the coming one turns in from a quarter the other way, over 200 ms; under reduce-motion it changes
 * at once. Stop plays `REJECT` as it is pressed; send plays `CONFIRM` once the session took the request; the mic
 * records while held, and stays the same control as its recording starts, so the hold goes on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EndControls(
    end: EndControl,
    recording: Boolean,
    actions: ComposerActions,
) {
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val time = if (reduced) 0 else FermixMotion.SEND_STOP_MILLIS
    AnimatedContent(
        targetState = end,
        transitionSpec = { fadeIn(tween(time)) togetherWith fadeOut(tween(time)) },
        label = "send, stop or mic",
    ) { control ->
        val turn by transition.animateFloat(transitionSpec = { tween(time) }, label = "cross-rotate") { phase ->
            when (phase) {
                EnterExitState.PreEnter -> -CROSS_ROTATION
                EnterExitState.Visible -> 0f
                EnterExitState.PostExit -> CROSS_ROTATION
            }
        }
        if (control == EndControl.MIC) {
            Box(modifier = Modifier.graphicsLayer { rotationZ = turn }) { MicButton(recording, actions.voice) }
            return@AnimatedContent
        }
        val stop = control == EndControl.STOP
        val label = stringResource(if (stop) R.string.chat_stop else R.string.chat_send)
        val colors = LocalFermixColors.current
        Box(
            modifier =
                Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .graphicsLayer { rotationZ = turn }
                    .background(if (stop) colors.ink else colors.accent, CircleShape)
                    .combinedClickable(
                        onClick = { pressed(view, stop, actions) },
                        onLongClick = actions.onPalette,
                    ).semantics {
                        role = Role.Button
                        contentDescription = label
                    },
            contentAlignment = Alignment.Center,
        ) {
            val icon = if (stop) R.drawable.ic_chat_stop else R.drawable.ic_chat_send
            Icon(painterResource(icon), null, tint = if (stop) colors.canvas else colors.onAccent)
        }
    }
}

/** Stop, refused at once, or send, its act felt once the session took it (design section 13.1, "Haptics"). */
private fun pressed(
    view: View,
    stop: Boolean,
    actions: ComposerActions,
) {
    if (stop) {
        HapticFeedback.perform(view, HapticUse.Stop)
        actions.onStop()
    } else {
        actions.onSend { HapticFeedback.perform(view, HapticUse.Send) }
    }
}
