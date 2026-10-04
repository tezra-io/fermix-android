package io.tezra.fermix.chat

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.MAX_ATTACHMENTS
import io.tezra.fermix.session.OutboxAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.ref.Reference
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The attach sheet and the tray (design sections 8.5 and 13.6): what is picked, from the Photo Picker, the camera,
 * the files, the clipboard ([paste]) and the keyboard, at most [MAX_ATTACHMENTS] in order, a paste's and the
 * keyboard's copied as they land, since their read grant ends long before Send; whether the sheet shows;
 * whether images go as files; and Send, which makes and stages each item (AttachMaker) and sends one `msg` with
 * the composer's words as its caption. An item past the daemon's `caps.max_media_bytes` ([maxBytes]) stays in the
 * tray with its line and never goes; one made past it, a JPEG over a small limit, stops the send with that line.
 */
class ChatAttach(
    private val parts: ChatParts,
    private val requests: ChatRequests,
    private val composer: ChatComposer,
    private val scope: CoroutineScope,
    private val maxBytes: StateFlow<Long>,
) {
    private val io: CoroutineDispatcher = parts.io

    private val picked = MutableStateFlow<List<Picked>>(emptyList())
    private val sheet = MutableStateFlow(false)
    private val asFiles = MutableStateFlow(false)
    private val madeTooBig = MutableStateFlow<TooBig?>(null)
    private val sending = AtomicBoolean(false)
    private val maker = AttachMaker(parts, { maxBytes.value }, io)

    val ui: StateFlow<AttachUi> =
        combine(picked, sheet, asFiles, maxBytes, madeTooBig) { items, open, files, max, made ->
            val ui = attachUiOf(items, open, files, max)
            if (ui.tooBig == null && made != null) ui.copy(tooBig = made) else ui
        }.stateIn(scope, SharingStarted.Eagerly, AttachUi())

    fun open() {
        sheet.value = true
    }

    fun close() {
        sheet.value = false
    }

    /** "Send as files": images go as their own bytes, as documents. */
    fun sendAsFiles(on: Boolean) {
        asFiles.value = on
        madeTooBig.value = null
    }

    /**
     * The items [uris] name, from [from], after those picked already, the first ten in all; one the phone cannot
     * read, and each past the ten, is logged. A paste's and the keyboard's are copied as they land (copiedOnLanding),
     * while [grant], the keyboard's commit, keeps its read grant: the platform revokes it once that is collected.
     */
    fun add(
        uris: List<String>,
        from: PickedFrom,
        grant: Any? = null,
    ) {
        if (uris.isEmpty()) return
        scope.launch {
            val landed = uris.take(MAX_ATTACHMENTS).mapNotNull { landed(parts, it, from) }
            Reference.reachabilityFence(grant)
            val held = picked.value
            val kept = withPicked(held, landed)
            val past = (held + landed).distinctBy { it.uri }.size - kept.size
            if (past > 0) parts.log("$past picked past the ten a send takes were left out", null)
            picked.value = kept
            forget(landed - kept.toSet())
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
        madeTooBig.value = null
    }

    /** The tray's ✕: [id] leaves, its file with it when the chat made it. */
    fun remove(id: String) {
        val gone = picked.value.filter { it.id == id }
        picked.update { items -> items.filterNot { it.id == id } }
        madeTooBig.value = null
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
     * words; the tray and the field empty once the session took it, then [onTaken]. One send runs at a time.
     */
    fun send(onTaken: () -> Unit) {
        val chosen = sendableOf(picked.value, asFiles.value, maxBytes.value)
        if (chosen.isEmpty() || !sending.compareAndSet(false, true)) return
        val words = composer.field.value.text
        val files = asFiles.value
        scope.launch {
            try {
                val made = maker.make(chosen, files)
                if (made is Made.TooBigMade) madeTooBig.value = made.tooBig
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
 * [uri], picked from [from], described under a new id, and copied into a file the chat owns when its grant ends
 * before Send (copiedOnLanding); none, logged, when the phone cannot read it.
 */
private suspend fun landed(
    parts: ChatParts,
    uri: String,
    from: PickedFrom,
): Picked? {
    val picked = described(parts, uri, from) ?: return null
    return if (copiedOnLanding(from)) ownCopy(parts, picked) else picked
}

/**
 * [uri], picked from [from], described under a new id; none, logged, when the phone cannot read it, its grant gone,
 * or when the chat refuses it as it lands (mayRead: an app's own file or provider), which is logged by its scheme and
 * authority alone.
 */
private suspend fun described(
    parts: ChatParts,
    uri: String,
    from: PickedFrom,
): Picked? =
    try {
        val picked = parts.media.describe(uri, from)
        if (picked == null) parts.log("A picked item could not be read", null)
        picked?.copy(id = parts.newId())
    } catch (refused: SecurityException) {
        parts.log("A picked item was refused, or its grant is gone", refused)
        null
    }

/**
 * [picked]'s own bytes copied into a scratch file the chat owns, which the item names from then on, its size the
 * copy's; none, logged, when it cannot be read, and its part-made copy deleted.
 */
private suspend fun ownCopy(
    parts: ChatParts,
    picked: Picked,
): Picked? {
    val into = withContext(parts.io) { parts.scratch() }
    var copy: Picked? = null
    try {
        parts.media.prepare(picked, asFile = true, into)
        val size = withContext(parts.io) { into.length() }
        copy = picked.copy(uri = into.toURI().toString(), sizeBytes = size)
    } catch (unreadable: IOException) {
        parts.log("A pasted or typed-in item could not be copied", unreadable)
    } catch (refused: SecurityException) {
        parts.log("A pasted or typed-in item was refused, or its grant is gone", refused)
    } finally {
        if (copy == null) withContext(NonCancellable + parts.io) { into.delete() }
    }
    return copy
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
