package io.tezra.fermix.chat

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.MediaRef
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The agent's answer that carries the document: the row after the host's thread. */
private const val DOCUMENT_ROW = THREAD_ROWS + 1

/** A raw HTML page's words, which the chat never draws. */
private const val PAGE = "<html><body><script>alert('drawn')</script>The export report</body></html>"

/**
 * A document from the agent on a device (design section 13.5): a tap downloads it and hands it to the chooser
 * as a read-only content URI of the app's own FileProvider, whose bytes are the blob's; raw HTML goes the same
 * way, as a row with its name, never drawn in the chat.
 */
class DocumentDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    /** The agent's row holding the document [name] of type [mime], its bytes [bytes] on the daemon. */
    private fun documentArrives(
        name: String,
        mime: String,
        bytes: ByteArray,
    ) {
        val rig = rule.activity.rig
        val ref = MediaRef("blob-$name", "file", mime, bytes.size.toLong(), filename = name)
        rig.session.blobs = mapOf(ref.ref to bytes)
        val row =
            TimelineRow.Message(
                HistoryMessage(DOCUMENT_ROW.toULong(), "assistant", "", at(DOCUMENT_ROW.toLong()), listOf(ref)),
            )
        rig.store.rows.update { listOf(row) + it }
        rule.waitUntil("the document's row shows", STEP_MILLIS) {
            rule.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The document tapped: the chooser the chat starts, and the intent it offers. */
    private fun opened(name: String): Pair<Intent, Intent> {
        rule.onNodeWithText(name).performClick()
        val started = rule.activity.rig.started
        rule.waitUntil("the chooser starts", STEP_MILLIS) { started.isNotEmpty() }
        val chooser = started.single()
        val view = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)) { "$chooser" }
        return chooser to view
    }

    private fun assertOffered(
        view: Intent,
        mime: String,
        bytes: ByteArray,
    ) {
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(mime, view.type)
        val uri = checkNotNull(view.data)
        assertEquals("content", uri.scheme)
        assertEquals(filesAuthority(rule.activity), uri.authority)
        assertTrue("read-only", view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue("never writable", view.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        val served = checkNotNull(rule.activity.contentResolver.openInputStream(uri)).use { it.readBytes() }
        assertArrayEquals(bytes, served)
    }

    @Test
    fun a_tapped_document_opens_through_the_chooser_from_the_apps_provider() {
        val pdf = "%PDF-1.7 export report".toByteArray()
        documentArrives("export-report.pdf", "application/pdf", pdf)
        val (chooser, view) = opened("export-report.pdf")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertOffered(view, "application/pdf", pdf)
    }

    @Test
    fun raw_html_is_a_row_never_drawn_and_opens_through_the_chooser() {
        documentArrives("report.html", "text/html", PAGE.toByteArray())
        assertTrue(
            "the page's words are never drawn",
            rule.onAllNodes(hasText("The export report", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        val (chooser, view) = opened("report.html")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertOffered(view, "text/html", PAGE.toByteArray())
    }
}
