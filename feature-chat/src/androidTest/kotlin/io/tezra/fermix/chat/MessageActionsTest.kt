package io.tezra.fermix.chat

import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A message's long-press on a device (design section 13.7): the menu reads Copy · Select text · Copy code ·
 * Share · Info, in that order, for an answer with a fence, with no Reply and no Forward; and Copy puts the
 * message's words on the clipboard.
 */
class MessageActionsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    /** The words on the system clipboard, read on the main thread, none while it is empty. */
    private fun clipped(): String? {
        var words: String? = null
        rule.runOnUiThread {
            val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
            words =
                clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
        }
        return words
    }

    @Test
    fun a_fenced_answers_long_press_opens_copy_select_text_copy_code_share_and_info_in_that_order() {
        rule.onNode(hasScrollToKeyAction()).performScrollToKey("row:$FENCED_ROW")
        rule.onNodeWithText("row $FENCED_ROW").performTouchInput { longClick() }
        val labels = listOf("Copy", "Select text", "Copy code", "Share", "Info")
        for (label in labels) rule.onNodeWithText(label).assertIsDisplayed()
        val tops =
            labels.map {
                rule
                    .onNodeWithText(it)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        val ordered = tops.zipWithNext().all { (above, below) -> above < below }
        assertTrue("the menu reads $labels from the top: $tops", ordered)
        rule.onNodeWithText("Reply").assertDoesNotExist()
        rule.onNodeWithText("Forward").assertDoesNotExist()
    }

    @Test
    fun copy_puts_the_messages_words_on_the_clipboard() {
        val newest = "row $THREAD_ROWS"
        rule.onNodeWithText(newest).performTouchInput { longClick() }
        rule.onNodeWithText("Copy").performClick()
        rule.waitUntil("the words are on the clipboard", STEP_MILLIS) { clipped() == newest }
        assertEquals(newest, clipped())
    }
}
