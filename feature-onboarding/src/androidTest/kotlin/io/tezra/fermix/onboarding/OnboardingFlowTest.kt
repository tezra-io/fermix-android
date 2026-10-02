package io.tezra.fermix.onboarding

import android.Manifest
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.transport.NetworkFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A phone on a network with no VPN: Tailscale off. */
private val NO_VPN = NetworkFacts(1L, defaultHasVpn = false, defaultHasCgnatAddress = false, otherUidVpnPresent = false)

/** Tailscale on, with this app split out of it. */
private val SPLIT_OUT = NO_VPN.copy(otherUidVpnPresent = true)

/** Another VPN holds the phone's one VPN slot. */
private val OTHER_VPN = NO_VPN.copy(defaultHasVpn = true)

/** Every candidate failed, the tailnet one among them. */
private val UNREACHED = PairingState.CannotReach(mapOf(TAILNET to IOException("connection refused")))

/** A ceremony's ending, the network as it ended, and the failure screen they lead to (design section 13.3). */
private class Ending(
    val state: PairingState.Ended,
    val network: NetworkFacts,
    val case: FailureCase,
)

/** Each ending a ceremony reaches on the phone's Scan; no secure hardware last, as it stands on Welcome alone. */
private val ENDINGS =
    listOf(
        Ending(PairingState.Expired, NO_VPN, FailureCase.EXPIRED),
        Ending(PairingState.Denied, NO_VPN, FailureCase.DENIED),
        Ending(PairingState.RateLimited, NO_VPN, FailureCase.RATE_LIMITED),
        Ending(PairingState.AnotherPairingInProgress, NO_VPN, FailureCase.ANOTHER_PAIRING),
        Ending(UNREACHED, NO_VPN, FailureCase.CANT_REACH),
        Ending(UNREACHED, SPLIT_OUT, FailureCase.EXCLUDED_FROM_TAILSCALE),
        Ending(UNREACHED, OTHER_VPN, FailureCase.VPN_HOLDS_THE_SLOT),
        Ending(PairingState.WrongMachine, NO_VPN, FailureCase.WRONG_MACHINE),
        Ending(PairingState.OlderFermix, NO_VPN, FailureCase.OLDER_FERMIX),
        Ending(PairingState.NewerFermix, NO_VPN, FailureCase.NEWER_FERMIX),
        Ending(PairingState.AttestationRefused, NO_VPN, FailureCase.ATTESTATION_REFUSED),
        Ending(PairingState.AttestationUnavailable, NO_VPN, FailureCase.ATTESTATION_UNAVAILABLE),
        Ending(PairingState.LostMidWait, NO_VPN, FailureCase.LOST_MID_WAIT),
        Ending(PairingState.ProtocolError("an unknown frame"), NO_VPN, FailureCase.PROTOCOL_ERROR),
    )

/**
 * Onboarding on a device, with no daemon and no camera (CI/CD design section 3, `ui`; design section 13.3):
 * the fake pairing control stands in for the daemon and a stub preview for the camera. Each test starts on
 * Welcome in a new activity, with a new rig.
 */
class OnboardingFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<OnboardingTestActivity>()

    private fun assertActions(vararg expected: String) = assertEquals(expected.sorted(), rule.actionLabels().sorted())

    private fun assertTitle(case: FailureCase) {
        rule.onNodeWithText(rule.activity.getString(case.title, HOST)).assertIsDisplayed()
    }

    /**
     * Compose's accessibility on in [view], as TalkBack turns it on: the instrumentation's UiAutomation,
     * connected, turns the app's accessibility on, and Compose, which ignores UiAutomation, is told to work
     * out what a screen reader would read, its order among it.
     */
    private fun accessibilityOn(view: View) {
        // Reading it connects it: that connection is what turns the app's accessibility on.
        val connected = InstrumentationRegistry.getInstrumentation().uiAutomation
        checkNotNull(connected) { "the instrumentation's UiAutomation connects" }
        val manager = rule.activity.getSystemService(AccessibilityManager::class.java)
        rule.waitUntil("accessibility on", STEP_MILLIS) { manager.isEnabled }
        rule.runOnUiThread { (view as ViewRootForTest).forceAccessibilityForTesting(true) }
        rule.waitForIdle()
    }

    @Test
    fun welcome_to_paired_over_the_stub_camera_and_the_fake_ceremony() {
        val rig = rule.activity.rig
        assertActions("Get started", "Don't have Fermix yet?")
        rule.onNodeWithText("Get started").performClick()
        rule.awaitTop(OnboardingKey.Pair)
        assertActions("Back", "Copy", "Rename", "Scan the code", "Paste a pairing link")
        rule.onNodeWithText("Scan the code").performClick()
        rule.awaitTop(OnboardingKey.Scan)
        rule.onNodeWithText("Allow the camera to scan the code").assertIsDisplayed()
        rig.prompts.answer = true
        rule.onNodeWithText("Continue").performClick()
        rule.waitUntil("the camera's preview", STEP_MILLIS) {
            rule.onAllNodesWithTag(STUB_PREVIEW).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(Manifest.permission.CAMERA), rig.prompts.asked)
        assertActions("Back", "Torch", "Paste a pairing link")
        rule.onNodeWithContentDescription("Torch").assertIsOff().performClick()
        rule.onNodeWithContentDescription("Torch").assertIsOn()
        rule.scanned(linkText())
        rule.awaitTop(OnboardingKey.Connecting)
        rig.starter.control.state.value =
            PairingState.Verify(TEST_SAS, TimeSource.Monotonic.markNow() + 120.seconds, PHONE)
        rule.awaitTop(OnboardingKey.Verify)
        rule.onNodeWithContentDescription(SPOKEN_TEST_SAS).assertIsDisplayed()
        assertActions("Cancel")
        // The paired session an approval hands over runs in the rig's scope until the driver closes it.
        rig.starter.control.state.value = PairingState.Approved(facts(), idleSession(rig.viewModelScope))
        rule.awaitTop(OnboardingKey.Paired)
        rule.onNodeWithText("Paired with $HOST").assertIsDisplayed()
        assertActions("Continue")
        assertEquals("the record was stored, replacing none", listOf<String?>(null), rig.starter.control.replaced)
    }

    @Test
    fun a_link_the_scan_refuses_is_said_on_the_scan() {
        rule.toScan()
        rule.scanned("https://example.com/pair")
        rule.onNodeWithText("That's not a Fermix pairing code.").assertIsDisplayed()
        assertEquals(OnboardingKey.Scan, topOf(rule.activity.onboarding.stack.value))
        assertEquals("no ceremony began", 0, rule.activity.rig.starter.started.size)
    }

    @Test
    fun with_the_camera_refused_the_scan_offers_settings_and_the_paste_which_clears_the_clip_it_took() {
        val rig = rule.activity.rig
        rule.toScan(cameraAllowed = false)
        rig.prompts.answer = false
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Camera is off for Fermix").assertIsDisplayed()
        assertActions("Back", "Open settings", "Paste a pairing link")
        rule.onNodeWithText("Paste a pairing link").performClick()
        rule.onNodeWithContentDescription("Paste").assertIsDisplayed()
        rig.clip.held = "https://example.com/pair"
        rule.onNodeWithContentDescription("Paste").performClick()
        rule.onNodeWithText("That's not a Fermix pairing code.").assertIsDisplayed()
        assertEquals("the owner's own text stays on the clipboard", listOf("text"), rig.clip.calls)
        rig.clip.held = linkText()
        rule.onNodeWithContentDescription("Paste").performClick()
        rule.awaitTop(OnboardingKey.Connecting)
        assertEquals(listOf("text", "text", "clear"), rig.clip.calls)
        assertNull(rig.clip.held)
    }

    @Test
    fun every_failure_screen_shows_its_title_and_labels_its_actions() {
        val rig = rule.activity.rig
        val shown = mutableSetOf<FailureCase>()
        rig.gate = GateResult.NoHardwareCurve25519
        rule.onNodeWithText("Get started").performClick()
        rule.awaitTop(OnboardingKey.Failure(FailureCase.NO_SECURE_HARDWARE))
        assertTitle(FailureCase.NO_SECURE_HARDWARE)
        rule.actionLabels()
        shown += FailureCase.NO_SECURE_HARDWARE
        rule.back()
        rule.awaitTop(OnboardingKey.Welcome)
        rig.gate = GateResult.Ok
        rule.toScan()
        for (ending in ENDINGS) {
            rig.network.value = ending.network
            rule.scanned(linkText())
            rule.awaitTop(OnboardingKey.Connecting)
            rig.starter.control.state.value = ending.state
            rule.awaitTop(OnboardingKey.Failure(ending.case))
            assertTitle(ending.case)
            rule.actionLabels()
            shown += ending.case
            rule.back()
            rule.awaitTop(OnboardingKey.Scan)
        }
        assertEquals(FailureCase.entries.toSet(), shown)
    }

    @Test
    fun a_link_of_another_version_is_refused_on_the_phone_with_its_own_screen() {
        rule.toScan()
        rule.scanned(linkText().replace("v=2", "v=1"))
        rule.awaitTop(OnboardingKey.Failure(FailureCase.OLDER_FERMIX))
        assertTitle(FailureCase.OLDER_FERMIX)
        rule.back()
        rule.awaitTop(OnboardingKey.Scan)
        rule.scanned(linkText().replace("v=2", "v=3"))
        rule.awaitTop(OnboardingKey.Failure(FailureCase.NEWER_FERMIX))
        assertTitle(FailureCase.NEWER_FERMIX)
        assertEquals("the phone refused both links itself", 0, rule.activity.rig.starter.started.size)
    }

    @Test
    fun talkback_reaches_the_paste_first_on_scan_with_the_camera_and_without() {
        rule.toScan()
        val view = composeViewOf(rule.activity)
        accessibilityOn(view)
        assertEquals("Paste a pairing link", rule.talkBackOrder(view).first())
        rule.activity.rig.prompts.answer = false
        rule.activity.rig.cameraAllowed = false
        rule.back()
        rule.awaitTop(OnboardingKey.Pair)
        rule.onNodeWithText("Scan the code").performClick()
        rule.awaitTop(OnboardingKey.Scan)
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Camera is off for Fermix").assertIsDisplayed()
        assertEquals("Paste a pairing link", rule.talkBackOrder(view).first())
    }
}
