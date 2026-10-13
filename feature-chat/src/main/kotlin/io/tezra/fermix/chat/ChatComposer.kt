package io.tezra.fermix.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tezra.fermix.design.Sender
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

/** The protocol a session speaks, whose frames the composer's words and every request are weighed in. */
internal const val MSG_PROTOCOL = 2

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
 * A reply the owner holds over the composer (the owner, 2026-10-11): the [sender] of the message it quotes, its
 * [quote], and that message's [seq] and [clientMsgId], kept for the engine's `reply_to` (step 2) and sent nowhere
 * now; [revision] counts the replies started, so each one, the same message's again among them, is a new one.
 */
data class ReplyDraft(
    val sender: Sender,
    val quote: ReplyQuote,
    val seq: ULong?,
    val clientMsgId: String?,
    val revision: Int = 0,
)

/**
 * The reply held over the composer, at most one: started by a swipe or TalkBack's Reply on a message with words
 * ([start]), put down by the strip's ✕ ([drop]) or once a `msg` took its quote ([spent]). It lives with the chat's
 * ViewModel, so a rotation and the app going to the background keep it and leaving the chat or a process death let it
 * go; the draft keeps the field's words and not the reply.
 */
class ReplyHold {
    private val draft = MutableStateFlow<ReplyDraft?>(null)
    private var started = 0

    val held: StateFlow<ReplyDraft?> = draft.asStateFlow()

    /** A reply to [message], in place of any held: a message with words to quote that does not stream (takesReply). */
    fun start(message: ShownMessage) {
        require(!message.streaming) { "a streaming bubble takes no reply" }
        val quote = requireNotNull(quoteOf(message.text)) { "a reply quotes a message's words" }
        started += 1
        draft.value = ReplyDraft(message.sender, quote, message.seq, message.clientMsgId, started)
    }

    fun drop() {
        draft.value = null
    }

    /** A `msg` took [sent]'s quote: it is put down, unless another reply was started since. */
    fun spent(sent: ReplyDraft) {
        draft.compareAndSet(sent, null)
    }
}

/**
 * What a `msg` of the field's [words] carries while [reply] is held: its quote ahead of them (withQuote), the one
 * shape the daemon sees; the words as they are with no reply, or with no words, which a reply adds nothing to.
 */
fun composedOf(
    reply: ReplyDraft?,
    words: String,
): String = if (reply == null || words.isBlank()) words else withQuote(reply.quote, words)

/**
 * The composer's text path (design section 13.6): the field, restored from the chat's draft as it opens and
 * kept [DRAFT_DEBOUNCE_MS] after each change and as the screen leaves ([keep], on [background], which
 * outlives the screen); the slash palette, open while the field holds a command's word or the owner asked
 * for it (long-press send, Ctrl+K); the [reply] held over it, whose quote goes ahead of the next `msg`'s words; and
 * send, a `command` for the daemon's commands and a `msg` otherwise, which empties the field once the session took
 * it. A blank field sends nothing: nothing is disabled, and the mic takes send's place (ChatVoice).
 */
class ChatComposer(
    private val requests: ChatRequests,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val background: CoroutineScope,
    internal val commands: () -> List<CommandDescriptor>,
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

    /** The reply held over the composer, whose quote goes ahead of the next `msg`'s words. */
    val reply = ReplyHold()

    /** Whether the palette shows. */
    val palette: StateFlow<Boolean> =
        combine(text, asked) { value, open -> open || paletteQuery(value.text) != null }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Whether what Send would send holds more than one message carries (ChatRequests.carries), as a message, a command
     * or a caption alike: a command's words as typed, a message's or a caption's under the held reply's quote
     * (replyWith, composedOf). Send then sends nothing, and the line over the composer says why. Weighed as the words
     * or the reply change, never as the caret, a selection or the keyboard's composition alone moves.
     */
    val tooLong: StateFlow<Boolean> =
        combine(text.map { it.text }.distinctUntilChanged(), reply.held) { words, held ->
            !requests.carries(composedOf(replyWith(words, held, commands()), words))
        }.distinctUntilChanged()
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
     * the outbox, where it would wait for a later connection and stop whatever runs then. A `msg` goes under the held
     * reply's quote, made here and nowhere else, and puts the reply down once the session took it; a command goes as
     * itself and the reply stays. Words past what one message carries, the quote with them, send nothing and stay in
     * the field, every one of them, and the reply stays ([tooLong]).
     */
    fun send(onTaken: () -> Unit) {
        val words = text.value.text
        val held = replyWith(words, reply.held.value, commands())
        val quoted = composedOf(held, words)
        if (!requests.carries(quoted)) return
        val made = requests.of(words, commands()) ?: return
        val request = if (made is ClientEvent.Msg && held != null) made.copy(text = quoted) else made
        scope.launch {
            val taken =
                if (request is ClientEvent.Command && request.name == STOP_COMMAND) {
                    requests.stopAs(request.clientMsgId)
                } else {
                    requests.send(request)
                }
            if (!taken) return@launch
            onTaken()
            emptyIf(words, held)
        }
    }

    /**
     * A request captioned [words] went, under [quoted]'s quote when it held one: the field empties unless the owner
     * typed on, and is kept, and that reply is put down.
     */
    fun emptyIf(
        words: String,
        quoted: ReplyDraft? = null,
    ) {
        asked.value = false
        if (text.value.text == words) replace("")
        quoted?.let(reply::spent)
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
 * The reply whose quote goes with the field's [words] while [held] is the one held: none for blank words, which a
 * quote adds nothing to, nor for words that go as one of the daemon's [commands], as a command goes as itself and the
 * reply stays. The one rule Send, the attach sheet's Send and tooLong weigh and compose by, so the line over the
 * composer says "too long" for what each would send.
 */
internal fun replyWith(
    words: String,
    held: ReplyDraft?,
    commands: List<CommandDescriptor>,
): ReplyDraft? = held?.takeUnless { words.isBlank() || goesAsCommand(words, commands) }

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
    val envelope = envelopeBytes(profileId)
    val fits = if (fitsOneMsg(head + words, envelope)) points.size else fittingPrefix(head, points, envelope)
    return if (fits == 0) field else head + String(points, 0, fits)
}

/**
 * How many of [points] one `msg` whose header weighs [envelope] around its words still carries after [head], none
 * when [head] alone is too much.
 */
private fun fittingPrefix(
    head: String,
    points: IntArray,
    envelope: Int,
): Int {
    if (!fitsOneMsg(head, envelope)) return 0
    // The first `fits` characters go and the first `fails` do not: halve between them.
    var fits = 0
    var fails = points.size
    repeat(MAX_CUTS) {
        if (fails - fits <= 1) return@repeat
        val middle = (fits + fails) / 2
        if (fitsOneMsg(head + String(points, 0, middle), envelope)) fits = middle else fails = middle
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
): Boolean = fitsOneMsg(words, envelopeBytes(profileId))

/** Whether [words], as JSON escapes them, fit one `msg` whose header weighs [envelope] around them (envelopeBytes). */
internal fun fitsOneMsg(
    words: String,
    envelope: Int,
): Boolean = envelope + escapedSize(words) <= MAX_HEADER_BYTES

/**
 * What a `msg` of [profileId] weighs around its words, which [fitsOneMsg] adds them to: its header at the largest seq,
 * with ten attachments and a `retry_of`, every id as the phone makes them, and no words. It depends on the profile
 * alone, so a caller weighs it once for as many words as it holds to the bound (ChatRequests, withSharedWords).
 */
internal fun envelopeBytes(profileId: String): Int {
    val bare = ClientEvent.Msg(ID_SIZED, profileId, "", List(MAX_ATTACHMENTS) { ID_SIZED }, retryOf = ID_SIZED)
    return encodeClientEvent(MSG_PROTOCOL, ULong.MAX_VALUE, bare).size - LENGTH_PREFIX_BYTES
}

/** What [words] weigh in a frame's header: their UTF-8 bytes as JSON escapes them, the string's two quotes left out. */
internal fun escapedSize(words: String): Int = JsonPrimitive(words).toString().encodeToByteArray().size - QUOTES
