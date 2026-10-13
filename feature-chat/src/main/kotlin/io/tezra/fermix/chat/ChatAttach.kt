package io.tezra.fermix.chat

import androidx.lifecycle.SavedStateHandle
import io.tezra.fermix.data.LANDING_WAIT_MILLIS
import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.MAX_ATTACHMENTS
import io.tezra.fermix.session.OutboxAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.lang.ref.Reference
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The attach sheet and the tray (design sections 8.5 and 13.6): what is picked, from the Photo Picker, the camera,
 * the files, the clipboard ([paste]), the keyboard and another app's share, at most [MAX_ATTACHMENTS] in order, a
 * paste's, the keyboard's and a share's copied as they land, since their read grant ends long before Send; whether
 * the sheet shows; whether images go as files; and Send, which makes and stages each item (AttachMaker) and sends
 * one `msg` with the composer's words as its caption. An item past the daemon's `caps.max_media_bytes` ([limit]), or
 * the app's own [LANDING_MAX_BYTES] until the daemon has stated one, stays in the tray with its line and never goes;
 * one made past it, a JPEG over a small limit or a file whose provider hands over more than it said, copied no further
 * than a byte past the limit, stops the send with that line ([refused]), shown while that limit is the bound; and one
 * a paste, the keyboard or a share brings past what its landing copy may hold (Landings' landingBound) is never copied
 * whole, and shows as that line alone ([leftOut]) whatever the limit does after. Neither line outlives a send begun
 * while it showed that the session takes. Another app's landings run one at a time ([landings]), so the tray's room
 * is counted once those before have landed, each waiting its turn and then landing within [LANDING_WAIT_MILLIS]; the
 * owner's own picks wait behind none of them. The items whose files the chat made are kept in the chat's [saved]
 * state as the tray changes, so a process death keeps them, and come back as the chat opens again while their files
 * are there.
 */
class ChatAttach(
    private val parts: ChatParts,
    private val requests: ChatRequests,
    private val composer: ChatComposer,
    private val scope: CoroutineScope,
    private val limit: StateFlow<MediaLimit?>,
    private val saved: SavedStateHandle,
) {
    private val io: CoroutineDispatcher = parts.io

    private val picked = MutableStateFlow<List<Picked>>(emptyList())
    private val sheet = MutableStateFlow(false)
    private val asFiles = MutableStateFlow(false)

    /**
     * The first item a landing brought past what its copy may hold, which the tray does not hold: never copied, its
     * line stays whatever the limit does after, until an item is removed or unpicked, "Send as files" turns, or a send
     * begun while it showed is taken.
     */
    private val leftOut = MutableStateFlow<TooBig?>(null)

    /**
     * The item Send last stopped at, made past the limit as it went: a JPEG over a small limit, or a file whose
     * provider handed over more than it said. Its line names that limit, so it shows only while that is the bound.
     */
    private val refused = MutableStateFlow<TooBig?>(null)
    private val sending = AtomicBoolean(false)
    private val maker = AttachMaker(parts, { sendLimitOf(limit.value) }, io)

    /** Another app's landings one at a time: each counts the tray's room once those before it have landed. */
    private val landings = Mutex()

    val ui: StateFlow<AttachUi> =
        combine(picked, sheet, asFiles, limit, combine(refused, leftOut, ::Pair)) { items, open, files, read, out ->
            val (sent, landed) = out
            val bound = sendLimitOf(read)
            val ui = attachUiOf(items, open, files, bound)
            // A send's line names the limit it was made under; a landing's stays.
            val line = sent?.takeIf { it.maxBytes == bound } ?: landed
            if (ui.tooBig == null && line != null) ui.copy(tooBig = line) else ui
        }.stateIn(scope, SharingStarted.Eagerly, AttachUi())

    init {
        scope.launch {
            val kept = trayKept(saved[KEPT_TRAY])
            val present = withContext(io) { kept.filter { File(URI(it.uri)).isFile } }
            if (present.size < kept.size) parts.log("${kept.size - present.size} kept tray files were gone", null)
            picked.update { withPicked(present, it) }
            picked.collect { saved[KEPT_TRAY] = keptTrayOf(it) }
        }
    }

    fun open() {
        sheet.value = true
    }

    fun close() {
        sheet.value = false
    }

    /** "Send as files": images go as their own bytes, as documents. */
    fun sendAsFiles(on: Boolean) {
        asFiles.value = on
        leftOut.value = null
        refused.value = null
    }

    /**
     * The items [uris] name, from [from], after those picked already, the first ten in all; one the phone cannot
     * read, or whose provider does not describe it in time, and each past the ten, is logged, and the files the chat
     * made of those past it deleted. A paste's, the keyboard's and a share's are other apps' items, weighed and
     * copied as they land, once the chat's record is read (landingOf), while [grant], the keyboard's commit, keeps
     * its read grant: the platform revokes it once that is collected. Each such landing waits its turn behind those
     * before it ([landings], [inTurn]), so it counts the tray's room once they have landed; the owner's own picks,
     * never copied as they land, wait for none. The first item past what its copy may hold shows as its line
     * ([leftOut]).
     */
    fun add(
        uris: List<String>,
        from: PickedFrom,
        grant: Any? = null,
    ) {
        if (uris.isEmpty()) return
        val queued = copiedOnLanding(from)
        scope.launch {
            if (queued && !inTurn(landings, uris.size, parts.log)) return@launch
            try {
                val landing = landingOf(parts, uris, from, limit) { picked.value.size }
                landing.tooBig?.let {
                    leftOut.value = it
                    refused.value = null
                }
                val kept = trayWith(picked.value, landing.landed, parts.log)
                picked.value = kept
                forget(landing.landed - kept.toSet())
            } finally {
                if (queued) landings.unlock()
            }
            Reference.reachabilityFence(grant)
        }
    }

    /** Paste: the clipboard's image or file, none when it holds neither or the clip refused what it holds. */
    fun paste() {
        val uri = parts.clip.media()
        if (uri == null) {
            parts.log("Paste took nothing from the clipboard", null)
            return
        }
        add(listOf(uri), PickedFrom.PASTE)
    }

    /** Items the embedded Photo Picker let go, by their [uris]: they leave the tray. */
    fun unpick(uris: List<String>) {
        val gone = uris.toSet()
        picked.update { items -> items.filterNot { it.from == PickedFrom.PHOTOS && it.uri in gone } }
        leftOut.value = null
        refused.value = null
    }

    /** The tray's ✕: [id] leaves, its file with it when the chat made it. */
    fun remove(id: String) {
        val gone = picked.value.filter { it.id == id }
        picked.update { items -> items.filterNot { it.id == id } }
        leftOut.value = null
        refused.value = null
        forget(gone)
    }

    /**
     * Edit on an outbox item with [attachments] (design section 13.6): each staged file copied, then the item taken
     * out by [withdraw], which lets its staged files go; the copies come back to the tray once it left, and are
     * deleted when it stayed. Whether it left.
     */
    suspend fun takeBack(
        attachments: List<OutboxAttachment>,
        withdraw: suspend () -> Boolean,
    ): Boolean {
        val copies = if (attachments.isEmpty()) emptyList() else copiesOf(parts, attachments) ?: return false
        val left = withdraw()
        if (left) picked.update { withPicked(it, copies) } else forget(copies)
        return left
    }

    /**
     * The chat closed: the tray empties, and the files the chat made for it, a camera's captures and Edit's copies,
     * are deleted on the app's scope, which outlives the chat's.
     */
    fun release() {
        val owned = picked.value.filter(::ownsFile)
        picked.value = emptyList()
        if (owned.isEmpty()) return
        parts.background.launch(io) { owned.forEach { File(URI(it.uri)).delete() } }
    }

    /**
     * "Send {n}" and send with the tray full: every item that may go, as one `msg` captioned with the composer's
     * words, under the held reply's quote as Send would put it (replyWith, composedOf): none with no words, nor ahead
     * of a command's words, which go as the caption and leave the reply held, as tooLong weighs them. The tray and
     * the field empty once the session took it, and that reply is put down, then [onTaken]. One send runs at a time. A
     * caption past what one message carries, the quote with it, sends and makes nothing, and the tray, the field and
     * the reply stay (ChatComposer.tooLong says why).
     */
    fun send(onTaken: () -> Unit) {
        val chosen = sendableOf(picked.value, asFiles.value, sendLimitOf(limit.value))
        val words = composer.field.value.text
        val held = replyWith(words, composer.reply.held.value, composer.commands())
        val caption = composedOf(held, words)
        if (chosen.isEmpty() || !requests.carries(caption) || !sending.compareAndSet(false, true)) return
        val files = asFiles.value
        val before = leftOut.value
        scope.launch {
            try {
                val made = maker.make(chosen, files)
                if (made is Made.TooBigMade) refused.value = made.tooBig
                val staged = (made as? Made.Staged)?.attachments ?: return@launch
                if (!sentStaged(parts, requests, staged, caption.trim())) return@launch
                picked.update { it - chosen.toSet() }
                leftOut.compareAndSet(before, null)
                refused.value = null
                sheet.value = false
                forget(chosen)
                composer.emptyIf(words, held)
                onTaken()
            } finally {
                sending.set(false)
            }
        }
    }

    private fun forget(items: List<Picked>) {
        val owned = items.filter(::ownsFile)
        if (owned.isEmpty()) return
        scope.launch(io) { owned.forEach { File(URI(it.uri)).delete() } }
    }
}

/**
 * One `msg` of [attachments] captioned [caption] through [requests]: whether its session took it. One that ended as
 * it went lets the staged copies go, logged, and the tray keeps what was picked.
 */
private suspend fun sentStaged(
    parts: ChatParts,
    requests: ChatRequests,
    attachments: List<OutboxAttachment>,
    caption: String,
): Boolean {
    val request = ClientEvent.Msg(parts.newId(), parts.profileId, caption, attachments.map { it.attachId })
    val taken = requests.send(request, attachments)
    if (!taken) {
        parts.log("The attachments were not taken: the chat's session ended", null)
        parts.files.release(attachments.map { it.source })
    }
    return taken
}

/**
 * Each of [attachments]' staged files copied back for the tray; none when one could not be, logged, which leaves the
 * item in the outbox and no copy behind.
 */
private suspend fun copiesOf(
    parts: ChatParts,
    attachments: List<OutboxAttachment>,
): List<Picked>? =
    withContext(parts.io) {
        val copies = mutableListOf<Picked>()
        try {
            attachments.forEach { copies += copyBack(it, parts.scratch(), parts.newId()) }
            copies
        } catch (unreadable: IOException) {
            parts.log("Edit could not copy an attachment back; the item stays in the outbox", unreadable)
            copies.forEach { File(URI(it.uri)).delete() }
            null
        }
    }

/**
 * Whether a landing of another app's [count] items has its turn, [landings] held, once those before it have landed,
 * waited for at most [LANDING_WAIT_MILLIS]; one that waited past that is told to [log], and has none.
 */
private suspend fun inTurn(
    landings: Mutex,
    count: Int,
    log: (String, Throwable?) -> Unit,
): Boolean {
    val turn = withTimeoutOrNull(LANDING_WAIT_MILLIS) { landings.lock() }
    if (turn == null) log("A landing of $count pasted, typed-in or shared items waited past its time; left out", null)
    return turn != null
}

/**
 * What a send and the tray hold an item to, one bound for both: the daemon's limit as [read] states it, above
 * [LANDING_MAX_BYTES] or below, or that bound of the app's own until it has stated one (the owner, 2026-10-11).
 */
private fun sendLimitOf(read: MediaLimit?): Long = read?.maxBytes ?: LANDING_MAX_BYTES

/** The tray [held] with [landed] after it, the first ten in all (withPicked); how many came past is told to [log]. */
private fun trayWith(
    held: List<Picked>,
    landed: List<Picked>,
    log: (String, Throwable?) -> Unit,
): List<Picked> {
    val kept = withPicked(held, landed)
    val past = (held + landed).distinctBy { it.uri }.size - kept.size
    if (past > 0) log("$past picked past the ten a send takes were left out", null)
    return kept
}

/**
 * An outbox item's [attachment], its staged file copied to [copy], as a picked item again under [id] (Edit); a copy
 * that fails part-way is deleted before the IOException goes on.
 */
private fun copyBack(
    attachment: OutboxAttachment,
    copy: File,
    id: String,
): Picked {
    var copied = false
    try {
        File(attachment.source).copyTo(copy, overwrite = true)
        copied = true
    } finally {
        if (!copied) copy.delete()
    }
    val kind = if (attachment.kind == AttachKind.AUDIO) PickedKind.VOICE else pickedKindOf(attachment.mime)
    val name = attachment.name ?: copy.name
    return Picked(id, copy.toURI().toString(), kind, attachment.mime, name, attachment.sizeBytes, PickedFrom.OUTBOX)
}
