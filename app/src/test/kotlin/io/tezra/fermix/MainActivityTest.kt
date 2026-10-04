package io.tezra.fermix

import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.net.Uri
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.launchCheck
import io.tezra.fermix.onboarding.FailureCase
import io.tezra.fermix.onboarding.OnboardingKey
import io.tezra.fermix.onboarding.OnboardingViewModel
import io.tezra.fermix.onboarding.PairingWait
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowBiometricPrompt
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** How long the screens may take to read the records, which DataStore does off the main thread. */
private const val SETTLE_MILLIS = 10_000L

private const val HOST = "suj-mbp"

/** Into the exit of a screen popped off the stack, which is still drawn, and still hit. */
private const val MID_EXIT_MILLIS = 150L

/**
 * The one activity on Robolectric, with the app's own services: the window kept out of screenshots while
 * onboarding shows (design sections 12.4 and 13.3), and the pairing-wait and upload notifications started as
 * the app leaves Verify, or a chat with an upload in flight, for another (section 12.5).
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FermixApplication = ApplicationProvider.getApplicationContext()

    /** Where a test's idle sessions run, ended with it. */
    private val sessions = sessionScope()

    @After
    fun end() = sessions.cancel()

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
    fun `the Instance screen, which shows the daemon's key, keeps the window out of screenshots while it shows`() {
        welcomeShows()
        runBlocking { app.services.instances.upsert(paired()) }
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(HOST).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(SETTLE_MILLIS) { !secured() }
        rule.onNodeWithText(HOST).performTouchInput { longClick() }
        rule.onNodeWithText("Details").performClick()
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("Key").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(SETTLE_MILLIS) { secured() }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("Key").fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(SETTLE_MILLIS) { !secured() }
    }

    @Test
    fun `a chat's link that reaches the running app opens that chat's screen over the list`() {
        welcomeShows()
        val record = paired()
        runBlocking { app.services.instances.upsert(record) }
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(HOST).fetchSemanticsNodes().isNotEmpty() }
        val navigator = ViewModelProvider(rule.activity)[AppNavigator::class.java]
        assertEquals(emptyList<NavKey>(), navigator.above.value)
        // A notification's tap or a conversation shortcut while the app is alive: the activity's onNewIntent.
        val link = chatIntent(app, record.id, "main")
        rule.runOnUiThread { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(rule.activity, link) }
        rule.waitUntil(SETTLE_MILLIS) { navigator.above.value == listOf(ChatKey(record.id, "main")) }
        // The Chat screen itself, its empty chat greeting the Fermix.
        rule.waitUntil(
            SETTLE_MILLIS,
        ) { rule.onAllNodesWithText("Say hello to $HOST.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the App lock switch turns on once the owner sets a screen lock and comes back to it`() {
        welcomeShows()
        runBlocking { app.services.instances.upsert(paired()) }
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(HOST).fetchSemanticsNodes().isNotEmpty() }
        val biometrics = Shadow.extract<ShadowBiometricManager>(app.getSystemService(BiometricManager::class.java))
        biometrics.setCanAuthenticate(false)
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("App lock").performClick()
        rule.waitUntil(SETTLE_MILLIS) {
            rule.onAllNodesWithText("Lock with biometrics").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Lock with biometrics").assertIsNotEnabled()
        rule.onNodeWithText("Set a screen lock on this phone to use it.").assertExists()
        // The owner sets a screen lock in the system's settings, and comes back.
        biometrics.setCanAuthenticate(true)
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(SETTLE_MILLIS) {
            rule.onAllNodesWithText("Set a screen lock on this phone to use it.").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("Lock with biometrics").assertIsEnabled()
    }

    @Test
    fun `a paired Fermix is a row of the Chats list, and its long-press opens its menu`() {
        welcomeShows()
        runBlocking { app.services.instances.upsert(paired()) }
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(HOST).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(HOST).performTouchInput { longClick() }
        for (label in listOf("Move to top", "Rename", "Details", "Unpair…")) rule.onNodeWithText(label).assertExists()
    }

    @Test
    fun `with the app lock on, a return past the grace locks the app, hides the list, and prompts for the unlock`() {
        welcomeShows()
        // A phone with a strong biometric enrolled, so that it can hold the lock.
        Shadow
            .extract<ShadowBiometricManager>(
                app.getSystemService(BiometricManager::class.java),
            ).setCanAuthenticate(true)
        runBlocking {
            app.services.instances.upsert(paired())
            app.services.settings.setAppLock(true)
        }
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(HOST).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(secured())
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ShadowSystemClock.advanceBy(Duration.ofMillis(BACKGROUND_GRACE_MILLIS + 1))
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("Fermix is locked").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(secured())
        rule.onNodeWithText("Unlock").assertExists()
        // Nothing of the list is drawn under the lock, or left for TalkBack.
        assertTrue(rule.onAllNodesWithText(HOST).fetchSemanticsNodes().isEmpty())
        // The system's prompt came as the lock did; its success opens the app.
        rule.waitUntil(SETTLE_MILLIS) { ShadowBiometricPrompt.getCurrentPrompt() != null }
        rule.runOnUiThread { ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully() }
        rule.waitUntil(SETTLE_MILLIS) { !secured() }
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(HOST).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a Fermix the launch check dropped is a Re-pair row on the Chats list, with no other Fermix paired`() {
        welcomeShows()
        runBlocking {
            app.services.instances.upsert(paired())
            // The restore left no key under the record's alias.
            launchCheck(app.services.instances) { false }
        }
        rule.waitUntil(SETTLE_MILLIS) {
            rule.onAllNodesWithText("Re-pair this Fermix").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(HOST).assertExists()
        assertTrue(rule.onAllNodesWithText("Get started").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a chat's link and the Add Fermix shortcut are what the activity acts on, and nothing else`() {
        val id = "ab".repeat(32)
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("fermix://chat/$id/main"))
        assertEquals(AppIntent.OpenChat(ChatKey(id, "main")), appIntentOf(link))
        assertEquals(AppIntent.AddFermix, appIntentOf(Intent(ACTION_ADD_FERMIX)))
        assertNull(appIntentOf(Intent(Intent.ACTION_VIEW, Uri.parse("fermix://chat/$id/main/extra"))))
        assertNull(appIntentOf(Intent(Intent.ACTION_MAIN)))
        assertEquals(link.data, chatIntent(app, id, "main").data)
        // No link is made that the activity would ignore.
        assertThrows(IllegalArgumentException::class.java) { chatLink(HOST, "main") }
        assertThrows(IllegalArgumentException::class.java) { chatLink(id, "main/extra") }
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
        rule.runOnUiThread { fitWindow(rule.activity, OnboardingKey.Scan, locked = false) }
        assertEquals(false to false, lightBars())
        assertFalse(rule.activity.window.isNavigationBarContrastEnforced)
        rule.runOnUiThread { fitWindow(rule.activity, OnboardingKey.Pair, locked = false) }
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

    @Test
    @Config(application = UploadingApplication::class)
    fun `leaving the app while a session uploads starts the upload notification`() {
        welcomeShows()
        val uploading = app as UploadingApplication
        uploading.upload.value = true
        uploading.hold(paired(), sessions)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val started = shadowOf(app).nextStartedService
        assertEquals(UploadService::class.java.name, started?.component?.className)
    }

    @Test
    @Config(application = UploadingApplication::class)
    fun `leaving the app with a session up and no upload in flight starts nothing`() {
        welcomeShows()
        (app as UploadingApplication).hold(paired(), sessions)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
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
