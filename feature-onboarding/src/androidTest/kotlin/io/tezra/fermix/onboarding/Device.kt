package io.tezra.fermix.onboarding

import android.app.Activity
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

/**
 * [command] run by the device's shell, as the instrumentation may run it, and what it printed. The shell
 * holds what the tests ask of the system: settings, the rotation, a foldable's state.
 */
internal fun shell(command: String): String {
    val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
}

/**
 * Waits for [activity]'s window to have the focus, which a key event and a read of the clipboard need: on an
 * emulator just booted, a system dialog can hold it, System UI's "isn't responding" among them. Past
 * [STEP_MILLIS] it fails with the window that has the focus, as `dumpsys window` names it.
 */
internal fun AndroidComposeTestRule<*, *>.awaitWindowFocus(activity: Activity) {
    try {
        waitUntil("${activity.localClassName} has the window focus", STEP_MILLIS) { activity.hasWindowFocus() }
    } catch (timeout: ComposeTimeoutException) {
        val focus = shell("dumpsys window").lines().filter { "mCurrentFocus" in it }
        throw AssertionError("${activity.localClassName} never had the window focus: $focus", timeout)
    }
}
