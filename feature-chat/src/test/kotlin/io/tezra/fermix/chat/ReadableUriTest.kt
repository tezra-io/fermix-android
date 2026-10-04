package io.tezra.fermix.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** The chat's own FileProvider and another provider of the app's package, as the phone would resolve them. */
private const val OWN = "io.tezra.fermix.chat.files"
private const val OWN_STARTUP = "io.tezra.fermix.androidx-startup"
private val OWN_AUTHORITIES = setOf(OWN, OWN_STARTUP)

/** Other apps' providers: the media store's picker, the documents UI's storage, a note app's and a keyboard's. */
private const val MEDIA = "media"
private const val STORAGE = "com.android.externalstorage.documents"
private const val NOTES = "com.example.notes.fileprovider"
private const val KEYS = "com.google.android.inputmethod.latin.fileprovider"

/** Where a file URI's canonical path lies, as the phone would answer it; none for a URI that names no file. */
private enum class Where { IN_CACHE, OUTSIDE, NONE }

/** One row of the decision: a URI's scheme and authority, where its file lies, its source as it lands or none. */
private data class Row(
    val scheme: String?,
    val authority: String?,
    val from: PickedFrom?,
    val where: Where,
    val readable: Boolean,
)

private fun content(
    authority: String?,
    from: PickedFrom?,
    readable: Boolean,
    scheme: String = "content",
) = Row(scheme, authority, from, Where.NONE, readable)

private fun file(
    where: Where,
    from: PickedFrom?,
    readable: Boolean,
    scheme: String = "file",
) = Row(scheme, null, from, where, readable)

private val ROWS =
    listOf(
        // Another app's item as it lands: its provider's content URI, never one of the app's own.
        content(MEDIA, PickedFrom.PHOTOS, readable = true),
        content(STORAGE, PickedFrom.FILES, readable = true),
        content(NOTES, PickedFrom.PASTE, readable = true),
        content(KEYS, PickedFrom.KEYBOARD, readable = true),
        content(OWN, PickedFrom.PASTE, readable = false),
        content(OWN, PickedFrom.KEYBOARD, readable = false),
        content(OWN, PickedFrom.FILES, readable = false),
        content(OWN_STARTUP, PickedFrom.PHOTOS, readable = false),
        // The ContentResolver strips a `user@` prefix, up to the last @, before it finds the provider.
        content("0@$OWN", PickedFrom.PASTE, readable = false),
        content("10@$OWN", PickedFrom.KEYBOARD, readable = false),
        content("x@y@$OWN", PickedFrom.FILES, readable = false),
        content("$OWN@$MEDIA", PickedFrom.PASTE, readable = true),
        content(null, PickedFrom.PASTE, readable = false),
        content("", PickedFrom.PASTE, readable = false),
        content("0@", PickedFrom.PASTE, readable = false),
        // The ContentResolver compares a scheme exactly: an upper-case one is none it reads as a provider's or a file.
        content(MEDIA, PickedFrom.PASTE, readable = false, scheme = "CONTENT"),
        content(OWN, PickedFrom.PHOTOS, readable = false, scheme = "Content"),
        // A file URI from outside the chat, wherever it lies.
        file(Where.IN_CACHE, PickedFrom.PASTE, readable = false),
        file(Where.OUTSIDE, PickedFrom.PASTE, readable = false),
        file(Where.IN_CACHE, PickedFrom.KEYBOARD, readable = false),
        file(Where.OUTSIDE, PickedFrom.KEYBOARD, readable = false),
        file(Where.IN_CACHE, PickedFrom.PHOTOS, readable = false),
        file(Where.OUTSIDE, PickedFrom.FILES, readable = false),
        file(Where.OUTSIDE, PickedFrom.PASTE, readable = false, scheme = "FILE"),
        // Any other scheme.
        Row("android.resource", "io.tezra.fermix", PickedFrom.PASTE, Where.NONE, readable = false),
        Row("http", "example.com", PickedFrom.PASTE, Where.NONE, readable = false),
        Row(null, null, PickedFrom.PASTE, Where.NONE, readable = false),
        // The chat's own files as they land: a camera's capture, Edit's copy, under the cache only.
        file(Where.IN_CACHE, PickedFrom.CAMERA, readable = true),
        file(Where.OUTSIDE, PickedFrom.CAMERA, readable = false),
        file(Where.IN_CACHE, PickedFrom.CAMERA, readable = false, scheme = "FILE"),
        content(MEDIA, PickedFrom.CAMERA, readable = false),
        file(Where.IN_CACHE, PickedFrom.OUTBOX, readable = true),
        file(Where.OUTSIDE, PickedFrom.OUTBOX, readable = false),
        // An item the tray holds: another app's content URI, or a file the chat made under its cache.
        content(MEDIA, null, readable = true),
        content(OWN, null, readable = false),
        content("0@$OWN", null, readable = false),
        file(Where.IN_CACHE, null, readable = true),
        file(Where.OUTSIDE, null, readable = false),
        file(Where.IN_CACHE, null, readable = false, scheme = "FILE"),
        content(MEDIA, null, readable = false, scheme = "CONTENT"),
        Row("http", "example.com", null, Where.NONE, readable = false),
    )

/**
 * What enters the chat from outside it (design section 8.5, "Sources"), weighed by one rule: another app's item is
 * its provider's content URI, never one of the app's own providers, which the app would read with its own rights;
 * a file URI is read only where the chat made the file, under its cache.
 */
class ReadableUriTest {
    private fun decided(row: Row): Boolean =
        mayRead(
            row.scheme,
            row.authority,
            row.from,
            own = { it in OWN_AUTHORITIES },
            inCache = { row.where == Where.IN_CACHE },
        )

    @Test
    fun `each scheme, source, authority and path is read or refused as the table says`() {
        val wrong = ROWS.filter { decided(it) != it.readable }
        assertEquals(emptyList<Row>(), wrong)
    }

    @Test
    fun `a file URI from outside the chat is refused without asking where it lies`() {
        val outside = listOf(PickedFrom.PHOTOS, PickedFrom.FILES, PickedFrom.PASTE, PickedFrom.KEYBOARD)
        for (from in outside) {
            var asked = false
            val readable = mayRead("file", null, from, own = { false }, inCache = { true.also { asked = true } })
            assertFalse(readable, "$from")
            assertFalse(asked, "$from")
        }
    }
}
