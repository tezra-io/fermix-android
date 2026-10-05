package io.tezra.fermix.onboarding

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [command] run by the device's shell, as the instrumentation may run it, and what it printed. The shell
 * holds what the tests ask of the system: settings, the rotation, a foldable's state.
 */
internal fun shell(command: String): String {
    val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
}

/**
 * Waits for the rule's activity to have the window focus, which a key event and a read of the clipboard need: on an
 * emulator just booted, a system dialog can hold it, System UI's "isn't responding" among them. The activity is asked
 * for again at every poll: a fold can make it again while this waits, and the one made before never has the focus back.
 * Past [STEP_MILLIS] it fails with the window that has the focus, as `dumpsys window` names it.
 */
internal fun AndroidComposeTestRule<*, *>.awaitWindowFocus() {
    try {
        waitUntil("the activity has the window focus", STEP_MILLIS) { activity.hasWindowFocus() }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("${activity.localClassName} never had the window focus: ${focusedWindow()}", timeout)
    }
}

/** The window with the focus, as `dumpsys window` names it. */
private fun focusedWindow(): String = shell("dumpsys window").lines().filter { "mCurrentFocus" in it }.joinToString()

/**
 * Compose's idle, waited for [STEP_MILLIS] at most: Espresso's idle waits for the main thread with no bound of its own,
 * and after a fold it once never came back (Task 14c, Pixel_Fold_API_36.1: the instrumentation parked in Espresso's
 * FutureTask.get for 15 minutes, the main thread idle, until the emulator was killed). Past the bound it fails with the
 * window that has the focus, as `dumpsys window` names it, and the idle left waiting is interrupted.
 */
internal fun AndroidComposeTestRule<*, *>.idleWithin() {
    val idling = Executors.newSingleThreadExecutor()
    try {
        idling.submit { waitForIdle() }.get(STEP_MILLIS, TimeUnit.MILLISECONDS)
    } catch (stuck: TimeoutException) {
        throw AssertionError("the app was not idle after $STEP_MILLIS ms: ${focusedWindow()}", stuck)
    } catch (failed: ExecutionException) {
        throw failed.cause ?: failed
    } finally {
        idling.shutdownNow()
    }
}

/** Whether one node with [text] is displayed now: a poll for a bounded wait, which asks again after an idle. */
internal fun AndroidComposeTestRule<*, *>.displayed(text: String): Boolean =
    onAllNodesWithText(text).fetchSemanticsNodes().size == 1 && onNodeWithText(text).isDisplayed()

/** Whether one node TalkBack reads as [description] is displayed now: a poll for a bounded wait. */
internal fun AndroidComposeTestRule<*, *>.describedAs(description: String): Boolean =
    onAllNodesWithContentDescription(description).fetchSemanticsNodes().size == 1 &&
        onNodeWithContentDescription(description).isDisplayed()

/** Whether a text field holds [text] now: a poll for a bounded wait. */
internal fun AndroidComposeTestRule<*, *>.holds(text: String): Boolean =
    onAllNodes(hasSetTextAction() and hasText(text, substring = true)).fetchSemanticsNodes().size == 1
