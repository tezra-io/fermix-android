package io.tezra.fermix.chat

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.MediaRef
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The agent's answer that carries the image: the row after the host's thread. */
private const val IMAGE_ROW = THREAD_ROWS + 1

/** The daemon's name for the image's blob: its digest, as a row's `media_refs` names it. */
private val IMAGE_REF = "ab".repeat(32)

/** The image's size, a 4:3 frame. */
private const val IMAGE_WIDTH = 120
private const val IMAGE_HEIGHT = 90

/** A PNG of the image, one colour. */
private fun png(): ByteArray {
    val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.rgb(92, 139, 163))
        return ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "the image did not compress" }
            out.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

/**
 * The media viewer on a device (design sections 13.7 and 13.11): a tapped image opens it, its blob fetched once
 * through the session; a rotation makes the activity again, and the viewer is open on its image after it; back
 * puts it down.
 */
class ViewerDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    /** The agent's row holding the image, its bytes on the daemon, drawn in its bubble. */
    private fun imageArrives() {
        val rig = rule.activity.rig
        val bytes = png()
        rig.session.blobs = mapOf(IMAGE_REF to bytes)
        val ref = MediaRef(IMAGE_REF, "image", "image/png", bytes.size.toLong(), filename = "rack.png")
        val message = HistoryMessage(IMAGE_ROW.toULong(), "assistant", "", at(IMAGE_ROW.toLong()), listOf(ref))
        rig.store.rows.update { listOf(TimelineRow.Message(message)) + it }
        rule.waitUntil("the image shows", STEP_MILLIS) { imagesShown() == 1 }
    }

    /** The images drawn: the bubble's, or the viewer's while the bubble's gives it its place. */
    private fun imagesShown(): Int =
        rule
            .onAllNodes(hasContentDescription(rule.activity.getString(R.string.chat_image)))
            .fetchSemanticsNodes()
            .size

    private fun viewerOpen(): Boolean =
        rule.onAllNodes(hasText(rule.activity.getString(R.string.chat_show_in_chat))).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun a_rotation_keeps_the_viewer_open_on_its_image_and_back_puts_it_down() {
        imageArrives()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_image)).performClick()
        rule.waitUntil("the viewer opens", STEP_MILLIS) { viewerOpen() }
        // The rig outlives the activity, as the app's services do.
        val rig = rule.activity.rig
        val made = rig.creations.get()
        rule.rotated {
            rule.waitUntil("the viewer is open again on its image", STEP_MILLIS) { viewerOpen() && imagesShown() == 1 }
        }
        // The turn back makes the activity again too.
        rule.waitUntil("the activity turned back", STEP_MILLIS) { rig.creations.get() >= made + 2 }
        rule.waitUntil("the viewer is open again", STEP_MILLIS) { viewerOpen() }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil("the viewer closes", STEP_MILLIS) { !viewerOpen() }
        assertEquals(listOf(IMAGE_REF), rig.session.fetched.value)
    }
}
