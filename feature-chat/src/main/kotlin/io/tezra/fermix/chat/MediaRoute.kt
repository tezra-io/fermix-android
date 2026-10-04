package io.tezra.fermix.chat

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The tag what the phone refuses of a blob is logged under. */
private const val MEDIA_TAG = "FermixChat"

/** Where a blob is copied for another app to open or take, under the cache, as the FileProvider serves it. */
private const val SHARED_DIRECTORY = "shared"

/** The most blobs kept copied for other apps at once; the oldest goes as another is copied. */
private const val MAX_SHARED = 8

/** Where Save puts an image (design section 13.7, "Save (Pictures/Fermix)") and a document. */
private const val SAVED_IMAGES = "Pictures/Fermix"
private const val SAVED_DOCUMENTS = "Download/Fermix"

/** The FileProvider's authority under the app's id (the module's manifest). */
fun filesAuthority(context: Context): String = "${context.packageName}.chat.files"

/**
 * What the chat asks of the phone beyond its own screen: [start] another app's activity, the chooser, the share
 * sheet and the app's settings among them; whether the app holds a permission ([allowed]); the [camera]'s
 * capture screen, which hands the photo it took to its first argument and closes with its second; and the attach
 * sheet's [photos] grid, the system's embedded Photo Picker where the phone has it. The phone's for the app; the
 * instrumented tests record the activities and stand in for the camera and the picker's surface.
 */
class ChatOutside(
    val start: (Context, Intent) -> Unit = { context, intent -> context.startActivity(intent) },
    val allowed: (Context, String) -> Boolean = { context, permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    },
    val camera: @Composable (onTaken: (File) -> Unit, onClose: () -> Unit) -> Unit = { taken, close ->
        CameraCapture(taken, close)
    },
    val photos: @Composable (AttachUi, AttachActions, Modifier) -> Unit = { attach, actions, modifier ->
        PhotoGrid(attach, actions, modifier)
    },
)

/**
 * The timeline's blobs on the phone (design sections 13.5 and 13.7): an image decoded from the chat's bytes at the
 * edge asked; a voice note's length, play and speed; a document opened through the chooser once it is downloaded
 * and copied where the FileProvider serves it, raw HTML among them, which the chat never draws; Share through the
 * share sheet; Save into Pictures/Fermix or Download/Fermix. A blob the daemon let go says "No longer on {host}".
 */
@Composable
internal fun rememberMediaActions(
    model: ChatViewModel,
    outside: ChatOutside,
    host: String,
): MediaActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(model, outside, context, scope, host) {
        val handed = Handed(model, context, scope, host)
        MediaActions(
            image = { media, edge -> imageOf(model.blobs.bytes(media), edge) },
            length = model.notes::length,
            onOpen = { media -> handed.copied(media) { uri -> outside.start(context, openIntent(uri, media)) } },
            onShare = { media -> handed.copied(media) { uri -> outside.start(context, shareIntent(uri, media)) } },
            onSave = { media -> handed.saved(media) },
            onPlay = model.notes::play,
            onSpeed = model.playback::faster,
        )
    }
}

/** A blob's bytes as the bubble or the viewer draws them, at most [edge] on the long edge. */
private suspend fun imageOf(
    blob: Blob,
    edge: Int,
): MediaImage =
    when (blob) {
        is Blob.Bytes -> decodeThumbnail(blob.bytes, edge)?.let { MediaImage.Shown(it) } ?: MediaImage.Missing
        Blob.Gone -> MediaImage.Gone
        is Blob.InFile, Blob.Missing -> MediaImage.Missing
    }

/**
 * The chat's blobs handed to other apps: copied where the FileProvider serves them, or saved for the owner. A copy
 * or a save the phone refuses is logged; a blob the daemon let go is said.
 */
private class Handed(
    private val model: ChatViewModel,
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** [media] copied for another app, then [then] with its content URI. */
    fun copied(
        media: ShownMedia,
        then: (Uri) -> Unit,
    ) {
        scope.launch {
            val into = copy(media) ?: return@launch
            then(FileProvider.getUriForFile(context, filesAuthority(context), into))
        }
    }

    /** Save: [media] written into the phone's shared Pictures/Fermix or Download/Fermix; "Saved" once it is. */
    fun saved(media: ShownMedia) {
        scope.launch {
            val into = copy(media) ?: return@launch
            val saved =
                try {
                    withContext(io) { savedFile(context, into, media) }
                } catch (refused: IOException) {
                    Log.w(MEDIA_TAG, "a blob could not be saved", refused)
                    false
                }
            if (saved) toast(context.getString(R.string.chat_saved))
        }
    }

    /** [media] in its shared file; none, said or logged, when it cannot be had or written. */
    private suspend fun copy(media: ShownMedia): File? {
        val outcome =
            try {
                val into = withContext(io) { sharedFile(context, media) }
                model.blobs.file(media, into)
            } catch (refused: IOException) {
                Log.w(MEDIA_TAG, "a blob could not be copied for another app", refused)
                Blob.Missing
            }
        if (outcome == Blob.Gone) toast(context.getString(R.string.chat_media_gone, host))
        return (outcome as? Blob.InFile)?.file
    }

    private fun toast(words: String) {
        Toast.makeText(context, words, Toast.LENGTH_SHORT).show()
    }
}

/**
 * A file under the cache's shared directory named as [media] is, in a directory of its cache name, the oldest
 * copies past [MAX_SHARED] deleted first; run off the main thread.
 */
private fun sharedFile(
    context: Context,
    media: ShownMedia,
): File {
    val shared = File(context.cacheDir, SHARED_DIRECTORY)
    val held = shared.listFiles().orEmpty().sortedBy { it.lastModified() }
    held.dropLast(MAX_SHARED - 1).forEach { it.deleteRecursively() }
    val directory = File(shared, media.cacheName.take(CACHE_NAME_CHARS))
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("$directory could not be made")
    return File(directory, fileNameOf(media))
}

/** The characters of a cache name a shared copy's directory keeps: enough to tell two blobs apart. */
private const val CACHE_NAME_CHARS = 16

/** [media]'s name as a file of its own, with no directory in it; [UNNAMED_FILE] when it has none. */
internal fun fileNameOf(media: ShownMedia): String {
    val name =
        media.name
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    return name ?: UNNAMED_FILE
}

/** The file name a blob that names none is shared and saved under. */
private const val UNNAMED_FILE = "file"

/** The chooser over the apps that open [uri], as [media]'s type, read-only. */
internal fun openIntent(
    uri: Uri,
    media: ShownMedia,
): Intent {
    val view =
        Intent(
            Intent.ACTION_VIEW,
        ).setDataAndType(uri, media.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** The share sheet with [uri] as [media]'s type, read-only. */
private fun shareIntent(
    uri: Uri,
    media: ShownMedia,
): Intent {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(media.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * [file], as [media], written into the phone's shared storage: an image into Pictures/Fermix, anything else into
 * Download/Fermix, pending until it is whole. Whether it was; one that could not be is deleted again.
 */
private fun savedFile(
    context: Context,
    file: File,
    media: ShownMedia,
): Boolean {
    val image = media.shape == MediaShape.IMAGE
    val collection =
        if (image) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
    val values =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileNameOf(media))
            put(MediaStore.MediaColumns.MIME_TYPE, media.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (image) SAVED_IMAGES else SAVED_DOCUMENTS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values) ?: throw IOException("the shared storage took no new entry")
    var published = false
    try {
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        published = written(resolver, uri, file) && resolver.update(uri, done, null, null) > 0
    } finally {
        // An entry left pending, a write that threw among them, is deleted, so no half file shows in Pictures.
        if (!published) resolver.delete(uri, null, null)
    }
    return published
}

/** [file]'s bytes into the entry [uri]; whether [resolver] opened it. */
private fun written(
    resolver: ContentResolver,
    uri: Uri,
    file: File,
): Boolean {
    val out = resolver.openOutputStream(uri) ?: return false
    out.use { into -> file.inputStream().use { it.copyTo(into) } }
    return true
}
