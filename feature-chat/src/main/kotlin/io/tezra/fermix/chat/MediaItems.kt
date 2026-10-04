package io.tezra.fermix.chat

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.MediaRef
import io.tezra.fermix.session.OutboxAttachment
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.UploadProgress
import io.tezra.fermix.session.UploadStage

/** The voice note's type on the wire (D15: OGG/Opus), and the name it goes up under. */
const val VOICE_MIME = "audio/ogg"
const val VOICE_NAME = "voice-note.ogg"

/** A type for an item nothing names a type for that the phone takes: bytes, sent and shown as a document. */
internal const val UNKNOWN_MIME = "application/octet-stream"

/** The most characters a media type takes: a type and a subtype of at most 127 each, and the slash (RFC 6838). */
private const val MEDIA_TYPE_MAX_CHARS = 255

/** A `type/subtype` as RFC 6838 names them, lower-cased: each a letter or digit, then at most 126 of its others. */
private val MEDIA_TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}")

/**
 * [mime], the daemon's or another app's, as the type an intent and the media store are handed: its `type/subtype`,
 * its parameters dropped, lower-cased, at most [MEDIA_TYPE_MAX_CHARS] characters; [UNKNOWN_MIME] for anything else.
 * The wire holds a type to no form or length (PROTOCOL.md, "Timeline shapes"), and one past the binder's buffer
 * would stop the app as the chooser starts.
 */
internal fun mediaTypeOf(mime: String): String {
    val bare = mime.substringBefore(';').trim()
    if (bare.length > MEDIA_TYPE_MAX_CHARS) return UNKNOWN_MIME
    val type = bare.lowercase()
    return if (MEDIA_TYPE.matches(type)) type else UNKNOWN_MIME
}

/** How a blob shows in its bubble (design section 13.5): an image, a document's row, or a voice note. */
enum class MediaShape { IMAGE, DOCUMENT, VOICE }

/**
 * One blob of a message as the chat draws it: [ref], the daemon's for a row's (media_fetch) and the digest for an
 * outbox item's, which the daemon names it by once it holds it; [sha256], the media cache's name for it, none
 * when the daemon gave none; its shape, type (as [mediaTypeOf] bounds it), size and name; for an outbox item,
 * [local], the staged file the bubble draws from before the daemon has it, and [sent], the ring's share of it gone,
 * none once it is in.
 */
data class ShownMedia(
    val ref: String,
    val sha256: String?,
    val shape: MediaShape,
    val mime: String,
    val sizeBytes: Long,
    val name: String?,
    val local: String? = null,
    val sent: Float? = null,
) {
    /** The media cache's name for it: its digest, or its ref, which names a blob by its digest on the daemon. */
    val cacheName: String get() = sha256 ?: ref
}

/** The one line under an outbox item with attachments (design section 13.9). */
enum class UploadLine {
    /** "Upload interrupted — resumes when connected". */
    INTERRUPTED,

    /** "Already on {host} — sent instantly". */
    DUPLICATE,
}

/**
 * A row's blobs: an image as an image; the owner's audio as their voice note; everything else, the agent's audio
 * and video among it, as a document's row with "Open" (design section 13.5; D14 leaves the general player to M52).
 */
fun rowMedia(
    refs: List<MediaRef>,
    user: Boolean,
): List<ShownMedia> =
    refs.map { ref ->
        val shape =
            when {
                ref.kind == "image" -> MediaShape.IMAGE
                ref.kind == "audio" && user -> MediaShape.VOICE
                else -> MediaShape.DOCUMENT
            }
        ShownMedia(ref.ref, ref.sha256, shape, mediaTypeOf(ref.mime), ref.sizeBytes, ref.filename)
    }

/** An outbox item's attachments, each from its staged file, an image with its ring while it goes up, none failed. */
fun outboxMedia(
    item: OutboxItem,
    uploads: Map<String, UploadProgress>,
): List<ShownMedia> =
    item.attachments.map { attachment ->
        val shape =
            when (attachment.kind) {
                AttachKind.IMAGE -> MediaShape.IMAGE
                AttachKind.AUDIO -> MediaShape.VOICE
                AttachKind.DOCUMENT, AttachKind.VIDEO -> MediaShape.DOCUMENT
            }
        ShownMedia(
            ref = attachment.sha256,
            sha256 = attachment.sha256,
            shape = shape,
            mime = mediaTypeOf(attachment.mime),
            sizeBytes = attachment.sizeBytes,
            name = attachment.name,
            local = attachment.source,
            sent = if (item.failure != null) null else sentShare(attachment, uploads[attachment.attachId]),
        )
    }

/** The ring's share of [attachment] gone up: none once it is in, 0 before its upload started. */
private fun sentShare(
    attachment: OutboxAttachment,
    progress: UploadProgress?,
): Float? {
    val inAlready =
        attachment.uploaded || progress?.stage == UploadStage.UPLOADED || progress?.stage == UploadStage.DUPLICATE
    val size = attachment.sizeBytes.coerceAtLeast(1L)
    return if (inAlready) null else (progress?.sentBytes ?: 0L).toFloat() / size
}

/**
 * The line under an outbox item with attachments: interrupted while one is still to go and its upload was cut, by
 * the connection that carried it or, its upload begun before ([OutboxItem.uploadStarts]), while none is up; sent
 * instantly when the daemon held one already; none otherwise, so an item composed offline reads "Queued".
 */
fun uploadLineOf(
    item: OutboxItem,
    uploads: Map<String, UploadProgress>,
    connected: Boolean,
): UploadLine? {
    val shown = item.attachments.mapNotNull { uploads[it.attachId] }
    val cut = shown.any { it.stage == UploadStage.INTERRUPTED } || (!connected && item.uploadStarts > 0)
    return when {
        item.failure != null -> null
        item.uploading && cut -> UploadLine.INTERRUPTED
        shown.any { it.stage == UploadStage.DUPLICATE } -> UploadLine.DUPLICATE
        else -> null
    }
}

/** How a message's images lay out (design section 13.5): how many cells show, and the "+N" the last one wears. */
data class ImageLayout(
    val shown: Int,
    val more: Int,
)

/** One image fills; two sit side by side; three are one large and two; four or more a 2 × 2, its last "+N". */
fun imageLayoutOf(count: Int): ImageLayout {
    require(count > 0) { "a layout of no image" }
    val shown = minOf(count, GRID_CELLS)
    return ImageLayout(shown, count - shown)
}

/** The cells of the 2 × 2 grid. */
const val GRID_CELLS = 4

/** One image's aspect, width over height, clamped to 3:4…16:9 (design section 13.5); 4:3 before it decodes. */
fun singleAspect(
    width: Int,
    height: Int,
): Float {
    if (width <= 0 || height <= 0) return DEFAULT_ASPECT
    return (width.toFloat() / height).coerceIn(TALLEST_ASPECT, WIDEST_ASPECT)
}

private const val DEFAULT_ASPECT = 4f / 3f
private const val TALLEST_ASPECT = 3f / 4f
private const val WIDEST_ASPECT = 16f / 9f
