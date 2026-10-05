package io.tezra.fermix.chat

import io.tezra.fermix.session.MAX_ATTACHMENTS
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/**
 * The most bytes any landing copy holds (a paste's, the keyboard's or a share's), the app's own bound over whatever the
 * chat's record says: the phone's storage is the phone's, and a daemon may state a limit up to Long.MAX_VALUE, or none
 * before its first `hello_ack`. A file's copy holds the daemon's limit when it is lower; an image's holds this much
 * whatever the limit, as it goes as a JPEG made from the copy at Send, held to the limit as it is made, as a picked
 * image is. The design names no such bound; the number is the owner's to settle.
 */
const val LANDING_MAX_BYTES = 128L * 1024 * 1024

/**
 * How long a landing waits for what the chat reads as it opens, its record (the daemon's limit) and its draft, as a
 * share may land in a chat whose model was made a moment before; past that, nothing of it lands.
 */
internal const val READ_WAIT_MILLIS = 10_000L

/**
 * How long a landing takes at most once its turn comes, its providers' descriptions, the chat's record and its copies
 * all within it, and again how long a landing of another app's items waits for its turn behind those before it: past
 * either, what had not landed is left out, logged, a provider's call cancelled and its copy's stream closed. The number
 * is the owner's to settle.
 */
internal const val LANDING_WAIT_MILLIS = 60_000L

/** What lands of the items added: [landed], for the tray, and the first of them too big to go ([tooBig]), if any. */
internal data class Landing(
    val landed: List<Picked>,
    val tooBig: TooBig? = null,
)

/**
 * What a landing has made so far, each item added as it is done, so a landing whose time runs out still lands what it
 * made in time: the items [described], those [copies] holds, the first one past what its copy may hold ([tooBig]) and
 * how many were ([past]), and whether it had begun [copying].
 */
private class LandingMade {
    val described = mutableListOf<Picked>()
    val copies = mutableListOf<Picked>()
    var copying = false
    var tooBig: TooBig? = null
    var past = 0

    /** [picked] past [bound], by the size its provider gave or by the bytes it streamed: never copied whole. */
    fun pastBound(
        picked: Picked,
        bound: Long,
    ) {
        past++
        if (tooBig == null) tooBig = TooBig(picked.name, picked.sizeBytes, bound)
    }

    /** What lands: the copies for items [copied] on landing, else what was described. */
    fun landing(copied: Boolean): Landing = Landing(if (copied) copies.toList() else described.toList(), tooBig)
}

/**
 * [uris] from [from] as they land (ChatAttach.add), within [LANDING_WAIT_MILLIS] in all: described, the first ten, each
 * once; then, as [copiedOnLanding] says, copied ([copiedLanding]) with the tray's room counted from what it [held] by
 * then. A landing whose time runs out lands what it made by then, the rest left out, logged.
 */
internal suspend fun landingOf(
    parts: ChatParts,
    uris: List<String>,
    from: PickedFrom,
    limit: StateFlow<MediaLimit?>,
    held: () -> Int,
): Landing {
    val made = LandingMade()
    val copied = copiedOnLanding(from)
    val inTime =
        withTimeoutOrNull(LANDING_WAIT_MILLIS) {
            val first = uris.distinct().take(MAX_ATTACHMENTS)
            first.forEach { uri -> described(parts, uri, from)?.let(made.described::add) }
            if (copied) copiedLanding(parts, made, limit, held)
        }
    if (inTime == null) {
        val step = if (made.copying) "hand its items over" else "describe its items"
        parts.log("A landing's provider did not $step in time; what had not landed was left out", null)
    }
    return made.landing(copied)
}

/**
 * What the chat's record says of the daemon's limit, once it is read: [maxBytes], `caps.max_media_bytes`, none while
 * the daemon has sent no caps, before its first `hello_ack`.
 */
data class MediaLimit(
    val maxBytes: Long?,
) {
    init {
        require(maxBytes == null || maxBytes >= 0) { "a limit of $maxBytes bytes" }
    }
}

/**
 * [made]'s described items copied into files of the chat's own as they land, one after another, once the chat's
 * record is read: a share may land in a chat whose model was made a moment before, so the record ([limit], none until
 * then) is waited for, at most [READ_WAIT_MILLIS]; past that, none is copied, which is logged. As many as the tray's
 * room, counted from what it [held] once the record is read, are copied, the rest logged and never copied. One past
 * what its copy may hold ([landingBound] of the daemon's limit), by the size its provider gives or by the bytes it
 * streams, is never copied whole: it lands as its line alone, the landing's tooBig. A record whose daemon has sent no
 * caps holds a copy to the app's own bound, as the daemon's limit is held again at Send.
 */
private suspend fun copiedLanding(
    parts: ChatParts,
    made: LandingMade,
    limit: StateFlow<MediaLimit?>,
    held: () -> Int,
) {
    val items = made.described.toList()
    if (items.isEmpty()) return
    val read = withTimeoutOrNull(READ_WAIT_MILLIS) { limit.filterNotNull().first() }
    if (read == null) {
        val lost = "${items.size} pasted, typed-in or shared items were not copied"
        parts.log("The chat's record was not read in time: $lost", null)
        return
    }
    val room = (MAX_ATTACHMENTS - held()).coerceAtLeast(0)
    val (fit, big) = items.partition { it.sizeBytes <= landingBound(it, read.maxBytes) }
    if (fit.size > room) parts.log("${fit.size - room} picked past the ten a send takes were left out", null)
    big.forEach { made.pastBound(it, landingBound(it, read.maxBytes)) }
    made.copying = true
    fit.take(room).forEach { picked ->
        val bound = landingBound(picked, read.maxBytes)
        val copy = ownCopy(parts, picked, bound) ?: return@forEach
        if (copy.sizeBytes <= bound) made.copies += copy else made.pastBound(copy, bound)
    }
    if (made.past > 0) parts.log("${made.past} picked past what a landing copy holds were never copied whole", null)
}

/**
 * The most bytes [picked]'s landing copy holds, always finite: the daemon's limit ([daemon]) under the app's own
 * [LANDING_MAX_BYTES], or that bound alone while the daemon has sent none; an image, which goes as a JPEG made of the
 * copy and held to the limit as it is made, as a picked image is, holds [LANDING_MAX_BYTES] whatever the limit, and
 * "Send as files" then holds its own bytes to the limit in the tray, as it does a picked image's.
 */
private fun landingBound(
    picked: Picked,
    daemon: Long?,
): Long = if (picked.kind == PickedKind.IMAGE || daemon == null) LANDING_MAX_BYTES else minOf(daemon, LANDING_MAX_BYTES)

/**
 * [uri], picked from [from], described under a new id ([bounded]); none, logged, when the phone cannot read it, its
 * grant gone, or when the chat refuses it as it lands (mayRead: an app's own file or provider), or when its provider
 * fails ([providerFailed]). A refusal is logged by the exception's class alone: the platform's own words name the URI
 * whole, its path among it, and another app picks that path.
 */
private suspend fun described(
    parts: ChatParts,
    uri: String,
    from: PickedFrom,
): Picked? =
    try {
        val picked = parts.media.describe(uri, from)
        if (picked == null) parts.log("A picked item could not be read", null)
        picked?.let { bounded(it).copy(id = parts.newId()) }
    } catch (refused: SecurityException) {
        parts.log("A picked item was refused, or its grant is gone: ${refused.javaClass.simpleName}", null)
        null
    } catch (expected: RuntimeException) {
        providerFailed(parts, "A picked item's", expected)
        null
    }

/**
 * [picked] as its provider described it, its name and type held to the app's own bounds, whatever the provider gave:
 * its name as a file's ([fileNameOf], at most NAME_MAX_BYTES), its type a `type/subtype` ([mediaTypeOf], at most 255
 * characters, or bytes), and its kind the type's when the bound changed the type. They go into its `attach_begin`, a
 * header of at most 4,096 bytes, and the chat's saved state, a parcel the binder holds to about 1 MB.
 */
private fun bounded(picked: Picked): Picked {
    val mime = mediaTypeOf(picked.mime)
    val kind = if (mime == picked.mime) picked.kind else pickedKindOf(mime)
    return picked.copy(kind = kind, mime = mime, name = fileNameOf(picked.name))
}

/**
 * [picked]'s own bytes copied into a scratch file the chat owns, at most [maxBytes] and a byte more: the item as the
 * copy names it from then on, its size the copy's, or, past [maxBytes], the item as it was, its size the bytes the copy
 * reached, with no copy kept; none, logged, when it cannot be read or its provider fails ([providerFailed]), a refusal
 * by the exception's class alone, as [described] logs it. A copy that is not kept is deleted, a cancelled one too: the
 * scratch file is named as it is made, inside the block no cancellation stops, since a block's result is dropped when
 * its caller is cancelled as it returns, and a file named only by that result would be left behind. A share's grant is
 * the one the activity holds, which outlives this copy as the share lands while it shows.
 */
private suspend fun ownCopy(
    parts: ChatParts,
    picked: Picked,
    maxBytes: Long,
): Picked? {
    var into: File? = null
    var copy: Picked? = null
    try {
        withContext(NonCancellable + parts.io) { into = parts.scratch() }
        val file = checkNotNull(into) { "a scratch file was made" }
        val size = parts.media.copyAtMost(picked, file, maxBytes)
        val landed = if (size <= maxBytes) file.toURI().toString() else picked.uri
        copy = picked.copy(uri = landed, sizeBytes = size)
    } catch (unreadable: IOException) {
        currentCoroutineContext().ensureActive()
        parts.log("A pasted, typed-in or shared item could not be copied", unreadable)
    } catch (refused: SecurityException) {
        val gone = refused.javaClass.simpleName
        parts.log("A pasted, typed-in or shared item was refused, or its grant is gone: $gone", null)
    } catch (expected: RuntimeException) {
        providerFailed(parts, "A pasted, typed-in or shared item's", expected)
    } finally {
        if (copy == null || copy.sizeBytes > maxBytes) withContext(NonCancellable + parts.io) { into?.delete() }
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
