package io.tezra.fermix.instance

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * How many decoded avatar pictures the app keeps for its screens: a phone's Chats list of rows, the chat's bar and the
 * Instance header, each at its own size. A bound by count, not bytes, a picture replaced staying until it is the
 * oldest: at 2.625 density the largest disc, the Instance header's 72 dp, is 189 px, 142,884 B, so 16 of it are
 * 2.2 MiB; and as a picture is read at most at its file's 256 px (AvatarPictures.read), 4 MiB on any screen.
 */
private const val KEPT_PICTURES = 16

/** One avatar picture as a screen draws it: [id]'s picture of [pictureAt] (its version), [side] pixels across. */
data class AvatarKey(
    val id: String,
    val pictureAt: Long,
    val side: Int,
)

/**
 * The avatar pictures the screens draw (the owner, 2026-10-10): each read by [read], data's AvatarPictures.read, a file
 * of at most 256 × 256 decoded at the size drawn, at most its own, on [io], once per [AvatarKey], and the last
 * [KEPT_PICTURES] kept for every screen to share, so a row scrolled back, the chat's bar and the Instance header draw a
 * picture read before at once. One read at a time, so two screens asking for one picture read it once. One that cannot
 * be read is told to [log] and drawn as the tint. The app makes one ([LocalAvatarPictures]).
 */
class AvatarPictureCache(
    private val read: (id: String, side: Int) -> Bitmap?,
    private val log: (String, Throwable?) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val kept = LruCache<AvatarKey, ImageBitmap>(KEPT_PICTURES)
    private val reading = Mutex()

    /** [key]'s picture while it is kept, else none. */
    fun kept(key: AvatarKey): ImageBitmap? = kept[key]

    /** [key]'s picture, kept or read now off the main thread; none when it has no file or cannot be read. */
    suspend fun picture(key: AvatarKey): ImageBitmap? =
        reading.withLock {
            kept[key] ?: withContext(io) { readOrLog(key) }?.also { kept.put(key, it) }
        }

    private fun readOrLog(key: AvatarKey): ImageBitmap? =
        try {
            read(key.id, key.side)?.asImageBitmap()
        } catch (unreadable: IOException) {
            log("An avatar picture could not be read", unreadable)
            null
        }
}

/** The app's [AvatarPictureCache]; none draws every avatar as its tint, as a test with no pictures does. */
val LocalAvatarPictures = staticCompositionLocalOf<AvatarPictureCache?> { null }

/**
 * [id]'s picture for an avatar of [size], none while [pictureAt] says it has none: drawn at once when kept, else
 * read off the main thread and drawn as it comes, the tint until then. A new [pictureAt] reads the new picture.
 */
@Composable
fun rememberAvatarPicture(
    id: String,
    pictureAt: Long?,
    size: AvatarSize,
): ImageBitmap? {
    val pictures = LocalAvatarPictures.current
    val side = with(LocalDensity.current) { size.disc.roundToPx() }
    val key = pictureAt?.let { AvatarKey(id, it, side) }
    val picture by produceState(key?.let { pictures?.kept(it) }, pictures, key) {
        value = if (pictures == null || key == null) null else pictures.picture(key)
    }
    return picture
}
