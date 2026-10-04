package io.tezra.fermix.chat

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** Where a blob is copied for another app to open or take, under the cache, as the FileProvider serves it. */
private const val SHARED_DIRECTORY = "shared"

/** The most blobs kept copied for other apps at once; the oldest goes as another is copied. */
private const val MAX_SHARED = 8

/**
 * Where [media] is copied for another app: under [cache]'s shared directory, in the directory its cache name gives
 * ([sharedDirectoryName]), as the file its name gives ([fileNameOf]), the oldest copies past [MAX_SHARED] deleted
 * first, a link among them deleted itself, never followed. No string from the wire names the path, and the file is
 * checked to lie under the shared directory before anything is deleted or made: an IOException that names neither
 * when it does not. Run off the main thread.
 */
@OptIn(ExperimentalPathApi::class)
internal fun sharedFile(
    cache: File,
    media: ShownMedia,
): File {
    val shared = File(cache, SHARED_DIRECTORY)
    val directory = File(shared, sharedDirectoryName(media.cacheName))
    val file = File(directory, fileNameOf(media))
    if (!liesUnder(file, shared)) throw IOException("a shared copy's path leads out of the shared directory")
    val held = shared.listFiles().orEmpty().sortedBy { it.lastModified() }
    held.dropLast(MAX_SHARED - 1).forEach { it.toPath().deleteRecursively() }
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("a shared copy's directory could not be made")
    return file
}

/** The hex characters a shared copy's directory is named by: enough to tell the copies kept apart. */
private const val SHARED_NAME_CHARS = 16

/**
 * The directory a shared copy of the blob [cacheName] goes in: the first 16 lowercase hex characters of the SHA-256
 * of its UTF-8 bytes, one rule for a digest and a ref alike, as PROTOCOL.md ("Timeline shapes") gives a
 * `media_refs[]` entry's `ref` no form, and `sha256` is optional.
 */
internal fun sharedDirectoryName(cacheName: String): String {
    require(cacheName.isNotEmpty()) { "a blob's cache name is empty" }
    val digest = MessageDigest.getInstance("SHA-256").digest(cacheName.encodeToByteArray())
    return digest.toHexString().take(SHARED_NAME_CHARS)
}

/**
 * Whether [file]'s canonical path lies under [directory]'s, and is not [directory] itself: where a path the app
 * built from a name it was handed must lie before the app reads or writes there. Making a path canonical resolves
 * its `..` and its symbolic links, which reads the filesystem: off the main thread. The paths are compared as
 * strings, never as java.nio paths, which ART refuses to make of a name with a lone surrogate.
 */
internal fun liesUnder(
    file: File,
    directory: File,
): Boolean {
    val path = file.canonicalPath
    val root = directory.canonicalPath
    return path != root && path.startsWith(root.removeSuffix(File.separator) + File.separator)
}

/** The most UTF-8 bytes a file's name takes on the phone's filesystems, ext4 and f2fs (NAME_MAX). */
private const val NAME_MAX_BYTES = 255

/**
 * [media]'s name as a file of its own: its last part after any `/` or `\`, its control characters and its lone
 * surrogates dropped, which no filesystem holds and ART's path calls throw on, past [NAME_MAX_BYTES] its last
 * characters that fit, which keeps its extension, and trimmed; [UNNAMED_FILE] when that leaves nothing, `.` or `..`.
 */
internal fun fileNameOf(media: ShownMedia): String {
    val last =
        media.name
            .orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
    val name = lastBytes(nameCharacters(last), NAME_MAX_BYTES).trim()
    return if (name.isEmpty() || name == "." || name == "..") UNNAMED_FILE else name
}

/** [words]' code points but its control characters and its lone surrogates: well-formed UTF-16, as a name holds. */
private fun nameCharacters(words: String): String {
    val kept = StringBuilder(words.length)
    words.codePoints().forEach { point ->
        val lone = Character.getType(point) == Character.SURROGATE.toInt()
        if (!lone && !Character.isISOControl(point)) kept.appendCodePoint(point)
    }
    return kept.toString()
}

/** [words]' last characters that fit in [maxBytes] of UTF-8, cut between two code points. */
private fun lastBytes(
    words: String,
    maxBytes: Int,
): String {
    var start = words.length
    var bytes = 0
    // A code point a step back from the end: the loop ends within words.length steps.
    while (start > 0) {
        val point = words.codePointBefore(start)
        val size = Character.toString(point).encodeToByteArray().size
        if (bytes + size > maxBytes) break
        bytes += size
        start -= Character.charCount(point)
    }
    return words.substring(start)
}

/** The file name a blob that names none is shared and saved under. */
private const val UNNAMED_FILE = "file"
