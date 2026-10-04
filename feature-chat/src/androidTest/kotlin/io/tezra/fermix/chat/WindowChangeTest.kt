package io.tezra.fermix.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** What the owner has half typed when the window changes. */
private const val DRAFT = "restart the ingest worker after"

/** The row the owner scrolled up to: well above the newest, on every window. */
private const val ANCHOR_ROW = 12

/** How long the card has shown: past the opening "Thinking", in the first pool's second phrase. */
private const val THINKING_FOR_MS = 15_000L

/**
 * A rotation and a fold on a device (design section 13.11, rule 3) recreate the activity, and the chat drawn
 * again keeps the draft in its field, the row the owner scrolled to, and the working indicator's phrase. The
 * folds run on a foldable, as a fold AVD is; elsewhere they are skipped, unless the run requires a fold.
 */
class WindowChangeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    /** A draft half typed, and the list scrolled up to [ANCHOR_ROW]. */
    private fun draftingAboveTheBottom() {
        rule.onNode(hasSetTextAction()).performTextReplacement(DRAFT)
        rule.onNode(hasScrollToKeyAction()).performScrollToKey("row:$ANCHOR_ROW")
        rule.onNodeWithText("row $ANCHOR_ROW").assertIsDisplayed()
    }

    /**
     * The draft in the field, and the row scrolled to displayed, waited for: the keyboard the draft brought up is over
     * the window made again until that window takes the focus, a step of the system's that Compose's idle does not
     * know, and while it is up a phone on its side has no room for the list (Android 15 keeps it up over the window's
     * first frames; 16 shows it again for the new configuration).
     */
    private fun assertStillDraftingThere() {
        rule.onNode(hasSetTextAction()).assertTextEquals(DRAFT, includeEditableText = true)
        val row = "row $ANCHOR_ROW"
        rule.waitUntil("$row displayed", STEP_MILLIS) {
            val placed = rule.onAllNodesWithText(row).fetchSemanticsNodes().isNotEmpty()
            placed && rule.onNodeWithText(row).isDisplayed()
        }
    }

    /** The card's phrase [THINKING_FOR_MS] in, on screen: a pool's, not the opening "Thinking". */
    private fun thinkingPhrase(): String {
        rule.activity.rig.thinking(THINKING_FOR_MS)
        rule.waitForIdle()
        val phrase = checkNotNull(rule.activity.cardPhrase(THINKING_FOR_MS)) { "the card shows" }
        val opening = rule.activity.getString(R.string.chat_indicator_opening)
        assertTrue("a pool's phrase, not the opening line: $phrase", phrase != opening)
        rule.onNodeWithText(phrase).assertIsDisplayed()
        return phrase
    }

    @Test
    fun a_rotation_keeps_the_draft_and_the_row_scrolled_to() {
        draftingAboveTheBottom()
        rule.rotated { assertStillDraftingThere() }
    }

    @Test
    fun a_rotation_keeps_the_working_indicators_phrase() {
        val phrase = thinkingPhrase()
        rule.rotated { rule.onNodeWithText(phrase).assertIsDisplayed() }
    }

    @Test
    @FoldingPhone
    fun a_fold_keeps_the_draft_and_the_row_scrolled_to() {
        draftingAboveTheBottom()
        rule.folded { assertStillDraftingThere() }
    }

    @Test
    @FoldingPhone
    fun a_fold_keeps_the_working_indicators_phrase() {
        val phrase = thinkingPhrase()
        rule.folded { rule.onNodeWithText(phrase).assertIsDisplayed() }
    }
}
