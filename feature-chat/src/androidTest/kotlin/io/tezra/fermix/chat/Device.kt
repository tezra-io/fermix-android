package io.tezra.fermix.chat

import android.app.Activity
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** How long a step of a test may take on an emulator: a recreation, a window's focus. */
internal const val STEP_MILLIS = 15_000L

/** The limit a send's own bytes are held to in a test of something else: the engine's own default, 20 MiB. */
internal const val SEND_LIMIT_BYTES = 20L * 1024 * 1024

/**
 * The runner argument CI's folding profile sets (`-Pandroid.testInstrumentationRunnerArguments.requireFold=true`),
 * under which a device that cannot fold fails the fold tests rather than skip them.
 */
private const val REQUIRE_FOLD = "requireFold"

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

/**
 * Waits, bounded, for one node with each of [texts] displayed: a window a rotation or a fold made again takes its
 * insets and its layout a frame or more after the activity's creation, a step of the system's that Compose's idle
 * does not know, so what it shows is asked for again until it lands, for at most [STEP_MILLIS].
 */
internal fun AndroidComposeTestRule<*, *>.awaitDisplayed(vararg texts: String) {
    waitUntil("${texts.joinToString()} displayed", STEP_MILLIS) {
        texts.all { onAllNodesWithText(it).fetchSemanticsNodes().size == 1 && onNodeWithText(it).isDisplayed() }
    }
}

/** [check] once the display turned a quarter and the activity was made again, then the display as it was. */
internal fun AndroidComposeTestRule<*, ChatTestActivity>.rotated(check: () -> Unit) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val made = activity.rig.creations.get()
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
 * [check] once the foldable closed and the activity was made again, then the foldable as it was. On a device
 * that cannot fold it is skipped, unless the run requires a fold, when it fails.
 */
internal fun AndroidComposeTestRule<*, ChatTestActivity>.folded(check: () -> Unit) {
    val foldable = "CLOSED" in shell("cmd device_state print-states")
    val required = InstrumentationRegistry.getArguments().getString(REQUIRE_FOLD) == "true"
    if (required) assertTrue("a foldable", foldable) else assumeTrue("a foldable", foldable)
    val made = activity.rig.creations.get()
    fold("0")
    try {
        awaitRecreated(made)
        awaitAppFocus(activity)
        check()
    } finally {
        fold("reset")
    }
}

private fun AndroidComposeTestRule<*, ChatTestActivity>.awaitRecreated(made: Int) {
    val rig = activity.rig
    waitUntil("the activity recreated", STEP_MILLIS) { rig.creations.get() > made }
    idleWithin()
}

/** The foldable put in device [state]; a fold can lock the phone, which is woken and unlocked here. */
private fun fold(state: String) {
    shell("cmd device_state state $state")
    shell("input keyevent KEYCODE_WAKEUP")
    shell("wm dismiss-keyguard")
}
