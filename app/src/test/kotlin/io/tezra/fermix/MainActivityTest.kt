package io.tezra.fermix

import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.data.Instance
import io.tezra.fermix.onboarding.FailureCase
import io.tezra.fermix.onboarding.OnboardingKey
import io.tezra.fermix.onboarding.OnboardingViewModel
import io.tezra.fermix.onboarding.PairingWait
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** How long the screens may take to read the records, which DataStore does off the main thread. */
private const val SETTLE_MILLIS = 10_000L

private const val HOST = "suj-mbp"

/** Into the exit of a screen popped off the stack, which is still drawn, and still hit. */
private const val MID_EXIT_MILLIS = 150L

/**
 * The one activity on Robolectric, with the app's own services: the window kept out of screenshots while
 * onboarding shows (design sections 12.4 and 13.3), and the pairing-wait notification started as the app
 * leaves Verify for another (section 12.5).
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FermixApplication = ApplicationProvider.getApplicationContext()

    private fun secured(): Boolean = (rule.activity.window.attributes.flags and FLAG_SECURE) != 0

    private fun welcomeShows() =
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() }

    /** The activity's onboarding ViewModel, the one its screens drew. */
    private fun model(): OnboardingViewModel = ViewModelProvider(rule.activity)[OnboardingViewModel::class.java]

    private fun lightBars(): Pair<Boolean, Boolean> {
        val bars = WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
        return bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
    }

    @Test
    fun `Welcome keeps the window out of screenshots, and the Chats list lets it be seen again`() {
        welcomeShows()
        assertTrue(secured())
        runBlocking { app.services.instances.upsert(paired()) }
        rule.waitUntil(SETTLE_MILLIS) { !secured() }
        assertFalse(secured())
    }

    @Test
    fun `a second tap on a screen already leaving is dropped`() {
        welcomeShows()
        // Robolectric's Keystore reports no hardware Curve25519, so the gate fails, as on such a phone.
        rule.onNodeWithText("Get started").performClick()
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("OK").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(OnboardingKey.Failure(FailureCase.NO_SECURE_HARDWARE)), model().stack.value)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("OK").performClick()
        rule.mainClock.advanceTimeBy(MID_EXIT_MILLIS)
        rule.onNodeWithText("OK").performClick()
        rule.mainClock.autoAdvance = true
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("OK").fetchSemanticsNodes().isEmpty() }
        assertEquals(emptyList<OnboardingKey>(), model().stack.value)
    }

    @Test
    fun `the camera's frame has white bars over it in light mode, and the next screen has the theme's again`() {
        welcomeShows()
        assertEquals(true to true, lightBars())
        rule.runOnUiThread { fitWindow(rule.activity, OnboardingKey.Scan) }
        assertEquals(false to false, lightBars())
        assertFalse(rule.activity.window.isNavigationBarContrastEnforced)
        rule.runOnUiThread { fitWindow(rule.activity, OnboardingKey.Pair) }
        assertEquals(true to true, lightBars())
        assertTrue(rule.activity.window.isNavigationBarContrastEnforced)
    }

    @Test
    fun `leaving the app while Verify waits starts the pairing-wait notification`() {
        welcomeShows()
        app.services.pairingWait.value = PairingWait(HOST, TestTimeSource().markNow() + 102.seconds)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(app.services.inBackground.value)
        val started = shadowOf(app).nextStartedService
        assertEquals(PairingWaitService::class.java.name, started?.component?.className)
    }

    @Test
    fun `leaving the app with nothing waiting starts nothing`() {
        welcomeShows()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(app.services.inBackground.value)
        assertNull(shadowOf(app).nextStartedService)
    }
}

/** A record as an approved pairing with suj-mbp leaves it. */
private fun paired(): Instance =
    Instance(
        gatewayPk = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        tlsFp = "02".repeat(32),
        host = HOST,
        profile = "fermix",
        label = HOST,
        tint = "Slate",
        candidates = listOf(Candidate("100.101.102.103", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
        port = 4031,
        deviceId = "device-1",
        keyAlias = "fermix.device.1.0102030405060708",
        pushSalt = "c3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3M=",
        pushPlatforms = emptyList(),
        notificationsEnabled = false,
    )
