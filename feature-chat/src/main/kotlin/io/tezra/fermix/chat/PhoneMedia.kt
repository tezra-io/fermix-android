package io.tezra.fermix.chat

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The longest edge an image goes up at, in pixels (design section 8.5, "Images"). 2,048 keeps a phone photo's
 * text and a screenshot legible to the model at full zoom, while the decoded ARGB bitmap stays near 12.6 MB at
 * 4:3, so a ten-image send never holds a 48 MP frame (192 MB) in memory, and its JPEG is a few hundred KB.
 */
const val LONG_EDGE_PX = 2_048

/** The JPEG quality an image is compressed at: no visible loss for photos and screenshots at a third the bytes. */
const val JPEG_QUALITY = 85

/** The longest edge a first chunk decodes at for its placeholder colour: enough pixels to average. */
private const val SHADE_EDGE_PX = 32

/**
 * The types a file the chat made is named by, by its extension: a camera's capture and an outbox item's copy. A
 * provider's item takes the type its provider says.
 */
private val FILE_TYPES =
    mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "heic" to "image/heic",
        "ogg" to "audio/ogg",
        "mp4" to "video/mp4",
    )

/**
 * The phone's media (design section 8.5): a picked item, once the chat may read it (readableUri), described through
 * the ContentResolver; an image decoded by ImageDecoder at most [LONG_EDGE_PX] on its long edge (HEIF too,
 * natively, and turned upright by its EXIF orientation) and compressed to a JPEG by Bitmap.compress, which writes
 * no EXIF, so the photo's GPS and camera tags stay on the phone; any other item, and an image sent as a file,
 * copied as its own bytes. Its file work runs on [io]; a first chunk that decodes to no placeholder is told to
 * [log].
 */
class PhoneMedia(
    private val context: Context,
    private val log: (String, Throwable?) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MediaPipeline {
    /** [uri] as it lands from [from], once the chat may read it (readableUri): a SecurityException when it may not. */
    override suspend fun describe(
        uri: String,
        from: PickedFrom,
    ): Picked? =
        withContext(io) {
            val parsed = readableUri(context, uri, from)
            nameAndSize(parsed)?.let { (name, size) ->
                val mime = context.contentResolver.getType(parsed) ?: mimeOf(name)
                Picked(uri, uri, pickedKindOf(mime), mime, name, size, from)
            }
        }

    /** [picked] made ready once the chat may read it as the tray holds it (readableUri): a SecurityException if not. */
    override suspend fun prepare(
        picked: Picked,
        asFile: Boolean,
        into: File,
    ): Prepared =
        withContext(io) {
            val parsed = readableUri(context, picked.uri, landing = null)
            if (asFile || picked.kind != PickedKind.IMAGE) {
                copyInto(parsed, into)
                Prepared(picked.mime, picked.name)
            } else {
                jpegInto(parsed, into)
                Prepared("image/jpeg", "${picked.name.substringBeforeLast('.')}.jpg")
            }
        }

    /**
     * The average colour of what [bytes], a blob's first chunk, decode to: ImageDecoder takes the part of the image
     * the chunk holds (a partial image), at most [SHADE_EDGE_PX] on its long edge, and the pixels it drew are
     * averaged; none for a chunk that is no image it can begin.
     */
    override fun dominantColour(bytes: ByteArray): Int? {
        val bitmap =
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val (width, height) = cappedSize(info.size.width, info.size.height, SHADE_EDGE_PX)
                    decoder.setTargetSize(width, height)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setOnPartialImageListener { true }
                }
            } catch (unreadable: IOException) {
                log("A first chunk decoded to no placeholder colour", unreadable)
                null
            }
        return bitmap?.let {
            try {
                averageOf(it)
            } finally {
                it.recycle()
            }
        }
    }

    /**
     * [uri]'s display name and size, from the file itself or its provider; none when neither answers. Its one caller,
     * [describe], has let it in first: a file the chat made, under its cache, or another app's content URI.
     */
    private fun nameAndSize(uri: Uri): Pair<String, Long>? {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "$uri names no file" })
            return if (file.isFile) file.name to file.length() else null
        }
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0) ?: uri.lastPathSegment ?: "file"
            val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            name to size
        }
    }

    /** [uri]'s own bytes into [into]. */
    private fun copyInto(
        uri: Uri,
        into: File,
    ) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("$uri cannot be opened")
        input.use { from -> into.outputStream().use { from.copyTo(it) } }
    }

    /** [uri]'s image, upright and capped, as a JPEG with no EXIF into [into]. */
    private fun jpegInto(
        uri: Uri,
        into: File,
    ) {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (width, height) = cappedSize(info.size.width, info.size.height, LONG_EDGE_PX)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        try {
            val written = into.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!written) throw IOException("$uri did not compress")
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * The phone's clipboard: its first item's URI, which Paste takes as a picked item, when it is another app's content
 * URI ([readable]); any other is refused, and told to [log] by its scheme and authority alone.
 */
class PhoneClip(
    private val context: Context,
    private val log: (String, Throwable?) -> Unit,
) : ChatClip {
    override fun media(): String? {
        val uri = firstUri() ?: return null
        val taken = readable(context, uri, PickedFrom.PASTE)
        if (!taken) log("Paste refused the clipboard's ${outsideOf(uri)}", null)
        return uri.takeIf { taken }?.toString()
    }

    private fun firstUri(): Uri? =
        context
            .getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.uri
}

/**
 * Whether the chat reads [uri] as it lands from [landing], or held in the tray (none): mayRead, its facts asked of
 * the phone, the app's own providers through PackageManager.resolveContentProvider, and a file's place by its
 * canonical path under the app's cache, which reads the filesystem and is asked only where a file may be read: a
 * camera's capture as it lands and an item held, both off the main thread.
 */
internal fun readable(
    context: Context,
    uri: Uri,
    landing: PickedFrom?,
): Boolean =
    mayRead(
        uri.scheme,
        uri.authority,
        landing,
        own = { authority -> ownsProvider(context, authority) },
        inCache = { uri.path?.let { liesUnder(File(it), context.cacheDir) } == true },
    )

/**
 * [uri] parsed, once the chat reads it as it lands from [landing], or held in the tray (none) ([readable]); a
 * SecurityException that names its scheme and authority, never its path, when it does not.
 */
internal fun readableUri(
    context: Context,
    uri: String,
    landing: PickedFrom?,
): Uri {
    val parsed = uri.toUri()
    val where = landing?.let { "as it lands from $it" } ?: "held in the tray"
    if (!readable(context, parsed, landing)) throw SecurityException("the chat refuses a ${outsideOf(parsed)} $where")
    return parsed
}

/** A URI as a refusal names it: its scheme and its authority, never its path, which can say what the phone holds. */
internal fun outsideOf(uri: Uri): String = "${uri.scheme} URI of ${uri.authority.orEmpty().ifEmpty { "no authority" }}"

/** Whether [authority] names a provider of the app's own package, which the app reads with its own rights. */
private fun ownsProvider(
    context: Context,
    authority: String,
): Boolean {
    val provider = context.packageManager.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
    return provider?.packageName == context.packageName
}

/** [width] × [height] scaled down to at most [edge] on the long edge, never up; at least a pixel each way. */
internal fun cappedSize(
    width: Int,
    height: Int,
    edge: Int,
): Pair<Int, Int> {
    val long = maxOf(width, height, 1)
    if (long <= edge) return maxOf(width, 1) to maxOf(height, 1)
    return maxOf(width * edge / long, 1) to maxOf(height * edge / long, 1)
}

/** The type [name]'s extension says among [FILE_TYPES], or bytes. */
private fun mimeOf(name: String): String = FILE_TYPES[name.substringAfterLast('.', "").lowercase()] ?: UNKNOWN_MIME

/** The average of [bitmap]'s drawn pixels, opaque; none when it drew none. */
private fun averageOf(bitmap: Bitmap): Int? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val drawn = pixels.filter { Color.alpha(it) != 0 }
    if (drawn.isEmpty()) return null
    val red = drawn.sumOf { Color.red(it) } / drawn.size
    val green = drawn.sumOf { Color.green(it) } / drawn.size
    val blue = drawn.sumOf { Color.blue(it) } / drawn.size
    return Color.rgb(red, green, blue)
}
