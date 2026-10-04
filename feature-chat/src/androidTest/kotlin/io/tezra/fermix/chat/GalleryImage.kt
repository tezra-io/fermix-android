package io.tezra.fermix.chat

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** A gallery photo's edge: enough pixels to decode. */
private const val GALLERY_EDGE = 8

/**
 * Another app's image as the tests make one: a small PNG named [name] in the phone's media store, in Pictures itself,
 * so no directory of its own is left behind; its URI, the caller's to delete. The media store is another package's
 * provider, but a test runs as the app, so the app reads the row it owns by that ownership, not by a grant another
 * app gave it. An entry that could not be written is deleted again.
 */
internal fun galleryImage(
    resolver: ContentResolver,
    name: String,
): Uri {
    val values =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val entry = checkNotNull(resolver.insert(collection, values)) { "the media store took no image" }
    var written = false
    try {
        pngInto(resolver, entry)
        written = true
    } finally {
        if (!written) resolver.delete(entry, null, null)
    }
    return entry
}

/** A small PNG written into the media store's [entry], which then stops being pending. */
private fun pngInto(
    resolver: ContentResolver,
    entry: Uri,
) {
    val bitmap = Bitmap.createBitmap(GALLERY_EDGE, GALLERY_EDGE, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.rgb(92, 139, 163))
        checkNotNull(resolver.openOutputStream(entry)) { "$entry took no bytes" }.use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "the PNG was not written" }
        }
    } finally {
        bitmap.recycle()
    }
    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    check(resolver.update(entry, done, null, null) == 1) { "$entry stayed pending" }
}
