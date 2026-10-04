package io.tezra.fermix.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.StrictMode
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** A file the test plants where the app keeps its records (AppServices: `noBackupFilesDir`), and what it holds. */
private const val PLANTED_NAME = "planted-records.pb"
private val PLANTED = byteArrayOf(9, 8, 7, 6)

/** A copy of a blob as Open leaves one under the cache's shared directory, which the chat's FileProvider serves. */
private const val SHARED_COPY = "shared/0123456789abcdef/$PLANTED_NAME"

/** Another app's image: a photo the test puts in the phone's media store and takes away (galleryImage). */
private const val GALLERY_NAME = "fermix-paste-test.png"

/**
 * What enters the chat from outside it on a device, through the phone's own clipboard and media as the app reads
 * them (design section 8.5, "Sources"): Paste takes another app's content URI, here the media store's, and refuses a
 * file URI, the app's own files and a file the chat made under its cache among them, and a content URI of the app's
 * own provider, with or without a `user@` prefix, each logged by its scheme and authority alone, the tray left
 * empty; the camera's capture, a file the chat made under its cache, still lands.
 */
class PhoneSourcesDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<PhoneChatTestActivity>()

    @After
    fun clipboardCleared() {
        val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
        rule.runOnUiThread { clipboard.clearPrimaryClip() }
    }

    private fun words(id: Int): String = rule.activity.getString(id)

    private fun picked(): List<Picked> = rule.activity.model.media.value.attach.picked

    private fun openSheet() {
        rule.onNodeWithContentDescription(words(R.string.chat_attach)).performClick()
        rule.waitUntil("the sheet shows", STEP_MILLIS) {
            rule.onAllNodes(hasText(words(R.string.chat_files))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * [uri] put on the clipboard as another app would: this process's StrictMode, which refuses a file URI leaving
     * it, is set aside meanwhile, as that app's own would be, and put back however it ends.
     */
    private fun onClipboard(uri: Uri) {
        rule.awaitAppFocus(rule.activity)
        val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
        val policy = StrictMode.getVmPolicy()
        try {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
            rule.runOnUiThread { clipboard.setPrimaryClip(ClipData.newRawUri("item", uri)) }
        } finally {
            StrictMode.setVmPolicy(policy)
        }
    }

    /** Paste with [uri] on the clipboard: refused and logged as [named], its scheme and authority, the tray empty. */
    private fun pasteRefused(
        uri: Uri,
        named: String,
    ) {
        onClipboard(uri)
        rule.onNodeWithText(words(R.string.chat_paste)).performClick()
        val line = "Paste refused the clipboard's $named"
        val log = rule.activity.rig.log.lines
        rule.waitUntil("Paste refuses $named, or the tray takes it", STEP_MILLIS) {
            line in log.value || picked().isNotEmpty()
        }
        rule.waitForIdle()
        assertEquals(emptyList<Picked>(), picked())
        assertTrue("refused as $named: ${log.value}", line in log.value)
        val refusals = log.value.filter { it.startsWith("Paste refused") }
        refusals.forEach { assertFalse("a refusal names no path: $it", PLANTED_NAME in it || "no_backup" in it) }
    }

    @Test
    fun a_file_uri_of_the_apps_own_files_pasted_never_reaches_the_tray() {
        val planted = File(rule.activity.noBackupFilesDir, PLANTED_NAME)
        planted.writeBytes(PLANTED)
        try {
            openSheet()
            pasteRefused(Uri.fromFile(planted), "file URI of no authority")
            assertArrayEquals(PLANTED, planted.readBytes())
        } finally {
            planted.delete()
        }
    }

    /** [block] with [SHARED_COPY] planted under the cache, as Open leaves a copy, then the copy's directory gone. */
    private fun withSharedCopy(block: (File) -> Unit) {
        val copy = File(rule.activity.cacheDir, SHARED_COPY)
        val directory = checkNotNull(copy.parentFile) { "$copy is in no directory" }
        check(directory.isDirectory || directory.mkdirs()) { "$directory could not be made" }
        try {
            copy.writeBytes(PLANTED)
            block(copy)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun a_file_uri_of_a_file_under_the_chats_cache_pasted_never_reaches_the_tray() {
        withSharedCopy { copy ->
            openSheet()
            pasteRefused(Uri.fromFile(copy), "file URI of no authority")
        }
    }

    @Test
    fun a_content_uri_of_the_chats_own_provider_pasted_never_reaches_the_tray() {
        withSharedCopy { copy ->
            val activity = rule.activity
            val authority = filesAuthority(activity)
            val own = FileProvider.getUriForFile(activity, authority, copy)
            openSheet()
            pasteRefused(own, "content URI of $authority")
            val asUserZero = own.buildUpon().encodedAuthority("0@${own.encodedAuthority}").build()
            pasteRefused(asUserZero, "content URI of 0@$authority")
        }
    }

    @Test
    fun another_apps_content_uri_pasted_reaches_the_tray_as_the_chats_copy() {
        val gallery = galleryImage(rule.activity.contentResolver, GALLERY_NAME)
        try {
            onClipboard(gallery)
            openSheet()
            rule.onNodeWithText(words(R.string.chat_paste)).performClick()
            val remove = rule.activity.getString(R.string.chat_remove_item, GALLERY_NAME)
            rule.waitUntil("the tray holds the pasted image", STEP_MILLIS) {
                rule.onAllNodes(hasContentDescription(remove)).fetchSemanticsNodes().isNotEmpty()
            }
            val pasted = picked().single()
            assertEquals(PickedFrom.PASTE, pasted.from)
            assertTrue("the chat's own copy: ${pasted.uri}", pasted.uri.startsWith("file:"))
        } finally {
            rule.activity.contentResolver.delete(gallery, null, null)
        }
    }

    @Test
    fun the_cameras_capture_reaches_the_tray() {
        openSheet()
        rule.onNodeWithText(words(R.string.chat_camera)).performClick()
        rule.onNodeWithText(words(R.string.chat_take_photo)).performClick()
        rule.waitUntil("the photo is picked", STEP_MILLIS) { picked().isNotEmpty() }
        val photo = picked().single()
        assertEquals(PickedFrom.CAMERA, photo.from)
        assertTrue("a file under the cache: ${photo.uri}", photo.uri.startsWith("file:"))
    }
}
