package io.tezra.fermix.data

import android.util.Size
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException

/**
 * The most pixels an image the app decodes from another app's bytes may have, by its header, the app's own bound: a
 * decode reads every pixel whatever size it is drawn at, so its time is the image's, which another app chooses (a PNG
 * of a few MB may say 65,535 × 65,535), and it holds its thread while it runs. A quarter of a gigapixel takes in a
 * 200 MP phone camera's photo; the design names no bound, and the number is the owner's to settle. The chat's tray and
 * Send hold to it, and so does an avatar's photo (AvatarPictures).
 */
const val MAX_IMAGE_PIXELS = 250_000_000L

/** The JPEG quality an image is compressed at: no visible loss for photos and screenshots at a third the bytes. */
const val JPEG_QUALITY = 85

/**
 * How long a landing takes at most once its turn comes, its providers' descriptions, the chat's record and its copies
 * all within it, and again how long a landing of another app's items waits for its turn behind those before it: past
 * either, what had not landed is left out, logged, a provider's call cancelled and its copy's stream closed. An
 * avatar's photo is made within it too (AvatarPictures). The number is the owner's to settle.
 */
const val LANDING_WAIT_MILLIS = 60_000L

/**
 * An image whose header says it is past [MAX_IMAGE_PIXELS], refused from its decoder's header listener, before a pixel
 * is decoded: an IOException, which the decoder's caller hears as an image that could not be read.
 */
fun refusePastPixels(size: Size) {
    val pixels = size.width.toLong() * size.height
    if (pixels > MAX_IMAGE_PIXELS) throw IOException("an image of $pixels pixels, past the $MAX_IMAGE_PIXELS decoded")
}

/**
 * [call], which asks another app's provider and may block on it, run on one of [threads], its caller waiting while it
 * is active. As the caller is cancelled (a landing's time passed), [stop] runs, which ends what [call] waits on where
 * the caller holds it, closing its stream or cancelling its signal (the chat's landings), or else only refuses what
 * [call] would hand over late (the avatar's decode, whose stream the decoder opens itself: AvatarPictures), and the
 * caller goes on at once, never waiting for the thread: a provider that heeds neither holds that thread, one of the
 * few [threads] has, until it answers, and a [call] not yet begun then never begins. What [call] returns or throws
 * after that has no one to hear it: a stream [stop] closed throws so.
 */
suspend fun <T> onLandingThreads(
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
