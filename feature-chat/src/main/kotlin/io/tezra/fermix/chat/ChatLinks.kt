package io.tezra.fermix.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.instance.tintColor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The schemes a link preview may open: the web's. Any other is never shown as a preview (shownPreviews). */
private val WEB_SCHEMES = setOf("http", "https")

/** A thumbnail is decoded at most this wide or tall, in pixels: a 640 dp card on a 3x screen, and no more. */
private const val THUMBNAIL_MAX_PX = 1_920

/** A decode halves the picture at most this many times. */
private const val MAX_HALVINGS = 8

/** Whether [url] is a web address, the only kind a preview opens. */
fun isWebLink(url: String): Boolean = url.substringBefore(':', "").lowercase() in WEB_SCHEMES

/** The tag a tap that nothing opens is logged under, as onboarding's links are. */
private const val LOG_TAG = "FermixChat"

/**
 * What a preview's tap does (design section 13.5): its page in a Custom Tab whose toolbar wears the instance's
 * [tint], or the chat's tone before the record is read. A phone with nothing to show it, no browser, has the
 * tap do nothing, which is logged, as onboarding's links do.
 */
@Composable
internal fun rememberLinkOpener(tint: String?): (String) -> Unit {
    val context = LocalContext.current
    val toolbar = (tint?.let(::tintColor) ?: LocalFermixColors.current.tonal).toArgb()
    return remember(context, toolbar) { { url -> openLink(context, url, toolbar) } }
}

private fun openLink(
    context: Context,
    url: String,
    toolbar: Int,
) {
    require(isWebLink(url)) { "a preview opens a web address only" }
    val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(toolbar).build()
    val tab =
        CustomTabsIntent
            .Builder()
            .setDefaultColorSchemeParams(colors)
            .setShowTitle(true)
            .build()
    try {
        tab.launchUrl(context, url.toUri())
    } catch (nothing: ActivityNotFoundException) {
        Log.w(LOG_TAG, "nothing on this phone opens a web page", nothing)
    }
}

/**
 * The picture in [bytes] as a card, a bubble or the viewer draws it, halved until it is no larger than [maxPx], a
 * thumbnail's [THUMBNAIL_MAX_PX] by default; none for bytes that are no picture. Decoded off the main thread.
 */
internal suspend fun decodeThumbnail(
    bytes: ByteArray,
    maxPx: Int = THUMBNAIL_MAX_PX,
    io: CoroutineDispatcher = Dispatchers.IO,
): ImageBitmap? =
    withContext(io) {
        require(maxPx > 0) { "a picture is decoded at some size" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        repeat(MAX_HALVINGS) { if (longest / (sample * 2) >= maxPx) sample *= 2 }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        if (longest <= 0) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
