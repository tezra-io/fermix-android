package io.tezra.fermix.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val ACTIVITY = "io.tezra.fermix.onboarding.OnboardingTestActivity"

/**
 * The words the focus wait fails with on a device (`awaitWindowFocus`): a system dialog for an app that hung or stopped
 * is named as the cause, as CI's emulator once kept one of the launcher's over eighteen tests (Task 19b), and any other
 * window is named as it is.
 */
class FocusWordsTest {
    @Test
    fun `a dialog for an app that hung is named as the reason the activity never had the focus`() {
        val focus =
            "  mCurrentFocus=Window{e9164cf u0 Application Not Responding: com.google.android.apps.nexuslauncher}"
        assertEquals(
            "the emulator's com.google.android.apps.nexuslauncher hung and its dialog has the window focus, " +
                "so $ACTIVITY never had it: $focus",
            noFocusWords(ACTIVITY, focus),
        )
    }

    @Test
    fun `a dialog for an app that stopped, the system's has stopped, is named so`() {
        val focus = "  mCurrentFocus=Window{3f2a1b7 u0 Application Error: com.android.systemui}"
        assertEquals(
            "the emulator's com.android.systemui stopped and its dialog has the window focus, " +
                "so $ACTIVITY never had it: $focus",
            noFocusWords(ACTIVITY, focus),
        )
    }

    @Test
    fun `a dialog for a process of an app is named by the process`() {
        val focus = "  mCurrentFocus=Window{70c2d1e u0 Application Not Responding: com.android.phone:ui}"
        assertEquals(
            "the emulator's com.android.phone:ui hung and its dialog has the window focus, " +
                "so $ACTIVITY never had it: $focus",
            noFocusWords(ACTIVITY, focus),
        )
    }

    @Test
    fun `any other window is named as it is`() {
        val others =
            listOf(
                "  mCurrentFocus=Window{1c9e4a0 u0 NotificationShade}",
                "  mCurrentFocus=Window{88a0f3c u0 com.example.errors/com.example.errors.ApplicationErrorActivity}",
                "  mCurrentFocus=null",
                "",
            )
        for (focus in others) {
            assertEquals("$ACTIVITY never had the window focus: $focus", noFocusWords(ACTIVITY, focus))
        }
    }
}
