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
import io.tezra.fermix.protocol.CommandDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Chat screen over [model]: its state, its composer and its actions, search, the "Model" sheet and the
 * jump to a hit while search is open, the draft kept as the screen stops, the platform's clipboard, toast and
 * share sheet for Copy and Share, and a Custom Tab in the instance's tint for a link preview.
 */
@Composable
fun ChatRoute(
    model: ChatViewModel,
    navigation: ChatNavigation,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val field by model.composer.field.collectAsStateWithLifecycle()
    val palette by model.composer.palette.collectAsStateWithLifecycle()
    val search by model.search.state.collectAsStateWithLifecycle()
    val sheet by model.models.sheet.collectAsStateWithLifecycle()
    val jump by model.jumps.jump.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.composer.keep() }
    val text = rememberTextActions()
    val openLink = rememberLinkOpener(state?.header?.record?.tint)
    val actions = remember(model, navigation, text, openLink) { actionsOf(model, navigation, text, openLink) }
    state?.let { ChatScreen(ChatUi(it, field, palette, search, sheet, jump.takeIf { search != null }), actions) }
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
    openLink: (String) -> Unit,
): ChatScreenActions {
    fun guarded(action: () -> Unit): () -> Unit = { if (navigation.showing()) action() }
    val composer = model.composer
    return ChatScreenActions(
        onBack = guarded(navigation.onBack),
        onInstance = guarded(navigation.onInstance),
        composer =
            ComposerActions(
                onField = composer::edit,
                onSend = { onTaken -> if (navigation.showing()) sendAction(model, onTaken) },
                onStop = guarded(model.requests::stop),
                onPalette = guarded(composer::openPalette),
                onModel = guarded(model.models::open),
            ),
        onPick = { command -> paletteAction(model, command) },
        onClosePalette = composer::closePalette,
        onError = { error -> if (navigation.showing()) errorAction(model.requests, error) },
        onOutbox = { message, entry -> if (navigation.showing()) outboxAction(model, message, entry) },
        list = ListActions(model::reachedBottom, model::reachedTop, model::listed),
        info = model::info,
        modelOf = model::modelOf,
        nowMono = model::nowMono,
        text = text,
        cards =
            CardActions(
                onAnswer = { id, approve -> if (navigation.showing()) model.approvals.answer(id, approve) },
                thumbnail = { ref -> model.thumbnails.thumbnail(ref)?.let { decodeThumbnail(it) } },
                onLink = { url -> if (navigation.showing()) openLink(url) },
            ),
        models =
            ModelActions(
                onOpen = guarded(model.models::open),
                onPick = { row -> model.models.pick(row, model.state.value?.turnRuns == true) },
                onClose = model.models::close,
            ),
        search = searchActionsOf(model.search),
    )
}

/**
 * A command picked on the palette: `/model`, while the chat has a model chip, does what the chip does (design
 * section 8.6): opens the "Model" sheet, and nothing while the chip is disabled, the line above the composer
 * saying to connect; any other puts "/name " in the field.
 */
private fun paletteAction(
    model: ChatViewModel,
    command: CommandDescriptor,
) {
    val chip = model.state.value?.model
    if (command.name != MODEL_COMMAND || chip == null) return model.composer.pick(command)
    model.composer.closePalette()
    if (chip.enabled) model.models.open()
}

/**
 * Send; but `/model` typed alone, while the chat has a model chip, does what the chip does (design section 8.6):
 * the daemon answers that command with `models` pages, which the sheet is the one place to show. While the chip
 * is disabled nothing goes, and the line above the composer says to connect.
 */
private fun sendAction(
    model: ChatViewModel,
    onTaken: () -> Unit,
) {
    val chip = model.state.value?.model
    if (chip == null || !model.composer.asksForModels()) return model.composer.send(onTaken)
    if (!chip.enabled) return
    model.composer.closePalette()
    model.models.open()
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
