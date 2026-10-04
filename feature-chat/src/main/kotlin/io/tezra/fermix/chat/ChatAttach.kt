package io.tezra.fermix.chat

import androidx.lifecycle.SavedStateHandle
import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.MAX_ATTACHMENTS
import io.tezra.fermix.session.OutboxAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.lang.ref.Reference
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The most bytes an image's landing copy holds (a paste's, the keyboard's or a share's), when the daemon's limit is
 * lower: an image goes as a JPEG made from that copy at Send, held to the limit as it is made, as a picked image is,
 * so its own bytes may be past the limit, but another app's stream may not fill the phone. The design names no such
 * bound; this one is the app's own.
 */
internal const val IMAGE_LANDING_MAX_BYTES = 128L * 1024 * 1024

/**
 * How long a landing copy waits for the daemon's limit, which the chat's record carries, as a share may land in a chat
 * whose model was made a moment before and has not read its record yet; past that, nothing is copied.
 */
internal const val LIMIT_WAIT_MILLIS = 10_000L

/**
 * The attach sheet and the tray (design sections 8.5 and 13.6): what is picked, from the Photo Picker, the camera,
 * the files, the clipboard ([paste]), the keyboard and another app's share, at most [MAX_ATTACHMENTS] in order, a
 * paste's, the keyboard's and a share's copied as they land, since their read grant ends long before Send; whether
 * the sheet shows; whether images go as files; and Send, which makes and stages each item (AttachMaker) and sends
 * one `msg` with the composer's words as its caption. An item past the daemon's `caps.max_media_bytes` ([maxBytes],
 * none until the chat's record is read) stays in the tray with its line and never goes; one made past it, a JPEG over
 * a small limit, stops the send with that line; and one a paste, the keyboard or a share brings past what its landing
 * copy may hold, the limit or an image's own bound, is never copied whole, and shows as that line alone. The items
 * whose files the chat made are kept in the chat's [saved] state as the tray changes, so a process death keeps them,
 * and come back as the chat opens again while their files are there.
 */
class ChatAttach(
    private val parts: ChatParts,
    private val requests: ChatRequests,
    private val composer: ChatComposer,
    private val scope: CoroutineScope,
    private val maxBytes: StateFlow<Long?>,
    private val saved: SavedStateHandle,
) {
    private val io: CoroutineDispatcher = parts.io

    private val picked = MutableStateFlow<List<Picked>>(emptyList())
    private val sheet = MutableStateFlow(false)
    private val asFiles = MutableStateFlow(false)

    /** The first item too big to go that the tray does not hold: a JPEG made past the limit, or one landing past it. */
    private val leftOut = MutableStateFlow<TooBig?>(null)
    private val sending = AtomicBoolean(false)
    private val maker = AttachMaker(parts, { maxBytes.value ?: Long.MAX_VALUE }, io)

    val ui: StateFlow<AttachUi> =
        combine(picked, sheet, asFiles, maxBytes, leftOut) { items, open, files, max, out ->
            val ui = attachUiOf(items, open, files, max ?: Long.MAX_VALUE)
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
     * read, and each past the ten, is logged, and the files the chat made of those past it deleted. A paste's, the
     * keyboard's and a share's are weighed and copied as they land, once the daemon's limit is known
     * ([copiedLanding]), while [grant], the keyboard's commit, keeps its read grant: the platform revokes it once that
     * is collected. The first of them past what its copy may hold shows as its line ([leftOut]).
     */
    fun add(
        uris: List<String>,
        from: PickedFrom,
        grant: Any? = null,
    ) {
        if (uris.isEmpty()) return
        scope.launch {
            val described = uris.take(MAX_ATTACHMENTS).mapNotNull { described(parts, it, from) }.distinctBy { it.uri }
            val landing =
                if (copiedOnLanding(from)) {
                    copiedLanding(parts, described, maxBytes) { picked.value.size }
                } else {
                    Landing(described)
                }
            Reference.reachabilityFence(grant)
            landing.tooBig?.let { leftOut.value = it }
            val held = picked.value
            val kept = withPicked(held, landing.landed)
            val past = (held + landing.landed).distinctBy { it.uri }.size - kept.size
            if (past > 0) parts.log("$past picked past the ten a send takes were left out", null)
            picked.value = kept
            forget(landing.landed - kept.toSet())
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
     * words; the tray and the field empty once the session took it, then [onTaken]. One send runs at a time.
     */
    fun send(onTaken: () -> Unit) {
        val chosen = sendableOf(picked.value, asFiles.value, maxBytes.value ?: Long.MAX_VALUE)
        if (chosen.isEmpty() || !sending.compareAndSet(false, true)) return
        val words = composer.field.value.text
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
 * An item as its landing copy left it: [picked] names the chat's own copy when it [landed], and is the item as it was,
 * its size the bytes the copy reached past the limit, when it did not.
 */
private data class LandingCopy(
    val picked: Picked,
    val landed: Boolean,
)

/** What lands of the items added: [landed], for the tray, and the first of them too big to go ([tooBig]), if any. */
private data class Landing(
    val landed: List<Picked>,
    val tooBig: TooBig? = null,
)

/**
 * [items] as their landing copies leave them ([landedCopies]), once the daemon's limit is known: a share may land in
 * a chat whose model was made a moment before and has not read its record, so the limit ([maxBytes], none until then)
 * is waited for, at most [LIMIT_WAIT_MILLIS]; past that, none is copied, which is logged. The tray's room is counted
 * from what it [held] once the limit is known.
 */
private suspend fun copiedLanding(
    parts: ChatParts,
    items: List<Picked>,
    maxBytes: StateFlow<Long?>,
    held: () -> Int,
): Landing {
    if (items.isEmpty()) return Landing(emptyList())
    val limit = withTimeoutOrNull(LIMIT_WAIT_MILLIS) { maxBytes.filterNotNull().first() }
    val lost = "${items.size} pasted, typed-in or shared items were not copied"
    if (limit == null) parts.log("The daemon's limit was not known in time: $lost", null)
    val room = (MAX_ATTACHMENTS - held()).coerceAtLeast(0)
    return limit?.let { landedCopies(parts, items, it, room) } ?: Landing(emptyList())
}

/**
 * [items] copied into files of the chat's own as they land, as many as the tray's [room], the rest logged and never
 * copied. One past what its copy may hold ([landingBound] of [limit], the daemon's), by the size its provider gives or
 * by the bytes it streams, is never copied whole: it lands as its line alone, the landing's tooBig.
 */
private suspend fun landedCopies(
    parts: ChatParts,
    items: List<Picked>,
    limit: Long,
    room: Int,
): Landing {
    val (fit, big) = items.partition { it.sizeBytes <= landingBound(it, limit) }
    if (fit.size > room) parts.log("${fit.size - room} picked past the ten a send takes were left out", null)
    val copies = fit.take(room).mapNotNull { ownCopy(parts, it, landingBound(it, limit)) }
    val (landed, streamedPast) = copies.partition { it.landed }
    val past = big + streamedPast.map { it.picked }
    if (past.isNotEmpty()) parts.log("${past.size} picked past what a landing copy holds were never copied whole", null)
    val tooBig = past.firstOrNull()?.let { TooBig(it.name, it.sizeBytes, landingBound(it, limit)) }
    return Landing(landed.map { it.picked }, tooBig)
}

/**
 * The most bytes [picked]'s landing copy holds: the daemon's [limit], or, for an image, which goes as a JPEG made of
 * the copy and held to the limit as it is made, as a picked image is, at least [IMAGE_LANDING_MAX_BYTES]; "Send as
 * files" then holds its own bytes to the limit in the tray, as it does a picked image's.
 */
private fun landingBound(
    picked: Picked,
    limit: Long,
): Long = if (picked.kind == PickedKind.IMAGE) maxOf(limit, IMAGE_LANDING_MAX_BYTES) else limit

/**
 * [uri], picked from [from], described under a new id; none, logged, when the phone cannot read it, its grant gone,
 * or when the chat refuses it as it lands (mayRead: an app's own file or provider), or when its provider fails
 * ([providerFailed]). A refusal is logged by the exception's class alone: the platform's own words name the URI whole,
 * its path among it, and another app picks that path.
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
        parts.log("A picked item was refused, or its grant is gone: ${refused.javaClass.simpleName}", null)
        null
    } catch (expected: RuntimeException) {
        providerFailed(parts, "A picked item's", expected)
        null
    }

/**
 * [picked]'s own bytes copied into a scratch file the chat owns, at most [maxBytes] and a byte more: the item as the
 * copy names it from then on, its size the copy's, or, past [maxBytes], the item as it was with no copy kept; none,
 * logged, when it cannot be read or its provider fails ([providerFailed]), a refusal by the exception's class alone,
 * as [described] logs it. A copy that is not kept is deleted. A share's grant is the one the activity holds, which
 * outlives this copy as the share lands while it shows.
 */
private suspend fun ownCopy(
    parts: ChatParts,
    picked: Picked,
    maxBytes: Long,
): LandingCopy? {
    val into = withContext(parts.io) { parts.scratch() }
    var copy: LandingCopy? = null
    try {
        val size = parts.media.copyAtMost(picked, into, maxBytes)
        copy =
            if (size <= maxBytes) {
                LandingCopy(picked.copy(uri = into.toURI().toString(), sizeBytes = size), landed = true)
            } else {
                LandingCopy(picked.copy(sizeBytes = size), landed = false)
            }
    } catch (unreadable: IOException) {
        parts.log("A pasted, typed-in or shared item could not be copied", unreadable)
    } catch (refused: SecurityException) {
        val gone = refused.javaClass.simpleName
        parts.log("A pasted, typed-in or shared item was refused, or its grant is gone: $gone", null)
    } catch (expected: RuntimeException) {
        providerFailed(parts, "A pasted, typed-in or shared item's", expected)
    } finally {
        if (copy?.landed != true) withContext(NonCancellable + parts.io) { into.delete() }
    }
    return copy
}

/**
 * Another app's provider failed as [whose] item was read: what the binder carries back from it (Parcel.readException
 * and DatabaseUtils.readExceptionFromParcel: an IllegalArgumentException, an IllegalStateException, an
 * UnsupportedOperationException, a NullPointerException, an SQLiteException) is the other app's, so the item is left
 * out, never a stopped app, and logged by the exception's class alone, as its words are the provider's and may name a
 * path. A cancellation is an IllegalStateException too, and goes on.
 */
private suspend fun providerFailed(
    parts: ChatParts,
    whose: String,
    failure: RuntimeException,
) {
    currentCoroutineContext().ensureActive()
    parts.log("$whose provider failed: ${failure.javaClass.simpleName}", null)
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
