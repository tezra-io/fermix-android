package io.tezra.fermix.chats

import android.app.UiAutomation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** A name half chosen in a rename dialog, which a rotation or a fold keeps. */
private const val HALF_NAMED = "Studio on the"

/**
 * The runner argument CI's folding profile sets (`-Pandroid.testInstrumentationRunnerArguments.requireFold=true`),
 * under which a device that cannot fold fails the fold tests rather than skip them.
 */
private const val REQUIRE_FOLD = "requireFold"

/**
 * A rotation and a fold on a device (design section 13.11, rule 3) recreate the activity, and the Chats list
 * and the Instance screen drawn again lose nothing: a rename dialog open with a name half typed stays so.
 * The folds run on a foldable, as a fold AVD is; elsewhere they are skipped, unless the run requires a fold.
 */
class WindowChangeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatsTestActivity>()

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

    /** The Chats list's rename dialog for the first row, a name half typed in it. */
    private fun renamingOnTheList() {
        rule.onNodeWithText(HOST).performTouchInput { longClick() }
        rule.onNodeWithText("Rename").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(HALF_NAMED)
    }

    /** The Instance screen's rename dialog, opened from the name, a name half typed in it. */
    private fun renamingOnTheInstanceScreen() {
        rule.activity.rig.shown.value = Shown.INSTANCE
        rule.onNodeWithText(HOST).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(HALF_NAMED)
    }

    private fun assertStillNaming() {
        rule.onNodeWithText("Name this Fermix").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).assertTextContains(HALF_NAMED)
    }

    @Test
    fun a_rotation_keeps_the_chats_lists_rename_dialog_and_its_half_typed_name() {
        renamingOnTheList()
        rotated { assertStillNaming() }
    }

    @Test
    fun a_rotation_keeps_the_instance_screens_rename_dialog_and_its_half_typed_name() {
        renamingOnTheInstanceScreen()
        rotated { assertStillNaming() }
    }

    @Test
    @FoldingPhone
    fun a_fold_keeps_the_chats_lists_rename_dialog_and_its_half_typed_name() {
        renamingOnTheList()
        folded { assertStillNaming() }
    }

    @Test
    @FoldingPhone
    fun a_fold_keeps_the_instance_screens_rename_dialog_and_its_half_typed_name() {
        renamingOnTheInstanceScreen()
        folded { assertStillNaming() }
    }
}
