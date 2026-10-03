package io.tezra.fermix.chat

import android.app.UiAutomation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** What the owner has half typed when the window changes. */
private const val DRAFT = "restart the ingest worker after"

/** The row the owner scrolled up to: well above the newest, on every window. */
private const val ANCHOR_ROW = 12

/** How long the card has shown: past the opening "Thinking", in the first pool's second phrase. */
private const val THINKING_FOR_MS = 15_000L

/**
 * The runner argument CI's folding profile sets (`-Pandroid.testInstrumentationRunnerArguments.requireFold=true`),
 * under which a device that cannot fold fails the fold tests rather than skip them.
 */
private const val REQUIRE_FOLD = "requireFold"

/**
 * A rotation and a fold on a device (design section 13.11, rule 3) recreate the activity, and the chat drawn
 * again keeps the draft in its field, the row the owner scrolled to, and the working indicator's phrase. The
 * folds run on a foldable, as a fold AVD is; elsewhere they are skipped, unless the run requires a fold.
 */
class WindowChangeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun foldRequired(): Boolean = InstrumentationRegistry.getArguments().getString(REQUIRE_FOLD) == "true"

    private fun awaitRecreated(made: Int) {
        val rig = rule.activity.rig
        rule.waitUntil("the activity recreated", STEP_MILLIS) { rig.creations.get() > made }
        rule.waitForIdle()
    }

    /** [check] once the display turned a quarter, then the display as the system turns it again. */
    private fun rotated(check: () -> Unit) {
        val made =
            rule.activity.rig.creations
                .get()
        assertTrue("the display turns", automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
        try {
            awaitRecreated(made)
            check()
        } finally {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    /** [check] once the foldable closed and the activity was made again, then the foldable as it was. */
    private fun folded(check: () -> Unit) {
        val foldable = "CLOSED" in shell("cmd device_state print-states")
        if (foldRequired()) assertTrue("a foldable", foldable) else assumeTrue("a foldable", foldable)
        val made =
            rule.activity.rig.creations
                .get()
        fold("0")
        try {
            awaitRecreated(made)
            rule.awaitAppFocus(rule.activity)
            check()
        } finally {
            fold("reset")
        }
    }

    /** The foldable put in device [state]; a fold can lock the phone, which is woken and unlocked here. */
    private fun fold(state: String) {
        shell("cmd device_state state $state")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    /** A draft half typed, and the list scrolled up to [ANCHOR_ROW]. */
    private fun draftingAboveTheBottom() {
        rule.onNode(hasSetTextAction()).performTextReplacement(DRAFT)
        rule.onNode(hasScrollToKeyAction()).performScrollToKey("row:$ANCHOR_ROW")
        rule.onNodeWithText("row $ANCHOR_ROW").assertIsDisplayed()
    }

    private fun assertStillDraftingThere() {
        rule.onNode(hasSetTextAction()).assertTextEquals(DRAFT, includeEditableText = true)
        rule.onNodeWithText("row $ANCHOR_ROW").assertIsDisplayed()
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
        rotated { assertStillDraftingThere() }
    }

    @Test
    fun a_rotation_keeps_the_working_indicators_phrase() {
        val phrase = thinkingPhrase()
        rotated { rule.onNodeWithText(phrase).assertIsDisplayed() }
    }

    @Test
    @FoldingPhone
    fun a_fold_keeps_the_draft_and_the_row_scrolled_to() {
        draftingAboveTheBottom()
        folded { assertStillDraftingThere() }
    }

    @Test
    @FoldingPhone
    fun a_fold_keeps_the_working_indicators_phrase() {
        val phrase = thinkingPhrase()
        folded { rule.onNodeWithText(phrase).assertIsDisplayed() }
    }
}
