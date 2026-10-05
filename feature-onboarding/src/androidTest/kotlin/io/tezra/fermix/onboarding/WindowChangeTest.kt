package io.tezra.fermix.onboarding

import android.app.UiAutomation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** A half-typed link, which the paste sheet keeps through a rotation. */
private const val HALF_TYPED = "fermix://pair?v=2&candid"

/**
 * The runner argument CI's folding profile sets (`-Pandroid.testInstrumentationRunnerArguments.requireFold=true`),
 * under which a device that cannot fold fails the fold test rather than skip it: the task passes over a skip.
 */
private const val REQUIRE_FOLD = "requireFold"

/**
 * A rotation and a fold on a device (design section 13.11, rule 3): each recreates the activity, and the
 * screen drawn again from the kept ViewModel shows the same pairing, its countdown running on, or the paste
 * sheet with its half-typed link. The fold runs on a foldable, as a fold AVD is; elsewhere it is skipped,
 * unless the run requires a fold ([REQUIRE_FOLD]).
 */
class WindowChangeTest {
    @get:Rule
    val rule = createAndroidComposeRule<OnboardingTestActivity>()

    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    /** Whether this run asks for a fold, as CI's folding profile does ([REQUIRE_FOLD]). */
    private fun foldRequired(): Boolean = InstrumentationRegistry.getArguments().getString(REQUIRE_FOLD) == "true"

    /** Waits for the activity made after [made] creations, which a window change recreates. */
    private fun awaitRecreated(made: Int) {
        val rig = rule.activity.rig
        rule.waitUntil("the activity recreated", STEP_MILLIS) { rig.creations.get() > made }
        rule.idleWithin()
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

    /**
     * Verify shown at 1:42, then ten seconds later recreated: the screen drawn again counts from the end the
     * ViewModel kept, 1:32, where a countdown begun again would show 2:00, and the code is the same. The window made
     * again is waited for, bounded, until it shows them: its insets and its layout land a frame or more after the
     * activity's creation, a step of the system's that Compose's idle does not know.
     */
    private fun assertVerifyKept() {
        rule.waitUntil("Verify drawn again at 1:32 with its code", STEP_MILLIS) {
            rule.displayed("1:32") && rule.describedAs(SPOKEN_TEST_SAS)
        }
        rule.onNodeWithText("1:32").assertIsDisplayed()
        rule.onNodeWithContentDescription(SPOKEN_TEST_SAS).assertIsDisplayed()
        assertEquals(OnboardingKey.Verify, topOf(rule.activity.onboarding.stack.value))
        assertEquals("the pairing was never cancelled", 0, rule.activity.rig.starter.control.cancels)
    }

    /**
     * The foldable put in device [state] (0 is closed, `reset` its own). A fold locks the phone, as the
     * emulator's Pixel Fold does by default, at times only once the activity is made again; the test
     * activity shows over the lock screen (its manifest), and the phone is woken and its keyguard dismissed
     * here, as the owner would.
     */
    private fun folded(state: String) {
        shell("cmd device_state state $state")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @Test
    fun a_rotation_keeps_verify_its_code_and_its_countdown() {
        val time = TestTimeSource()
        val expiresAt = time.markNow() + 120.seconds
        time += 18.seconds
        rule.toVerify(expiresAt)
        rule.onNodeWithText("1:42").assertIsDisplayed()
        time += 10.seconds
        rotated { assertVerifyKept() }
    }

    @Test
    @FoldingPhone
    fun a_fold_keeps_verify_its_code_and_its_countdown() {
        val foldable = "CLOSED" in shell("cmd device_state print-states")
        if (foldRequired()) assertTrue("a foldable", foldable) else assumeTrue("a foldable", foldable)
        val time = TestTimeSource()
        val expiresAt = time.markNow() + 120.seconds
        time += 18.seconds
        rule.toVerify(expiresAt)
        rule.onNodeWithText("1:42").assertIsDisplayed()
        time += 10.seconds
        val made =
            rule.activity.rig.creations
                .get()
        folded("0")
        try {
            awaitRecreated(made)
            rule.awaitWindowFocus()
            assertVerifyKept()
        } finally {
            folded("reset")
        }
    }

    @Test
    fun a_rotation_keeps_the_paste_sheet_with_its_half_typed_link() {
        rule.onNodeWithText("Get started").performClick()
        rule.awaitTop(OnboardingKey.Pair)
        rule.onNodeWithText("Paste a pairing link").performClick()
        rule.onNode(hasSetTextAction()).performTextInput(HALF_TYPED)
        rotated {
            rule.waitUntil(
                "the paste sheet drawn again with its half-typed link",
                STEP_MILLIS,
            ) { rule.holds(HALF_TYPED) }
            rule.onNode(hasSetTextAction()).assertTextContains(HALF_TYPED)
        }
    }
}
