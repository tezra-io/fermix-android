package io.tezra.fermix.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.CommandDescriptor
import io.tezra.fermix.protocol.LENGTH_PREFIX_BYTES
import io.tezra.fermix.protocol.MAX_HEADER_BYTES
import io.tezra.fermix.protocol.encodeClientEvent
import io.tezra.fermix.session.MAX_ATTACHMENTS
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
import kotlinx.serialization.json.JsonPrimitive

/** How long after the owner stops typing the draft is kept (design section 13.6, "Drafts"). */
const val DRAFT_DEBOUNCE_MS = 400L

/** The protocol a session speaks, whose `msg` the composer's words are held to. */
private const val MSG_PROTOCOL = 2

/** An id as the phone makes them, a UUID's 36 characters: a `msg`'s own, each attachment's and its `retry_of`. */
private val ID_SIZED = "0".repeat(36)

/** A JSON string's two quotes. */
private const val QUOTES = 2

/** The most halvings the cut of a share's words takes: a bound well past log2 of [MAX_SHARED_CHARS]. */
private const val MAX_CUTS = 32

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
    private val restoredState = MutableStateFlow(false)

    /** Whether the stored draft is back in the field: words written in before then would take its place. */
    val restored: StateFlow<Boolean> = restoredState.asStateFlow()

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
        restoredState.value = true
    }
}

/**
 * [field] with a share's [words] after it, on a line of their own, the words cut on a character's edge to the most
 * that one `msg` of [profileId] still carries ([fitsOneMsg]); [field] as it was when it carries no more.
 */
fun withSharedWords(
    field: String,
    words: String,
    profileId: String,
): String {
    val head = if (field.isEmpty() || field.endsWith('\n')) field else "$field\n"
    val points = words.codePoints().toArray()
    val fits = if (fitsOneMsg(head + words, profileId)) points.size else fittingPrefix(head, points, profileId)
    return if (fits == 0) field else head + String(points, 0, fits)
}

/** How many of [points] one `msg` of [profileId] still carries after [head], none when [head] alone is too much. */
private fun fittingPrefix(
    head: String,
    points: IntArray,
    profileId: String,
): Int {
    if (!fitsOneMsg(head, profileId)) return 0
    // The first `fits` characters go and the first `fails` do not: halve between them.
    var fits = 0
    var fails = points.size
    repeat(MAX_CUTS) {
        if (fails - fits <= 1) return@repeat
        val middle = (fits + fails) / 2
        if (fitsOneMsg(head + String(points, 0, middle), profileId)) fits = middle else fails = middle
    }
    check(fails - fits <= 1) { "the cut of ${points.size} characters did not settle in $MAX_CUTS halvings" }
    return fits
}

/**
 * Whether [words] fit one `msg` of [profileId]: its header (PROTOCOL.md, at most [MAX_HEADER_BYTES]) at the largest
 * seq, with ten attachments and a `retry_of`, every id as the phone makes them, and the words as JSON escapes them.
 */
fun fitsOneMsg(
    words: String,
    profileId: String,
): Boolean {
    val bare = ClientEvent.Msg(ID_SIZED, profileId, "", List(MAX_ATTACHMENTS) { ID_SIZED }, retryOf = ID_SIZED)
    val around = encodeClientEvent(MSG_PROTOCOL, ULong.MAX_VALUE, bare).size - LENGTH_PREFIX_BYTES - QUOTES
    val escaped = JsonPrimitive(words).toString().encodeToByteArray().size
    return around + escaped <= MAX_HEADER_BYTES
}
