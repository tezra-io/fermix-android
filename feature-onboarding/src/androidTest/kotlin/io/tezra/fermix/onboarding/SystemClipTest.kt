package io.tezra.fermix.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** A text file the test puts in the phone's media store, and its words, which no paste may read. */
private const val NOTE_NAME = "fermix-clip-test.txt"
private const val NOTE_WORDS = "the words of a file another app named"

/**
 * The paste sheet's primary clip on the device's own ClipboardManager, which lets an app read and clear the
 * clipboard only while one of its windows has the focus: an activity is shown for that, and has it. A clip's URI is
 * never opened, as the app would read it with its own rights: its own words are the text.
 */
class SystemClipTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun the_clip_reads_the_primary_clips_text_and_clearing_it_leaves_none() {
        val activity = rule.activity
        rule.awaitWindowFocus(activity)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        rule.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("pairing link", linkText())) }
        val clip = clipboardClip(activity)
        assertEquals(linkText(), rule.runOnUiThread { clip.text() })
        rule.runOnUiThread { clip.clear() }
        assertFalse("the link is off the clipboard", clipboard.hasPrimaryClip())
        assertNull(rule.runOnUiThread { clip.text() })
    }

    @Test
    fun a_clips_uri_is_never_opened_and_its_own_words_are_the_text() {
        val activity = rule.activity
        rule.awaitWindowFocus(activity)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val note = noteEntry()
        try {
            rule.runOnUiThread { clipboard.setPrimaryClip(ClipData.newRawUri("pairing link", note)) }
            val clip = clipboardClip(activity)
            assertEquals(note.toString(), rule.runOnUiThread { clip.text() })
        } finally {
            rule.runOnUiThread { clipboard.clearPrimaryClip() }
            activity.contentResolver.delete(note, null, null)
        }
    }

    /** A text file in the phone's Download, which the app could open as the media store's: its URI, to delete. */
    private fun noteEntry(): Uri {
        val resolver = rule.activity.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NOTE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val entry = checkNotNull(resolver.insert(collection, values)) { "the media store took no file" }
        var written = false
        try {
            checkNotNull(resolver.openOutputStream(entry)) { "$entry took no bytes" }.use {
                it.write(NOTE_WORDS.toByteArray())
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            written = resolver.update(entry, done, null, null) == 1
        } finally {
            if (!written) resolver.delete(entry, null, null)
        }
        check(written) { "$entry stayed pending" }
        return entry
    }
}
