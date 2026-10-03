package io.tezra.fermix.chat

import io.tezra.fermix.data.isMediaName
import io.tezra.fermix.session.ApprovalAnswer
import io.tezra.fermix.session.FetchedMedia
import io.tezra.fermix.session.MediaMismatchException
import io.tezra.fermix.session.OneShot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * The owner's answer to an approval card (design section 13.5, "Approval poll"): the session sends the card's
 * route itself (Session.answerApproval), so the token never reaches the chat. An answer it does not send, the
 * card gone, expired or answered already, is told to [log]; the card says the rest.
 */
class ChatApprovals(
    private val session: StateFlow<ChatSession?>,
    private val scope: CoroutineScope,
    private val log: (String, Throwable?) -> Unit,
) {
    fun answer(
        approvalId: String,
        approve: Boolean,
    ) {
        require(approvalId.isNotEmpty()) { "an answer names its card" }
        scope.launch {
            val answer = session.value?.answerApproval(approvalId, approve) ?: ApprovalAnswer.NotShown
            if (answer !is ApprovalAnswer.Sent) log("The answer to $approvalId was not sent: $answer", null)
        }
    }
}

/**
 * A link preview's thumbnail (design section 13.5): the media cache's copy of its `image_ref`, or else the
 * blob the daemon serves for it (Session.fetchMedia) into a [scratch] file the cache then takes, its digest
 * checked twice. The phone never fetches a preview's own URL, nor any other address but its daemon's. One
 * thumbnail is fetched at a time, as the daemon serves one blob at a time, so a second ask for the same one
 * finds it cached. The cache names a blob by its SHA-256, which is its ref on the daemon (content-addressed,
 * PROTOCOL.md "Media downloads"); a ref the wire allows that can name no cached blob gets no thumbnail.
 */
class ChatThumbnails(
    private val session: StateFlow<ChatSession?>,
    private val store: ChatStore,
    private val scratch: () -> File,
    private val log: (String, Throwable?) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val one = Mutex()

    /** The thumbnail [ref]'s bytes, none when it is neither cached nor to be had now. */
    suspend fun thumbnail(ref: String): ByteArray? {
        require(ref.isNotEmpty()) { "a thumbnail names its blob" }
        if (!isMediaName(ref)) {
            log("Thumbnail $ref is no SHA-256 the media cache can name", null)
            return null
        }
        return one.withLock { store.media(ref) ?: fetched(ref) }
    }

    /**
     * The blob [ref] through the session into a scratch file, made in the guarded block and kept as it is made:
     * a caller cancelled as it is made, which drops what the block returned, still has it deleted.
     */
    private suspend fun fetched(ref: String): ByteArray? {
        val chat = session.value ?: return null
        var into: File? = null
        val kept =
            try {
                withContext(io) { into = scratch() }
                val file = checkNotNull(into)
                kept(ref, chat.fetchMedia(ref, file), file)
            } catch (mismatch: MediaMismatchException) {
                log("Thumbnail $ref did not match its digest", mismatch)
                null
            } finally {
                withContext(NonCancellable + io) { into?.let { Files.deleteIfExists(it.toPath()) } }
            }
        return kept
    }

    /** The fetched blob's bytes once the media cache took a copy of [into]; none for a fetch that got none. */
    private suspend fun kept(
        ref: String,
        outcome: OneShot<FetchedMedia>,
        into: File,
    ): ByteArray? {
        if (outcome !is OneShot.Answered) {
            log("Thumbnail $ref was not fetched: $outcome", null)
            return null
        }
        val sha256 = outcome.value.sha256
        return if (store.keepMedia(into, sha256)) store.media(sha256) else null
    }
}
