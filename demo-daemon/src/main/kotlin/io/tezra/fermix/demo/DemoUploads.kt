package io.tezra.fermix.demo

import io.tezra.fermix.protocol.AttachStatus
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent

/** The most uploads one connection runs at once (PROTOCOL.md "Attachments"). */
private const val MAX_UPLOADS = 4

/** The SHA-256 of no bytes: a zero-byte upload announced with it is present at once. */
private val EMPTY_SHA256 = sha256(ByteArray(0)).toHexString()

/**
 * An upload under way: what its `attach_begin` announced, and the announced size's bytes, of which its chunks have
 * [filled] so many, in the one array the stored blob keeps.
 */
private class Upload(
    val begin: ClientEvent.AttachBegin,
) {
    val bytes = ByteArray(begin.sizeBytes.toInt())
    var filled = 0
    var next = 0
}

/**
 * The owner's uploads on one connection (PROTOCOL.md "Attachments"): `attach_begin` is answered `present` for a
 * digest the demo holds and `upload` otherwise, the chunks are taken in order from index 0, and `attach_end` stores
 * the blob once its size and digest match, under the upload's `attach_id`, which a `msg` names. Four run at once at
 * most; one past [MAX_MEDIA_BYTES], or that would take the store, the uploads under way included, past
 * [DEMO_STORE_BYTES], is refused: the demo keeps them in the app's own heap, so each is one array of its announced
 * size, made at its start and kept as the blob. A connection that ends takes its unfinished ones with it, as this
 * object ends with it.
 */
internal class DemoUploads(
    private val connection: DemoConnection,
) {
    private val parts = connection.parts
    private val running = mutableMapOf<String, Upload>()

    fun answer(frame: ClientFrame) {
        when (val event = frame.event) {
            is ClientEvent.AttachBegin -> begin(event)
            is ClientEvent.AttachChunk -> chunk(event, frame.raw)
            is ClientEvent.AttachEnd -> end(event)
            else -> error("${nameOf(event)} is not an upload's")
        }
    }

    private fun begin(begin: ClientEvent.AttachBegin) {
        val held = parts.stored[begin.sha256]
        val refusal =
            when {
                begin.attachId in running -> "upload_exists"
                held != null -> null
                begin.sizeBytes == 0L && begin.sha256 != EMPTY_SHA256 -> "sha256_mismatch"
                begin.sizeBytes > MAX_MEDIA_BYTES -> "media_too_large"
                running.size >= MAX_UPLOADS -> "upload_limit_reached"
                quotaPassedBy(begin.sizeBytes) -> "store_quota_exceeded"
                else -> null
            }
        when {
            refusal != null -> refuse(refusal)
            held != null -> present(begin.attachId, held)
            begin.sizeBytes == 0L -> present(begin.attachId, blobOf(begin, ByteArray(0)))
            else -> started(begin)
        }
    }

    private fun started(begin: ClientEvent.AttachBegin) {
        running[begin.attachId] = Upload(begin)
        connection.send(ServerEvent.AttachStatusEvent(begin.attachId, AttachStatus.UPLOAD))
    }

    private fun chunk(
        chunk: ClientEvent.AttachChunk,
        raw: ByteArray,
    ) {
        val upload = running[chunk.attachId] ?: return refuse("unknown_upload")
        val refusal =
            when {
                chunk.index != upload.next -> "unexpected_chunk"
                upload.filled + raw.size > upload.bytes.size -> "size_exceeded"
                else -> null
            }
        if (refusal != null) return abandon(chunk.attachId, refusal)
        raw.copyInto(upload.bytes, upload.filled)
        upload.filled += raw.size
        upload.next++
    }

    private fun end(end: ClientEvent.AttachEnd) {
        val upload = running.remove(end.attachId) ?: return refuse("unknown_upload")
        val refusal =
            when {
                end.sha256 != upload.begin.sha256 -> "announced_hash_mismatch"
                upload.filled != upload.bytes.size -> "size_mismatch"
                sha256(upload.bytes).toHexString() != end.sha256 -> "sha256_mismatch"
                else -> null
            }
        if (refusal != null) return refuse(refusal)
        present(end.attachId, blobOf(upload.begin, upload.bytes))
    }

    /** [blob] held under [attachId] and by its digest, and the phone told it is `present`. */
    private fun present(
        attachId: String,
        blob: DemoBlob,
    ) {
        parts.attached[attachId] = blob
        parts.stored[blob.ref] = blob
        connection.send(ServerEvent.AttachStatusEvent(attachId, AttachStatus.PRESENT))
    }

    /** Whether [size] more bytes, with the uploads running, would pass the demo's store (`store_quota_exceeded`). */
    private fun quotaPassedBy(size: Long): Boolean {
        val pending = running.values.sumOf { it.begin.sizeBytes }
        return parts.storedBytes() + pending + size > DEMO_STORE_BYTES
    }

    private fun abandon(
        attachId: String,
        code: String,
    ) {
        running.remove(attachId)
        refuse(code)
    }

    /** An upload refused: its code alone, which names no upload (PROTOCOL.md "Errors"). */
    private fun refuse(code: String) = connection.send(ServerEvent.Error(code, "the demo refused the upload: $code"))
}

private fun blobOf(
    begin: ClientEvent.AttachBegin,
    bytes: ByteArray,
): DemoBlob {
    val kind = begin.kind.name.lowercase()
    return DemoBlob(kind, begin.mime, begin.name ?: "upload", bytes)
}
