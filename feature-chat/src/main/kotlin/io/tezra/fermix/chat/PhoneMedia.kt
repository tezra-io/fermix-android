package io.tezra.fermix.chat

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.util.Size
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * The longest edge an image goes up at, in pixels (design section 8.5, "Images"). 2,048 keeps a phone photo's
 * text and a screenshot legible to the model at full zoom, while the decoded ARGB bitmap stays near 12.6 MB at
 * 4:3, so a ten-image send never holds a 48 MP frame (192 MB) in memory, and its JPEG is a few hundred KB.
 */
const val LONG_EDGE_PX = 2_048

/** The JPEG quality an image is compressed at: no visible loss for photos and screenshots at a third the bytes. */
const val JPEG_QUALITY = 85

/**
 * The most pixels an image the chat decodes for the tray or for Send may have, by its header, the app's own bound: a
 * decode reads every pixel whatever size it is drawn at, so its time is the image's, which another app chooses (a PNG
 * of a few MB may say 65,535 × 65,535), and it holds its thread while it runs. A quarter of a gigapixel takes in a
 * 200 MP phone camera's photo; the design names no bound, and the number is the owner's to settle.
 */
const val MAX_IMAGE_PIXELS = 250_000_000L

/** The longest edge a first chunk decodes at for its placeholder colour: enough pixels to average. */
private const val SHADE_EDGE_PX = 32

/** What a landing copy reads at a time, at most. */
private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * The threads the app gives another app's providers as a paste's, the keyboard's or a share's items land, for every
 * chat at once (AppServices): a provider that stalls, describing an item or handing over its bytes, holds no more of
 * them however many items and shares come, and the landings behind it are left out once their time passes
 * (LANDING_WAIT_MILLIS). The owner's own picks, the Photo Picker's, the camera's and the files', are described on the
 * app's `io`, so another app's share never holds them.
 */
const val LANDING_THREADS = 2

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
 * copied as its own bytes. Its file work runs on [io], and what asks another app's provider as a paste's, the
 * keyboard's or a share's item lands, its description and its landing copy, on [landing], the app's few threads for it
 * ([LANDING_THREADS]; the app passes the ones every chat shares, a test its own); an owner's pick is described on [io].
 * Each call is given up as its caller is cancelled ([onLandingThreads]); a first chunk that decodes to no placeholder
 * is told to [log].
 */
class PhoneMedia(
    private val context: Context,
    private val log: (String, Throwable?) -> Unit,
    private val landing: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(LANDING_THREADS),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MediaPipeline {
    /**
     * [uri] as it lands from [from], once the chat may read it (readableUri): a SecurityException when it may not. Its
     * provider is asked on [landing] when another app's item lands (copiedOnLanding), else on [io], and its query is
     * cancelled as its caller is.
     */
    override suspend fun describe(
        uri: String,
        from: PickedFrom,
    ): Picked? {
        val signal = CancellationSignal()
        val threads = if (copiedOnLanding(from)) landing else io
        return onLandingThreads(threads, stop = signal::cancel) {
            val parsed = readableUri(context, uri, from)
            nameAndSize(parsed, signal)?.let { (name, size) ->
                val mime = context.contentResolver.getType(parsed) ?: mimeOf(name)
                Picked(uri, uri, pickedKindOf(mime), mime, name, size, from)
            }
        }
    }

    /**
     * [picked]'s own bytes into [into], at most [maxBytes] and one more, once the chat may read it as the tray holds it
     * (readableUri): a SecurityException if not. As its caller is cancelled, the provider's open is cancelled and the
     * stream closed, which ends a read that waits on it.
     */
    override suspend fun copyAtMost(
        picked: Picked,
        into: File,
        maxBytes: Long,
    ): Long {
        require(maxBytes in 0..LANDING_MAX_BYTES) { "a landing copy of at most $maxBytes bytes" }
        val signal = CancellationSignal()
        val reading = AtomicReference<InputStream?>()
        val stop: () -> Unit = {
            signal.cancel()
            reading.get()?.close()
        }
        return onLandingThreads(landing, stop) {
            val parsed = readableUri(context, picked.uri, landing = null)
            val input =
                context.contentResolver.openAssetFileDescriptor(parsed, "r", signal)?.createInputStream()
                    ?: throw IOException("a ${outsideOf(parsed)} could not be opened")
            input.use { from ->
                reading.set(from)
                if (signal.isCanceled) throw IOException("the landing's time ran out as its stream opened")
                into.outputStream().use { copyAtMost(from, it, maxBytes) }
            }
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
     * [uri]'s display name and size, from the file itself or its provider, whose query [signal] cancels; none when
     * neither answers. Its one caller, [describe], has let it in first: a file the chat made, under its cache, or
     * another app's content URI.
     */
    private fun nameAndSize(
        uri: Uri,
        signal: CancellationSignal,
    ): Pair<String, Long>? {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "$uri names no file" })
            return if (file.isFile) file.name to file.length() else null
        }
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return context.contentResolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
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

    /**
     * [uri]'s image, upright and capped, as a JPEG with no EXIF into [into]; an IOException, before a pixel is decoded,
     * for one past [MAX_IMAGE_PIXELS] ([refusePastPixels]).
     */
    private fun jpegInto(
        uri: Uri,
        into: File,
    ) {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                refusePastPixels(info.size)
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
 * the phone, the app's own providers as its manifest declares them (ownsProvider), and a file's place by its
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

/** A URI as a refusal names it: its scheme and its authority, never its path (outsideOf). */
internal fun outsideOf(uri: Uri): String = outsideOf(uri.scheme, uri.authority)

/**
 * Whether [authority] names a provider of the app's own package, which the app reads with its own rights: one its
 * manifest declares, each provider's authorities split as the manifest lists them.
 */
fun ownsProvider(
    context: Context,
    authority: String,
): Boolean {
    val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong())
    val providers =
        context.packageManager
            .getPackageInfo(context.packageName, flags)
            .providers
            .orEmpty()
    return providers.any { provider -> authority in provider.authority.orEmpty().split(';') }
}

/**
 * An image whose header says it is past [MAX_IMAGE_PIXELS], refused from its decoder's header listener, before a pixel
 * is decoded: an IOException, which the decoder's caller hears as an image that could not be read.
 */
internal fun refusePastPixels(size: Size) {
    val pixels = size.width.toLong() * size.height
    if (pixels > MAX_IMAGE_PIXELS) throw IOException("an image of $pixels pixels, past the $MAX_IMAGE_PIXELS decoded")
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

/**
 * [from]'s bytes into [into], at most [max] and one more, which tells a stream past [max]: how many it wrote. Each read
 * asks for no more than that bound leaves and must take a byte or end the stream (a read of none and no end is an
 * IOException), so the loop ends within [max] + 1 reads, however much another app's provider would hand over.
 */
internal fun copyAtMost(
    from: InputStream,
    into: OutputStream,
    max: Long,
): Long {
    require(max >= 0) { "a copy of at most $max bytes" }
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var copied = 0L
    while (copied <= max) {
        // What the bound leaves, and one more, counted without overflow when the limit is Long.MAX_VALUE (no caps).
        val left = max - copied
        val read = from.read(buffer, 0, if (left < buffer.size) left.toInt() + 1 else buffer.size)
        if (read < 0) break
        if (read == 0) throw IOException("a stream handed over no bytes and did not end")
        into.write(buffer, 0, read)
        copied += read
    }
    return copied
}

/**
 * [call], which asks another app's provider and may block on it, run on one of [threads], its caller waiting while it
 * is active. As the caller is cancelled (a landing's time passed), [stop] runs, which ends what [call] waits on,
 * closing its stream or cancelling its signal, and the caller goes on at once, never waiting for the thread: a
 * provider that heeds neither holds that thread, one of the few [threads] has, until it answers, and a [call] not yet
 * begun then never begins. What [call] returns or throws after that has no one to hear it: a stream [stop] closed
 * throws so.
 */
internal suspend fun <T> onLandingThreads(
    threads: CoroutineDispatcher,
    stop: () -> Unit,
    call: () -> T,
): T =
    suspendCancellableCoroutine { waiting ->
        waiting.invokeOnCancellation { stop() }
        threads.asExecutor().execute {
            if (waiting.isActive) waiting.resumeWith(resultOf(call))
        }
    }

/**
 * What [call] returned or threw, for its caller to hear: whatever a provider's call throws, the binder's own
 * exceptions among them, is the caller's to handle, as a call on its own thread would throw it to it; an Error goes on,
 * on the landing thread.
 */
private fun <T> resultOf(call: () -> T): Result<T> =
    try {
        Result.success(call())
    } catch (expected: Exception) {
        Result.failure(expected)
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
