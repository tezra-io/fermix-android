package io.tezra.fermix.chat

import io.tezra.fermix.data.isMediaName
import io.tezra.fermix.session.ApprovalAnswer
import io.tezra.fermix.session.FetchedMedia
import io.tezra.fermix.session.MediaMismatchException
import io.tezra.fermix.session.OneShot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/** The most placeholder colours a chat keeps, one a blob, the oldest let go first. */
private const val MAX_COLOURS = 256

/** The `error{ref}` code of a blob the daemon no longer holds (design section 13.9, "No longer on {host}"). */
const val MEDIA_GONE = "media_gone"

/** What the chat has of a blob a bubble shows. */
sealed interface Blob {
    /** Its bytes. */
    class Bytes(
        val bytes: ByteArray,
    ) : Blob

    /** Copied into [file]. */
    class InFile(
        val file: File,
    ) : Blob

    /** The daemon no longer holds it (`media_gone`): "No longer on {host}". */
    data object Gone : Blob

    /** Not to be had now: no connection, or a fetch that failed; asked again as a connection comes. */
    data object Missing : Blob
}

/**
 * The blobs a chat shows (design sections 8.5 and 13.5): a link preview's thumbnail, a message's image, document
 * and voice note. An outbox item's comes from its staged file; any other from the media cache, or else from the
 * daemon (Session.fetchMedia) into a [scratch] file the cache then takes, its digest checked twice. While one
 * streams, its first chunk decodes to the colour its placeholder shows ([colours]; MediaPipeline.dominantColour),
 * as the wire carries no colour of its own. The phone never fetches any address but its daemon's. One blob is
 * fetched at a time, as the daemon serves one blob at a time, so a second ask for the same one finds it cached.
 * The cache names a blob by its SHA-256, which is its ref on the daemon (content-addressed, PROTOCOL.md "Media
 * downloads"); a thumbnail ref the wire allows that can name no cached blob gets no thumbnail.
 */
class ChatBlobs(
    private val session: StateFlow<ChatSession?>,
    private val parts: ChatParts,
) {
    private val io: CoroutineDispatcher = parts.io

    private val one = Mutex()
    private val shades = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Each streaming blob's placeholder colour, as an ARGB int, by its cache name. */
    val colours: StateFlow<Map<String, Int>> = shades.asStateFlow()

    /** The thumbnail [ref]'s bytes, none when it is neither cached nor to be had now. */
    suspend fun thumbnail(ref: String): ByteArray? {
        require(ref.isNotEmpty()) { "a thumbnail names its blob" }
        if (!isMediaName(ref)) {
            parts.log("Thumbnail $ref is no SHA-256 the media cache can name", null)
            return null
        }
        val held = parts.store.media(ref)?.let { Blob.Bytes(it) } ?: cachedBytes(fetched(ref, ref))
        return (held as? Blob.Bytes)?.bytes
    }

    /** [media]'s bytes: an outbox item's from its staged file, any other's from the cache or the daemon. */
    suspend fun bytes(media: ShownMedia): Blob {
        val local = media.local?.let { withContext(io) { File(it).takeIf(File::isFile)?.readBytes() } }
        val cached = local ?: cacheNameOf(media)?.let { parts.store.media(it) }
        return cached?.let { Blob.Bytes(it) } ?: cachedBytes(fetched(media.ref, media.cacheName))
    }

    /** What [fetch] came to, as the cache's bytes. */
    private suspend fun cachedBytes(fetch: Fetch): Blob =
        when (fetch) {
            is Fetch.Cached -> parts.store.media(fetch.sha256)?.let { Blob.Bytes(it) } ?: Blob.Missing
            Fetch.Gone -> Blob.Gone
            Fetch.Missing -> Blob.Missing
        }

    /**
     * [media] copied into [into], for a player or another app to open: an outbox item's staged file, or the
     * cache's copy, fetched from the daemon first when the cache does not hold it.
     */
    suspend fun file(
        media: ShownMedia,
        into: File,
    ): Blob {
        val local = media.local?.let { withContext(io) { copied(File(it), into) } } == true
        val exported = local || cacheNameOf(media)?.let { parts.files.export(it, into) } == true
        if (exported) return Blob.InFile(into)
        return when (val held = fetched(media.ref, media.cacheName)) {
            is Fetch.Cached -> if (parts.files.export(held.sha256, into)) Blob.InFile(into) else Blob.Missing
            Fetch.Gone -> Blob.Gone
            Fetch.Missing -> Blob.Missing
        }
    }

    /**
     * The blob [ref] through the session, one fetch at a time (scratchFetch); none without a session. The cache is
     * looked in again once the fetch's turn came, so an ask that waited for another of the same blob finds it there.
     */
    private suspend fun fetched(
        ref: String,
        name: String,
    ): Fetch =
        one.withLock {
            val cached = isMediaName(name) && parts.store.media(name) != null
            val chat = session.value
            when {
                cached -> Fetch.Cached(name)
                chat == null -> Fetch.Missing
                else -> scratchFetch(chat, ref, name)
            }
        }

    /**
     * The blob [ref] through [chat] into a scratch file, made in the guarded block and kept as it is made: a caller
     * cancelled as it is made, which drops what the block returned, still has it deleted.
     */
    private suspend fun scratchFetch(
        chat: ChatSession,
        ref: String,
        name: String,
    ): Fetch {
        var into: File? = null
        return try {
            withContext(io) { into = parts.scratch() }
            val file = checkNotNull(into)
            kept(ref, streamed(chat, ref, name, file), file)
        } catch (mismatch: MediaMismatchException) {
            parts.log("Blob $ref did not match its digest", mismatch)
            Fetch.Missing
        } finally {
            withContext(NonCancellable + io) { into?.let { Files.deleteIfExists(it.toPath()) } }
        }
    }

    /** [chat]'s fetch of [ref] into [into], its first chunk decoding to [name]'s placeholder as the rest streams. */
    private suspend fun streamed(
        chat: ChatSession,
        ref: String,
        name: String,
        into: File,
    ): OneShot<FetchedMedia> =
        coroutineScope {
            val first = CompletableDeferred<ByteArray>()
            val shading = launch(io) { shade(name, first.await()) }
            try {
                chat.fetchMedia(ref, into) { first.complete(it) }
            } finally {
                if (!first.isCompleted) shading.cancel()
            }
        }

    /** The fetched blob once the media cache took a copy of [into]; gone or missing for a fetch that got none. */
    private suspend fun kept(
        ref: String,
        outcome: OneShot<FetchedMedia>,
        into: File,
    ): Fetch {
        if (outcome !is OneShot.Answered) {
            parts.log("Blob $ref was not fetched: $outcome", null)
            return if (outcome == OneShot.Refused(MEDIA_GONE)) Fetch.Gone else Fetch.Missing
        }
        val sha256 = outcome.value.sha256
        return if (parts.store.keepMedia(into, sha256)) Fetch.Cached(sha256) else Fetch.Missing
    }

    private fun shade(
        name: String,
        first: ByteArray,
    ) {
        val colour = parts.media.dominantColour(first) ?: return
        shades.update {
            (it - name + (name to colour)).entries.toList().takeLast(MAX_COLOURS).associate { e ->
                e.toPair()
            }
        }
    }
}

/** What a fetch through the daemon came to. */
private sealed interface Fetch {
    data class Cached(
        val sha256: String,
    ) : Fetch

    data object Gone : Fetch

    data object Missing : Fetch
}

/**
 * The name the media cache may hold [media] under: its digest, or else its ref, which names a blob by its digest
 * on the daemon, as a row's `media_refs` entry carries no `sha256` of its own (PROTOCOL.md "Timeline shapes"); none
 * for a ref no cache could name, which is fetched each time.
 */
private fun cacheNameOf(media: ShownMedia): String? = media.cacheName.takeIf(::isMediaName)

/** Copies [from] into [into]: whether [from] was there to copy. */
private fun copied(
    from: File,
    into: File,
): Boolean {
    if (!from.isFile) return false
    from.copyTo(into, overwrite = true)
    return true
}
