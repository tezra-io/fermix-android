package io.tezra.fermix.onboarding

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.tezra.fermix.session.PairingState
import kotlin.time.TimeMark

// The steps the instrumented tests share, through the test activity's rig and ViewModel.

/** The code the fake ceremony shows: the IKpsk2 vector's SAS, as the previews show it. */
internal const val TEST_SAS = "669979"

/** [TEST_SAS] as TalkBack reads it, digit by digit (design section 13.8). */
internal const val SPOKEN_TEST_SAS = "6 6 9, 9 7 9"

/** How long one step may take on a slow emulator, a software-rendered one in CI among them, before it fails. */
internal const val STEP_MILLIS = 15_000L

/** The rule over the test activity, which onboarding's flow is drawn in. */
internal typealias OnboardingRule =
    AndroidComposeTestRule<ActivityScenarioRule<OnboardingTestActivity>, OnboardingTestActivity>

/** Waits for [key]'s screen on top, then for its entrance to end. */
internal fun OnboardingRule.awaitTop(key: OnboardingKey) {
    val model = activity.onboarding
    waitUntil("$key on top", STEP_MILLIS) { topOf(model.stack.value) == key }
    waitForIdle()
}

/** The system's back, once the activity's window has the focus Espresso's key event needs. */
internal fun OnboardingRule.back() {
    awaitWindowFocus(activity)
    Espresso.pressBack()
}

/** [text] read off a code by the scan's stub camera, on the main thread, as the camera hands it over. */
internal fun OnboardingRule.scanned(text: String) {
    val rig = activity.rig
    runOnUiThread { rig.read(text) }
}

/** Welcome's "Get started", then Pair's "Scan the code", the camera allowed as [cameraAllowed] says. */
internal fun OnboardingRule.toScan(cameraAllowed: Boolean = true) {
    activity.rig.cameraAllowed = cameraAllowed
    onNodeWithText("Get started").performClick()
    awaitTop(OnboardingKey.Pair)
    onNodeWithText("Scan the code").performClick()
    awaitTop(OnboardingKey.Scan)
}

/** A link scanned, and its ceremony's code shown until [expiresAt]: Verify. */
internal fun OnboardingRule.toVerify(expiresAt: TimeMark) {
    toScan()
    scanned(linkText())
    awaitTop(OnboardingKey.Connecting)
    activity.rig.starter.control.state.value = PairingState.Verify(TEST_SAS, expiresAt, PHONE)
    awaitTop(OnboardingKey.Verify)
}
