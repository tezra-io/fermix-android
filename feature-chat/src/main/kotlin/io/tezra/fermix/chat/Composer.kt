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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** The field's most lines before it scrolls (design section 13.6). */
private const val FIELD_LINES = 6

/** The send ↔ stop cross-rotation's turn: the leaving control turns this far out, the coming one this far in. */
private const val CROSS_ROTATION = 90f

/**
 * What the composer does: the field's change, send, stop, and the palette (long-press send, Ctrl+K). Send
 * takes what to do once the session took the request, where the composer plays its haptic.
 */
data class ComposerActions(
    val onField: (TextFieldValue) -> Unit,
    val onSend: (onTaken: () -> Unit) -> Unit,
    val onStop: () -> Unit,
    val onPalette: () -> Unit,
)

/**
 * The composer (design section 13.6): a floating 28 dp tonal pill. Row 1 is the field, "Message {title}…", up to
 * six lines; row 2 (40 dp) holds the send ↑ in a filled circle at its end, which is stop while a turn runs and
 * the field is empty, cross-rotating in 200 ms. Its leading slots, attach and the model chip, are Tasks 13 and
 * 12's. Enter sends and Shift+Enter breaks the line on a hardware keyboard; Ctrl+K opens the palette. Nothing
 * here is ever disabled. [inSheet]: the palette's sheet holds it, on the canvas inside the sheet's tone (the
 * canon's `.sheet .cmp`).
 */
@Composable
internal fun Composer(
    field: TextFieldValue,
    title: String,
    stops: Boolean,
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
    Column(
        modifier =
            Modifier
                .padding(start = 12.dp, top = if (inSheet) 8.dp else 0.dp, end = 12.dp, bottom = 12.dp)
                .fillMaxWidth()
                .then(surface)
                .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 6.dp),
    ) {
        Field(field, title, actions)
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            SendOrStop(stops, actions)
        }
    }
}

@Composable
private fun Field(
    field: TextFieldValue,
    title: String,
    actions: ComposerActions,
) {
    val colors = LocalFermixColors.current
    val view = LocalView.current
    val placeholder = stringResource(R.string.chat_placeholder, title)
    BasicTextField(
        value = field,
        onValueChange = actions.onField,
        textStyle = FermixType.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accentInk),
        maxLines = FIELD_LINES,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .onPreviewKeyEvent { keys(it, actions) { HapticFeedback.perform(view, HapticUse.Send) } }
                .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box {
                if (field.text.isEmpty()) Text(placeholder, style = FermixType.body, color = colors.inkSecondary)
                inner()
            }
        },
    )
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
 * Send ↔ stop (design sections 13.1 and 13.6): the leaving control turns out a quarter and fades as the coming
 * one turns in from a quarter the other way, over 200 ms; under reduce-motion it changes at once. Stop plays
 * `REJECT` as it is pressed; send plays `CONFIRM` once the session took the request.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendOrStop(
    stops: Boolean,
    actions: ComposerActions,
) {
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val time = if (reduced) 0 else FermixMotion.SEND_STOP_MILLIS
    AnimatedContent(
        targetState = stops,
        transitionSpec = { fadeIn(tween(time)) togetherWith fadeOut(tween(time)) },
        label = "send or stop",
    ) { stop ->
        val turn by transition.animateFloat(transitionSpec = { tween(time) }, label = "cross-rotate") { phase ->
            when (phase) {
                EnterExitState.PreEnter -> -CROSS_ROTATION
                EnterExitState.Visible -> 0f
                EnterExitState.PostExit -> CROSS_ROTATION
            }
        }
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
