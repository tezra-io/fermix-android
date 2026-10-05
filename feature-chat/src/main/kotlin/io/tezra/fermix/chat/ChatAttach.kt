package io.tezra.fermix.chat

import androidx.lifecycle.SavedStateHandle
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
 * one `msg` with the composer's words as its caption. An item past the daemon's `caps.max_media_bytes` ([limit],
 * none until the chat's record is read) stays in the tray with its line and never goes; one made past it, a JPEG over
 * a small limit, stops the send with that line; and one a paste, the keyboard or a share brings past what its landing
 * copy may hold (Landings' landingBound) is never copied whole, and shows as that line alone. Another app's landings
 * run one at a time ([landings]), so the tray's room is counted once those before have landed, each waiting its turn
 * and then landing within [LANDING_WAIT_MILLIS]; the owner's own picks wait behind none of them. The items whose files
 * the chat made are kept in the chat's [saved] state as the tray changes, so a process death keeps them, and come back
 * as the chat opens again while their files are there.
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

    /** The first item too big to go that the tray does not hold: a JPEG made past the limit, or one landing past it. */
    private val leftOut = MutableStateFlow<TooBig?>(null)
    private val sending = AtomicBoolean(false)
    private val maker = AttachMaker(parts, { sendLimitOf(limit) }, io)

    /** Another app's landings one at a time: each counts the tray's room once those before it have landed. */
    private val landings = Mutex()

    val ui: StateFlow<AttachUi> =
        combine(picked, sheet, asFiles, limit, leftOut) { items, open, files, read, out ->
            val ui = attachUiOf(items, open, files, read?.maxBytes ?: Long.MAX_VALUE)
            if (ui.tooBig == null && out != null) ui.copy(tooBig = out) else ui
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
                landing.tooBig?.let { leftOut.value = it }
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
    }

    /** The tray's ✕: [id] leaves, its file with it when the chat made it. */
    fun remove(id: String) {
        val gone = picked.value.filter { it.id == id }
        picked.update { items -> items.filterNot { it.id == id } }
        leftOut.value = null
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
     * words; the tray and the field empty once the session took it, then [onTaken]. One send runs at a time. A caption
     * past what one message carries sends and makes nothing, and the tray and the field stay (ChatComposer.tooLong).
     */
    fun send(onTaken: () -> Unit) {
        val chosen = sendableOf(picked.value, asFiles.value, sendLimitOf(limit))
        val words = composer.field.value.text
        if (chosen.isEmpty() || !requests.carries(words) || !sending.compareAndSet(false, true)) return
        val files = asFiles.value
        scope.launch {
            try {
                val made = maker.make(chosen, files)
                if (made is Made.TooBigMade) leftOut.value = made.tooBig
                val staged = (made as? Made.Staged)?.attachments ?: return@launch
                if (!sentStaged(parts, requests, staged, words.trim())) return@launch
                picked.update { it - chosen.toSet() }
                sheet.value = false
                forget(chosen)
                composer.emptyIf(words)
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

/** What a send is held to: the daemon's limit ([limit]), none while it has sent none, which refuses past its own. */
private fun sendLimitOf(limit: StateFlow<MediaLimit?>): Long = limit.value?.maxBytes ?: Long.MAX_VALUE

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
