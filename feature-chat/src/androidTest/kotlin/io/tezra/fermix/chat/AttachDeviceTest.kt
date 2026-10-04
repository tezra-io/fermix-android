package io.tezra.fermix.chat

import android.net.Uri
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest

/** The pasted item's bytes. */
private val PASTED_BYTES = byteArrayOf(4, 5, 6)

/**
 * The attach sheet's sources on a device (design sections 8.5 and 13.6), their system activities answered by the
 * rig: the Photo Picker's photos (PickMultipleVisualMedia, behind the sheet's tile where the embedded picker does
 * not draw), the documents UI's files (OpenMultipleDocuments), the clipboard's item (Paste), copied as it lands so
 * that it sends after the clip changed and its read grant went, and the camera's photo each reach the tray, each
 * item with its ✕; the camera, a screen of the chat's own, puts the sheet down first.
 */
class AttachDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    private fun openSheet() {
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_attach)).performClick()
        rule.waitUntil("the sheet shows", STEP_MILLIS) { exists(rule.activity.getString(R.string.chat_files)) }
    }

    private fun exists(words: String): Boolean = rule.onAllNodes(hasText(words)).fetchSemanticsNodes().isNotEmpty()

    /** The tray's items as the tray says them: each one's ✕ names it. */
    private fun awaitTray(vararg names: String) {
        val removes = names.map { rule.activity.getString(R.string.chat_remove_item, it) }
        rule.waitUntil("the tray holds ${names.toList()}", STEP_MILLIS) {
            removes.all { label ->
                rule
                    .onAllNodes(hasContentDescription(label))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }

    private fun picked(): List<Picked> = rule.activity.model.media.value.attach.picked

    @Test
    fun photos_from_the_photo_picker_reach_the_tray() {
        rule.activity.rig.results.photos =
            listOf(Uri.parse("content://media/picker/0/31"), Uri.parse("content://media/picker/0/32"))
        openSheet()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_photos)).performClick()
        awaitTray("31", "32")
        assertEquals(listOf("PickMultipleVisualMedia"), rule.activity.rig.results.launched)
        assertEquals(listOf(PickedFrom.PHOTOS, PickedFrom.PHOTOS), picked().map { it.from })
        rule.onNodeWithText(rule.activity.getString(R.string.chat_send_count, 2)).assertExists()
    }

    @Test
    fun files_from_the_documents_ui_reach_the_tray() {
        rule.activity.rig.results.documents = listOf(Uri.parse("content://docs/report.pdf"))
        openSheet()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_files)).performClick()
        awaitTray("report.pdf")
        assertEquals(listOf("OpenMultipleDocuments"), rule.activity.rig.results.launched)
        assertEquals(listOf("content://docs/report.pdf" to PickedFrom.FILES), picked().map { it.uri to it.from })
    }

    @Test
    fun paste_takes_the_clipboards_item_into_the_tray_as_a_copy_that_sends_once_the_clip_changed() {
        val rig = rule.activity.rig
        val pipeline = rig.parts.media as FakePipeline
        pipeline.bytes = mapOf("content://clip/pasted.png" to PASTED_BYTES)
        rig.clip.clip = "content://clip/pasted.png"
        openSheet()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_paste)).performClick()
        awaitTray("pasted.png")
        val pasted = picked().single()
        assertEquals(PickedFrom.PASTE, pasted.from)
        assertTrue("copied into a file the chat owns: ${pasted.uri}", pasted.uri.startsWith("file:"))
        assertEquals("Paste launches nothing", emptyList<String>(), rig.results.launched)
        // A new clip: the read grant of the one pasted is gone, and Send reads only the copy.
        rig.clip.clip = "content://clip/other.png"
        pipeline.refused = setOf("content://clip/pasted.png")
        rule.onNodeWithText(rule.activity.getString(R.string.chat_send_count, 1)).performClick()
        val outbox = rig.store.outbox
        rule.waitUntil("the session takes the send", STEP_MILLIS) { outbox.value.isNotEmpty() }
        val sent =
            outbox.value
                .single()
                .attachments
                .single()
        val digest = MessageDigest.getInstance("SHA-256").digest(PASTED_BYTES).toHexString()
        assertEquals("the pasted bytes went", digest, sent.sha256)
    }

    @Test
    fun the_cameras_photo_reaches_the_tray() {
        openSheet()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_camera)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_take_photo)).performClick()
        rule.waitUntil("the photo is picked", STEP_MILLIS) { picked().isNotEmpty() }
        val photo = picked().single()
        assertEquals(PickedFrom.CAMERA, photo.from)
        assertTrue("a file under the cache: ${photo.uri}", photo.uri.startsWith("file:"))
        awaitTray(photo.name)
    }
}
