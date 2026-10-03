package io.tezra.fermix.chat

import android.app.Activity
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

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
