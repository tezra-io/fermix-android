package io.tezra.fermix.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** An avatar picture's side, in pixels: three times the Instance header's 72 dp disc on a 2.625 density phone. */
const val AVATAR_SIDE_PX = 256

/** Where the pictures live, beside the records file (InstanceStore). */
internal const val AVATARS_DIRECTORY = "avatars"

private const val PICTURE_SUFFIX = ".jpg"
private const val PART_SUFFIX = ".jpg.part"

/** A write's hand-over ([AvatarPictures.write]) while its part is being made: the part itself once it is handed. */
private object Working

/** A write's hand-over once its caller stopped waiting: no part is handed over after it. */
private object Abandoned

/**
 * The longest edge a photo is scaled to on its way to the square, the app's own bound: the decoder holds the photo at
 * up to twice that on each edge before it cuts the square, some 16 MB at most, so a photo more than 16 times as long as
 * it is wide, which would scale past it, is refused as unreadable. A panorama of 12 : 1 is taken.
 */
private const val MAX_SCALED_EDGE = 4_096L

/**
 * How a photo of some size becomes the picture's square (the owner, 2026-10-10): scaled, up or down, until its
 * short edge is [AVATAR_SIDE_PX], to [width] × [height], then cut to the [AVATAR_SIDE_PX] square at [left], [top],
 * its centre.
 */
data class AvatarCrop(
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
)

/**
 * The square [width] × [height] is cut to ([AvatarCrop]); an IOException for a photo with no pixels, or one so long
 * and thin that its long edge would scale past [MAX_SCALED_EDGE].
 */
fun avatarCrop(
    width: Int,
    height: Int,
): AvatarCrop {
    if (width <= 0 || height <= 0) throw IOException("a photo of $width × $height has no pixels")
    val short = minOf(width, height).toLong()
    val scaledWidth = (width * AVATAR_SIDE_PX.toLong() + short - 1) / short
    val scaledHeight = (height * AVATAR_SIDE_PX.toLong() + short - 1) / short
    val long = maxOf(scaledWidth, scaledHeight)
    if (long > MAX_SCALED_EDGE) throw IOException("a photo of $width × $height scales past $MAX_SCALED_EDGE pixels")
    return AvatarCrop(
        width = scaledWidth.toInt(),
        height = scaledHeight.toInt(),
        left = ((scaledWidth - AVATAR_SIDE_PX) / 2).toInt(),
        top = ((scaledHeight - AVATAR_SIDE_PX) / 2).toInt(),
    )
}

/**
 * The avatar's photo, [uri] as the picker's result names it, read through [resolver] if it is another app's
 * (AGENTS.md: a string from another app never names a path or picks what the app reads): only a `content:` URI whose
 * authority names no provider of the app's own package ([own]), the rule the chat's landings hold to
 * ([isOtherAppsContent]). A `file:` URI, which the resolver would open as a path, the app's own FileProvider, which the
 * app reads with its own rights, and any other scheme are a SecurityException that names the URI's scheme and
 * authority alone, never its path. Nothing is read here: the decoder opens the stream as it decodes.
 */
fun avatarSource(
    resolver: ContentResolver,
    uri: String,
    own: (String) -> Boolean,
): ImageDecoder.Source {
    val parsed = Uri.parse(uri)
    val scheme = parsed.scheme
    val authority = parsed.authority
    if (!isOtherAppsContent(scheme, authority, own)) {
        throw SecurityException("the avatar refuses a ${outsideOf(scheme, authority)}")
    }
    return ImageDecoder.createSource(resolver, parsed)
}

/**
 * The owner's photos of their Fermixes (the owner, 2026-10-10), one file per instance in [directory], `<id>.jpg`: a
 * [AVATAR_SIDE_PX] square cut from the photo's centre ([avatarCrop]), upright as ImageDecoder turns it by its EXIF
 * orientation, and compressed to a JPEG by Bitmap.compress, which writes no EXIF, so the photo's GPS and camera tags
 * stay where they were. Its bounds, as another app's bytes come in: a photo past [MAX_IMAGE_PIXELS] is refused before a
 * pixel is decoded; one not made within [waitMillis], [LANDING_WAIT_MILLIS] but in a test, is given up, as a landing is
 * ([onLandingThreads]), though the give-up closes nothing, as the decoder opens the provider's stream itself, so a
 * provider that stalls holds the [io] thread its decode runs on until it answers or the decoder gives it up; and it has
 * no byte bound of its own, the decoder reading what the pixels need. The file is replaced in one rename, so a reader
 * finds the old picture or the new one, never half of either. The record's `picture_at` says which picture is current;
 * InstanceStore owns the files' going, with their record (its remove, its setPicture for a record gone, a merge's old
 * id and the launch check's sweep), and keeps them in credential-encrypted storage no backup carries, beside the
 * records.
 */
class AvatarPictures(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val waitMillis: Long = LANDING_WAIT_MILLIS,
) {
    init {
        require(waitMillis > 0) { "a picture made within $waitMillis ms" }
    }

    /** [id]'s picture file, which may not exist; a name that is no instance id is refused. */
    fun fileOf(id: String): File {
        require(SHA256_HEX.matches(id)) { "a picture is named by an instance id" }
        return File(directory, id + PICTURE_SUFFIX)
    }

    /**
     * [source]'s image as [id]'s picture, replacing the one before: an IOException for an image that cannot be read,
     * one past [MAX_IMAGE_PIXELS] by its header, or one not made within [waitMillis], which leaves the picture before
     * as it was. [source] is the owner's pick through the platform's ContentResolver ([avatarSource]). The time is the
     * wall's, kept on [io], whatever clock the caller's dispatcher keeps.
     */
    suspend fun write(
        id: String,
        source: ImageDecoder.Source,
    ) {
        val target = fileOf(id)
        withContext(io) {
            val part = awaitPart(source, target)
            if (part == null) throw IOException("the photo was not made a picture within $waitMillis ms")
            place(part, target)
        }
    }

    /**
     * [source] made into a part beside [target] on the landing threads and handed over within [waitMillis], or none.
     * The hand-over and the giving up are one or the other, never both ([Working], [Abandoned]): once the wait ends no
     * part is handed over, a writer that finishes later deleting its own, and a part handed over to a caller cancelled
     * meanwhile goes.
     */
    private suspend fun awaitPart(
        source: ImageDecoder.Source,
        target: File,
    ): File? {
        val handed = AtomicReference<Any>(Working)
        try {
            withTimeoutOrNull(waitMillis) {
                onLandingThreads(io, stop = { handed.compareAndSet(Working, Abandoned) }) {
                    makePart(source, target, handed)
                }
            }
        } catch (cancelled: CancellationException) {
            (handed.getAndSet(Abandoned) as? File)?.delete()
            throw cancelled
        }
        return handed.getAndSet(Abandoned) as? File
    }

    /**
     * [id]'s picture decoded [side] pixels across, or at its file's own [AVATAR_SIDE_PX] when [side] is larger, never
     * scaled up, as whoever draws it scales it to its disc; none while it has no file; an IOException if it cannot be
     * read. Blocking, a file of at most [AVATAR_SIDE_PX] square: its callers, the screens' cache and the conversations'
     * icons, run it off the main thread. The bitmap is a software one, which any canvas draws: the conversations' icon
     * is drawn on a bitmap's own canvas, where ImageDecoder's hardware bitmap throws.
     */
    fun read(
        id: String,
        side: Int,
    ): Bitmap? {
        require(side > 0) { "a picture drawn $side pixels across" }
        val file = fileOf(id)
        if (!file.isFile) return null
        val decoded = minOf(side, AVATAR_SIDE_PX)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            refusePastPixels(info.size)
            decoder.setTargetSize(decoded, decoded)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** Deletes [id]'s picture, if it has one. Blocking: the store calls it on its `io`. */
    internal fun delete(id: String) {
        val file = fileOf(id)
        if (file.exists() && !file.delete()) throw IOException("could not delete $file")
    }

    /** [from]'s picture as [to]'s in one rename, replacing any; nothing when [from] has none. Blocking. */
    internal fun move(
        from: String,
        to: String,
    ) {
        val source = fileOf(from)
        if (!source.exists()) return
        if (!source.renameTo(fileOf(to))) throw IOException("could not move $source")
    }

    /**
     * Deletes the picture of every instance not in [keep], and every part a write cut short left: what a removal that
     * ended early, or a process that died in a write, left behind. A file that is neither stays. Blocking.
     */
    internal fun deleteAllExcept(keep: Set<String>) {
        if (!directory.exists()) return
        val listed = directory.listFiles() ?: throw IOException("could not list $directory")
        listed
            .filter { it.isFile && (it.name.endsWith(PART_SUFFIX) || isOtherPicture(it.name, keep)) }
            .forEach { if (!it.delete()) throw IOException("could not delete $it") }
    }

    private fun isOtherPicture(
        name: String,
        keep: Set<String>,
    ): Boolean {
        val id = name.removeSuffix(PICTURE_SUFFIX)
        return name.endsWith(PICTURE_SUFFIX) && SHA256_HEX.matches(id) && id !in keep
    }

    /**
     * [source] decoded to the square ([avatarCrop]), then written as a JPEG, which writes no EXIF, into a new part
     * beside [target] and handed over through [handed], unless its caller gave up meanwhile: then the part goes. A
     * decode that fails leaves no part; one the provider stalls holds this thread, with no part yet. A part a failed
     * delete leaves is the launch check's sweep's ([deleteAllExcept]).
     */
    private fun makePart(
        source: ImageDecoder.Source,
        target: File,
        handed: AtomicReference<Any>,
    ) {
        val square = decodeSquare(source)
        val part =
            try {
                partBeside(target).also { part -> writeJpeg(square, part) }
            } finally {
                square.recycle()
            }
        if (!handed.compareAndSet(Working, part)) part.delete()
    }

    /** A new part file beside [target], in [directory], which it makes if need be. */
    private fun partBeside(target: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("could not make $directory")
        return File.createTempFile(target.nameWithoutExtension, PART_SUFFIX, directory)
    }
}

/** [source]'s centre square, [AVATAR_SIDE_PX] across, upright; refused past [MAX_IMAGE_PIXELS] before decoding. */
private fun decodeSquare(source: ImageDecoder.Source): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        refusePastPixels(info.size)
        val crop = avatarCrop(info.size.width, info.size.height)
        decoder.setTargetSize(crop.width, crop.height)
        decoder.crop = Rect(crop.left, crop.top, crop.left + AVATAR_SIDE_PX, crop.top + AVATAR_SIDE_PX)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

/** [square] as a JPEG into [part]; the part goes if it cannot be written. */
private fun writeJpeg(
    square: Bitmap,
    part: File,
) {
    var written = false
    try {
        written = part.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
    } finally {
        if (!written) part.delete()
    }
    if (!written) throw IOException("the picture did not compress")
}

/** [part] renamed over [target] in one rename; the part goes if it cannot be. Blocking. */
private fun place(
    part: File,
    target: File,
) {
    if (part.renameTo(target)) return
    part.delete()
    throw IOException("could not write $target")
}
