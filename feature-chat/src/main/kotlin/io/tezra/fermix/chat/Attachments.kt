package io.tezra.fermix.chat

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.session.MAX_ATTACHMENTS

/**
 * Where a picked item came from (design section 8.5, "Sources"); [OUTBOX] is an attachment an outbox item's Edit
 * returned to the composer (design section 13.6), and [SHARE] one another app shared into the chat (section 13.6,
 * "Share into Fermix"). A camera's capture, a paste's, the keyboard's and a share's copies and an outbox item's copy
 * are files the chat made, which it deletes once they leave the tray ([ownsFile]).
 */
enum class PickedFrom { PHOTOS, CAMERA, FILES, PASTE, KEYBOARD, OUTBOX, SHARE }

/** What a picked item is, which decides how it goes; [VOICE] is the owner's voice note, back from the outbox. */
enum class PickedKind { IMAGE, VIDEO, AUDIO, FILE, VOICE }

/**
 * Whether an item from [from] is read through a grant that ends long before Send, with the clip that held it, the
 * keyboard's commit or the activity a share came to: it is copied into a file of the chat's own as it lands in the
 * tray.
 */
fun copiedOnLanding(from: PickedFrom): Boolean =
    from == PickedFrom.PASTE || from == PickedFrom.KEYBOARD || from == PickedFrom.SHARE

/**
 * Whether the chat reads a URI of [scheme] and [authority], as android.net.Uri parses them, as it lands in the tray
 * from [landing], or, none, held in it already (design section 8.5, "Sources"). The Photo Picker's, the documents
 * UI's, the clipboard's, the keyboard's and a share's items are other apps': a `content:` URI whose authority,
 * without the `user@` prefix the ContentResolver strips up to its last `@`, names no provider of the app's own
 * package ([own]), whose checks the app would pass with its own rights. A camera's capture and Edit's copy are
 * files the chat made: a `file:` URI whose canonical path lies under the app's cache ([inCache], asked only of such a
 * URI). An item held is either, a paste's, the keyboard's and a share's being the chat's copies by then. Any other
 * scheme is refused, a scheme compared exactly, as the ContentResolver compares it: `CONTENT:` and `FILE:` are
 * neither.
 */
internal fun mayRead(
    scheme: String?,
    authority: String?,
    landing: PickedFrom?,
    own: (String) -> Boolean,
    inCache: () -> Boolean,
): Boolean {
    val provider = scheme == "content" && isForeign(authority, own)
    val made = { scheme == "file" && inCache() }
    return when (landing) {
        null -> provider || made()
        PickedFrom.CAMERA, PickedFrom.OUTBOX -> made()
        PickedFrom.PHOTOS, PickedFrom.FILES, PickedFrom.PASTE, PickedFrom.KEYBOARD, PickedFrom.SHARE -> provider
    }
}

/** Whether [authority], its `user@` prefix stripped, names a provider at all, and none of the app's own ([own]). */
private fun isForeign(
    authority: String?,
    own: (String) -> Boolean,
): Boolean {
    val name = authority?.substringAfterLast('@').orEmpty()
    return name.isNotEmpty() && !own(name)
}

/** Whether [picked]'s URI names a file the chat made, which goes once it leaves the tray. */
fun ownsFile(picked: Picked): Boolean =
    picked.from == PickedFrom.CAMERA || picked.from == PickedFrom.OUTBOX || copiedOnLanding(picked.from)

/**
 * One item picked for the next send: its content [uri] (or a file's path, a camera capture's), what it is, its
 * type, its [name] as the phone shows it and its size; [id] tells it apart in the tray and the sheet.
 */
data class Picked(
    val id: String,
    val uri: String,
    val kind: PickedKind,
    val mime: String,
    val name: String,
    val sizeBytes: Long,
    val from: PickedFrom,
)

/** An item past the daemon's limit (design section 13.9): "{name} is {size} — the limit is {max}." */
data class TooBig(
    val name: String,
    val sizeBytes: Long,
    val maxBytes: Long,
)

/**
 * The attach sheet and the tray (design sections 8.5 and 13.6): what is [picked], in order, at most ten, whether
 * the sheet shows, whether images go as files ("Send as files"), the first item too big to go, and how many
 * items go with "Send {n}".
 */
data class AttachUi(
    val picked: List<Picked> = emptyList(),
    val sheet: Boolean = false,
    val asFiles: Boolean = false,
    val tooBig: TooBig? = null,
    val sendable: Int = 0,
)

/** What a picked item of type [mime] is. */
fun pickedKindOf(mime: String): PickedKind =
    when {
        mime.startsWith("image/") -> PickedKind.IMAGE
        mime.startsWith("video/") -> PickedKind.VIDEO
        mime.startsWith("audio/") -> PickedKind.AUDIO
        else -> PickedKind.FILE
    }

/** Whether [picked] goes as its own bytes: anything but an image, and an image too when it goes as a file. */
fun goesAsItself(
    picked: Picked,
    asFiles: Boolean,
): Boolean = asFiles || picked.kind != PickedKind.IMAGE

/**
 * What [picked] goes as on the wire (design section 8.5): an image as a JPEG `image` unless it goes as a file; a
 * video as protocol v2's `video`, its own bytes (design section 7, the `attach_begin.kind: video` row); the
 * owner's voice note as `audio`; general audio and every other file as a `document`.
 */
fun attachKindOf(
    picked: Picked,
    asFiles: Boolean,
): AttachKind =
    when {
        picked.kind == PickedKind.IMAGE && !asFiles -> AttachKind.IMAGE
        picked.kind == PickedKind.VIDEO -> AttachKind.VIDEO
        picked.kind == PickedKind.VOICE -> AttachKind.AUDIO
        else -> AttachKind.DOCUMENT
    }

/**
 * The first of [picked] too big to go: one that goes as its own bytes past [maxBytes], the daemon's
 * `caps.max_media_bytes`. An image that goes as a JPEG is held to the limit as it is made, at most a few MB.
 */
fun tooBigOf(
    picked: List<Picked>,
    asFiles: Boolean,
    maxBytes: Long,
): TooBig? =
    picked
        .firstOrNull { goesAsItself(it, asFiles) && it.sizeBytes > maxBytes }
        ?.let { TooBig(it.name, it.sizeBytes, maxBytes) }

/** The items of [picked] that go with "Send {n}": every one but those too big. */
fun sendableOf(
    picked: List<Picked>,
    asFiles: Boolean,
    maxBytes: Long,
): List<Picked> = picked.filterNot { goesAsItself(it, asFiles) && it.sizeBytes > maxBytes }

/** [held] and then [more], an item once by its URI, at most [MAX_ATTACHMENTS] (design section 8.5, "Caps"). */
fun withPicked(
    held: List<Picked>,
    more: List<Picked>,
): List<Picked> = (held + more).distinctBy { it.uri }.take(MAX_ATTACHMENTS)

/** The sheet and tray as they read from [picked], [asFiles] and the daemon's [maxBytes]. */
fun attachUiOf(
    picked: List<Picked>,
    sheet: Boolean,
    asFiles: Boolean,
    maxBytes: Long,
): AttachUi =
    AttachUi(
        picked = picked,
        sheet = sheet,
        asFiles = asFiles,
        tooBig = tooBigOf(picked, asFiles, maxBytes),
        sendable = sendableOf(picked, asFiles, maxBytes).size,
    )
