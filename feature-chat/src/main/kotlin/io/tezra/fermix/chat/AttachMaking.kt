package io.tezra.fermix.chat

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.session.OutboxAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** What making a send's attachments came to. */
sealed interface Made {
    /** Each staged, in the order picked. */
    data class Staged(
        val attachments: List<OutboxAttachment>,
    ) : Made

    /** One came out past the daemon's limit: nothing goes, and the sheet says which. */
    data class TooBigMade(
        val tooBig: TooBig,
    ) : Made

    /** One could not be read or staged, its grant gone or the disk full among it; the log says which. */
    data object Failed : Made
}

/** One picked item made ready in its scratch [file]: what it goes as, its size and its digest. */
private class Ready(
    val picked: Picked,
    val file: File,
    val kind: AttachKind,
    val prepared: Prepared,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Makes a send's attachments (design section 8.5, "Images"): each picked item made ready in a scratch file
 * (MediaPipeline.prepare: an image a JPEG at most its long-edge cap, without its EXIF; anything else its own
 * bytes), hashed, held to the daemon's [maxBytes], then staged by a new `attach_id`, an image's JPEG kept in the
 * media cache first, so its row draws without a fetch. Every scratch file is deleted when the send stops short, and
 * every file staged for it let go.
 */
internal class AttachMaker(
    private val parts: ChatParts,
    private val maxBytes: () -> Long,
    private val io: CoroutineDispatcher,
) {
    /**
     * [chosen], images as files when [asFiles], made and staged; failed, logged, when one cannot be read (an
     * IOException, or a SecurityException once its read grant is gone or the chat refuses it) or staged.
     */
    suspend fun make(
        chosen: List<Picked>,
        asFiles: Boolean,
    ): Made {
        val ready = mutableListOf<Ready>()
        var made: Made = Made.Failed
        try {
            chosen.forEach { ready += readyOf(it, asFiles) }
            made = madeOf(ready)
        } catch (unreadable: IOException) {
            parts.log("An attachment could not be made ready or staged", unreadable)
        } catch (refused: SecurityException) {
            parts.log("An attachment was refused, or its grant is gone", refused)
        } finally {
            if (made !is Made.Staged) withContext(NonCancellable + io) { ready.forEach { it.file.delete() } }
        }
        return made
    }

    /** [ready] held to the daemon's limit, then staged. */
    private suspend fun madeOf(ready: List<Ready>): Made {
        val limit = maxBytes()
        val big = ready.firstOrNull { it.sizeBytes > limit }
        return if (big != null) Made.TooBigMade(TooBig(big.picked.name, big.sizeBytes, limit)) else staged(ready)
    }

    /** [picked] made ready in a scratch file, which is deleted when it cannot be. */
    private suspend fun readyOf(
        picked: Picked,
        asFiles: Boolean,
    ): Ready {
        val into = withContext(io) { parts.scratch() }
        var ready: Ready? = null
        try {
            val prepared = parts.media.prepare(picked, goesAsItself(picked, asFiles), into)
            val (size, digest) = withContext(io) { into.length() to sha256Of(into) }
            ready = Ready(picked, into, attachKindOf(picked, asFiles), prepared, size, digest)
        } finally {
            if (ready == null) withContext(NonCancellable + io) { into.delete() }
        }
        return checkNotNull(ready)
    }

    /**
     * [ready] staged in order, an image's JPEG kept in the media cache first; failed once the chat is gone, and an
     * IOException on a full disk thrown on, those staged before either let go again.
     */
    private suspend fun staged(ready: List<Ready>): Made {
        val attachments = mutableListOf<OutboxAttachment>()
        try {
            stageEach(ready, attachments)
        } finally {
            val short = attachments.size != ready.size
            if (short) withContext(NonCancellable) { parts.files.release(attachments.map { it.source }) }
        }
        return if (attachments.size == ready.size) Made.Staged(attachments) else Made.Failed
    }

    /** Each of [ready] staged in order into [attachments], until one is not. */
    private suspend fun stageEach(
        ready: List<Ready>,
        attachments: MutableList<OutboxAttachment>,
    ) {
        for (item in ready) {
            if (item.kind == AttachKind.IMAGE) parts.store.keepMedia(item.file, item.sha256)
            attachments += stageAttachment(parts, item.file, item.kind, item.prepared, item.sha256) ?: return
        }
    }
}

/**
 * [file], made ready as [kind] with [prepared]'s type and name and hashing to [sha256], staged under a new
 * `attach_id` (ChatFiles.stage, which moves it): the outbox attachment that names it; none once the chat is gone.
 */
internal suspend fun stageAttachment(
    parts: ChatParts,
    file: File,
    kind: AttachKind,
    prepared: Prepared,
    sha256: String,
): OutboxAttachment? {
    val size = file.length()
    val attachId = parts.newId()
    val source = parts.files.stage(file, attachId)
    if (source == null) parts.log("$attachId was not staged: the chat is gone", null)
    return source?.let { OutboxAttachment(attachId, kind, prepared.mime, size, sha256, prepared.name, it) }
}

/** [file]'s SHA-256 as lowercase hex, read once; the caller runs it off the main thread. */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        DigestInputStream(input, digest).use { it.copyTo(OutputStream.nullOutputStream()) }
    }
    return digest.digest().toHexString()
}
