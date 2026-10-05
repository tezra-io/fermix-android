package io.tezra.fermix.chats

import android.app.Activity
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** How long a step of a test may take on an emulator: a recreation, a window's focus. */
internal const val STEP_MILLIS = 15_000L

/**
 * [command] run by the device's shell, as the instrumentation may run it, and what it printed. The shell
 * holds what the tests ask of the system: the rotation and a foldable's state.
 */
internal fun shell(command: String): String {
    val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
}

/**
 * Waits for a window of [activity]'s app to have the focus, the activity's or a dialog's over it, where a
 * fold can leave the lock screen's: an open dialog holds the focus itself, so the activity's own window
 * never has it. Past [STEP_MILLIS] it fails with the window that has the focus, as `dumpsys window` names
 * it.
 */
internal fun AndroidComposeTestRule<*, *>.awaitAppFocus(activity: Activity) {
    val app = activity.packageName
    try {
        waitUntil("a window of $app has the focus", STEP_MILLIS) { app in focusedWindow() }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("no window of $app had the focus: ${focusedWindow()}", timeout)
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

/** How many nodes show [text] now: a poll for a bounded wait, which asks again after an idle. */
internal fun AndroidComposeTestRule<*, *>.countOf(text: String): Int =
    onAllNodesWithText(text).fetchSemanticsNodes().size

/** Whether one text field holds [text] and is displayed now: a poll for a bounded wait. */
internal fun AndroidComposeTestRule<*, *>.fieldShows(text: String): Boolean {
    val field = hasSetTextAction() and hasText(text)
    return onAllNodes(field).fetchSemanticsNodes().size == 1 && onNode(field).isDisplayed()
}
