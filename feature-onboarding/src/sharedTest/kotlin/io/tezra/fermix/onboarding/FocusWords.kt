package io.tezra.fermix.onboarding

// What a device test says when its activity never had the window focus: pure, so the JVM tests hold its words.

/** The title the system gives the window of its dialog for an app that is not responding, and the app's process. */
private val HUNG = Regex("""Application Not Responding: ([^\s}]+)""")

/** The title of the window of the system's "has stopped" dialog (AppErrorDialog), and the app's process. */
private val STOPPED = Regex("""Application Error: ([^\s}]+)""")

/**
 * Why [activity] never had the window focus, read off [focus], the `mCurrentFocus` line of `dumpsys window`. A system
 * dialog for an app that hung or stopped is named as the cause, so a failure says it was the emulator, not the test: on
 * 2026-10-09 CI's emulator kept the launcher's "isn't responding" over eighteen of this module's tests (Task 19b),
 * which the window's title alone left to be worked out. Any other window is named as it is.
 */
internal fun noFocusWords(
    activity: String,
    focus: String,
): String {
    require(activity.isNotBlank()) { "an activity's name" }
    val hung = HUNG.find(focus)?.groupValues?.get(1)
    val stopped = STOPPED.find(focus)?.groupValues?.get(1)
    val app = hung ?: stopped ?: return "$activity never had the window focus: $focus"
    val what = if (hung != null) "hung" else "stopped"
    return "the emulator's $app $what and its dialog has the window focus, so $activity never had it: $focus"
}
