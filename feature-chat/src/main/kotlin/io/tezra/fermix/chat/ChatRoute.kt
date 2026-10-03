package io.tezra.fermix.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.tezra.fermix.design.HapticFeedback
import io.tezra.fermix.design.HapticUse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Chat screen over [model]: its state, its composer and its actions, the draft kept as the screen stops,
 * and the platform's clipboard, toast and share sheet for Copy and Share.
 */
@Composable
fun ChatRoute(
    model: ChatViewModel,
    navigation: ChatNavigation,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val field by model.composer.field.collectAsStateWithLifecycle()
    val palette by model.composer.palette.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.composer.keep() }
    val text = rememberTextActions()
    val actions = remember(model, navigation, text) { actionsOf(model, navigation, text) }
    state?.let { ChatScreen(ChatUi(it, field, palette), actions) }
}

/** Copy, with the toast and the haptic (design section 13.5), and Share, one share sheet with the plain text. */
@Composable
internal fun rememberTextActions(): TextActions {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.chat_copied)
    return remember(context, clipboard, view, scope, copied) {
        TextActions(
            copy = { words ->
                copyText(scope, clipboard, words)
                HapticFeedback.perform(view, HapticUse.Copy)
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
            share = { words -> shareText(context, words) },
        )
    }
}

private fun copyText(
    scope: CoroutineScope,
    clipboard: Clipboard,
    words: String,
) {
    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, words))) }
}

private fun shareText(
    context: Context,
    words: String,
) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, words)
    context.startActivity(Intent.createChooser(send, null))
}

private fun actionsOf(
    model: ChatViewModel,
    navigation: ChatNavigation,
    text: TextActions,
): ChatScreenActions {
    fun guarded(action: () -> Unit): () -> Unit = { if (navigation.showing()) action() }
    val composer = model.composer
    return ChatScreenActions(
        onBack = guarded(navigation.onBack),
        onInstance = guarded(navigation.onInstance),
        composer =
            ComposerActions(
                onField = composer::edit,
                onSend = { onTaken -> if (navigation.showing()) composer.send(onTaken) },
                onStop = guarded(model.requests::stop),
                onPalette = guarded(composer::openPalette),
            ),
        onPick = composer::pick,
        onClosePalette = composer::closePalette,
        onError = { error -> if (navigation.showing()) errorAction(model.requests, error) },
        onOutbox = { message, entry -> if (navigation.showing()) outboxAction(model, message, entry) },
        list = ListActions(model::reachedBottom, model::reachedTop, model::listed),
        info = model::info,
        modelOf = model::modelOf,
        nowMono = model::nowMono,
        text = text,
    )
}

/**
 * An error card's one action, from the owner's hand alone: never run on its own (design section 13.5). "Retry
 * sending" sends a refused request again (ChatRequests.resend); "Run again" runs a failed turn's again, naming
 * it in `retry_of` (ChatRequests.retry).
 */
internal fun errorAction(
    requests: ChatRequests,
    error: ShownError,
) {
    when (error.action) {
        ErrorAction.RETRY_SENDING -> requests.resend(checkNotNull(error.request))
        ErrorAction.RUN_AGAIN -> requests.retry(checkNotNull(error.request))
        ErrorAction.RESET_TO_DEFAULT -> requests.resetModel()
        ErrorAction.NONE -> Unit
    }
}

/** A refused or unsent item's tap menu (design section 13.6): Edit, Remove, "Try again" as "Retry sending". */
private fun outboxAction(
    model: ChatViewModel,
    message: ShownMessage,
    entry: OutboxEntry,
) {
    when (entry) {
        OutboxEntry.EDIT -> model.edit(message)
        OutboxEntry.REMOVE, OutboxEntry.REMOVE_FROM_OUTBOX -> model.remove(message)
        OutboxEntry.TRY_AGAIN -> model.requests.resend(checkNotNull(message.request) { "a refused item holds it" })
    }
}
