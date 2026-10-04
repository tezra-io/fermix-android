package io.tezra.fermix.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.CommandDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How long after the owner stops typing the draft is kept (design section 13.6, "Drafts"). */
const val DRAFT_DEBOUNCE_MS = 400L

/**
 * A change the chat made to the field, not the owner's typing: the [value] it wrote, counted by [revision], which
 * the composer's field writes into what the owner types in (Composer's Field).
 */
data class FieldWrite(
    val revision: Int = 0,
    val value: TextFieldValue = TextFieldValue(""),
)

/**
 * The composer's text path (design section 13.6): the field, restored from the chat's draft as it opens and
 * kept [DRAFT_DEBOUNCE_MS] after each change and as the screen leaves ([keep], on [background], which
 * outlives the screen); the slash palette, open while the field holds a command's word or the owner asked
 * for it (long-press send, Ctrl+K); and send, a `command` for the daemon's commands and a `msg` otherwise,
 * which empties the field once the session took it. A blank field sends nothing: nothing is disabled, and the
 * mic takes send's place (ChatVoice).
 */
class ChatComposer(
    private val requests: ChatRequests,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val background: CoroutineScope,
    private val commands: () -> List<CommandDescriptor>,
) {
    private val text = MutableStateFlow(TextFieldValue(""))
    private val write = MutableStateFlow(FieldWrite())
    private val asked = MutableStateFlow(false)
    private val keeping = Mutex()

    // Whether the stored draft has been read back: an empty field before then is not the owner's, and keeping
    // it would erase the draft the chat opened with.
    private val restored = MutableStateFlow(false)

    val field: StateFlow<TextFieldValue> = text.asStateFlow()

    /** The latest change the chat, not the owner, made to the field. */
    val written: StateFlow<FieldWrite> = write.asStateFlow()

    /** Whether the palette shows. */
    val palette: StateFlow<Boolean> =
        combine(text, asked) { value, open -> open || paletteQuery(value.text) != null }
            .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch { restore() }
        scope.launch {
            text.map { it.text }.distinctUntilChanged().drop(1).collectLatest {
                delay(DRAFT_DEBOUNCE_MS)
                keep()
            }
        }
    }

    /** The field as the owner changed it. */
    fun edit(value: TextFieldValue) {
        text.value = value
    }

    /** The attach sheet's caption, which is the field's words, written into the composer's field as well. */
    fun caption(value: TextFieldValue) {
        text.value = value
        write.update { FieldWrite(it.revision + 1, value) }
    }

    /** Edit on a queued bubble: its words back in the field, the caret at their end. */
    fun replace(words: String) {
        val value = TextFieldValue(words, TextRange(words.length))
        text.value = value
        write.update { FieldWrite(it.revision + 1, value) }
    }

    fun openPalette() {
        asked.value = true
    }

    fun closePalette() {
        asked.value = false
        if (paletteQuery(text.value.text) != null) replace("")
    }

    /** A command picked on the palette: "/name " in the field, ready for its arguments. */
    fun pick(command: CommandDescriptor) {
        asked.value = false
        replace(pickedCommand(command))
    }

    /** Whether the field holds the model command alone, by its name or an alias (asksForModels). */
    fun asksForModels(): Boolean = asksForModels(text.value.text, commands())

    /**
     * Send: the field's request, the field emptied once the session took it, unless the owner typed on; then
     * [onTaken], where the screen plays send's haptic, which says something went (design section 13.1). A
     * typed `/stop` goes as the Stop control's does (ChatRequests.stopAs), at once or not at all, never into
     * the outbox, where it would wait for a later connection and stop whatever runs then.
     */
    fun send(onTaken: () -> Unit) {
        val words = text.value.text
        val request = requests.of(words, commands()) ?: return
        scope.launch {
            val taken =
                if (request is ClientEvent.Command && request.name == STOP_COMMAND) {
                    requests.stopAs(request.clientMsgId)
                } else {
                    requests.send(request)
                }
            if (!taken) return@launch
            onTaken()
            emptyIf(words)
        }
    }

    /** A request captioned [words] went: the field empties unless the owner typed on, and is kept. */
    fun emptyIf(words: String) {
        asked.value = false
        if (text.value.text == words) replace("")
        keep()
    }

    /**
     * Keeps the field as the chat's draft now, on [background]: the latest words, whichever call came last. An
     * empty field before the stored draft was read back keeps nothing, so the draft is not erased by a chat that
     * left before it showed it. A chat removed meanwhile has no draft to keep (ChatStore.setDraft's false).
     */
    fun keep() {
        background.launch {
            keeping.withLock {
                val words = text.value.text
                if (restored.value || words.isNotEmpty()) store.setDraft(words)
            }
        }
    }

    private suspend fun restore() {
        val draft = store.chat().firstOrNull()?.draft
        if (draft != null && text.value.text.isEmpty()) replace(draft)
        restored.value = true
    }
}
