package io.tezra.fermix

import android.content.ComponentName
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.chat.ChatViewModel
import io.tezra.fermix.data.Instance
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowBiometricPrompt
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.Base64
import java.util.Locale

/** How long the screens may take to read the records, which DataStore does off the main thread. */
private const val SETTLE_MILLIS = 10_000L

private const val MAC = "suj-mbp"
private const val LINUX = "suj-linux"
private const val WORDS = "look at this"
private const val ASKS = "Send to which Fermix?"

/** A record as an approved pairing with [host] leaves it. */
private fun record(
    gateway: Int,
    host: String,
): Instance =
    Instance(
        gatewayPk = Base64.getEncoder().encodeToString(ByteArray(32) { gateway.toByte() }),
        tlsFp = "%02x".format(Locale.ROOT, gateway + 1).repeat(32),
        host = host,
        profile = "fermix",
        label = host,
        tint = "Slate",
        candidates = listOf(Candidate("100.101.102.$gateway", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
        port = 4031,
        deviceId = "device-$gateway",
        keyAlias = "fermix.device.$gateway.0102030405060708",
        pushSalt = Base64.getEncoder().encodeToString(ByteArray(32) { 0x73 }),
        pushPlatforms = emptyList(),
        notificationsEnabled = false,
    )

/**
 * Another app's share reaching the running app, as the share entry hands it to the one activity (design sections
 * 13.6 and 13.9): words land in the chat it goes to and nothing is sent; with several paired it asks "Send to which
 * Fermix?", through a rotation; a Direct Share naming no paired Fermix asks too; and with the app lock on the lock
 * comes first, the share waiting behind it and lost once the app leaves without the unlock.
 */
@RunWith(RobolectricTestRunner::class)
class ShareFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FermixApplication = ApplicationProvider.getApplicationContext()
    private val mac = record(1, MAC)
    private val linux = record(2, LINUX)

    private fun shows(text: String) =
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun pair(vararg records: Instance) {
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { records.forEach { app.services.instances.upsert(it) } }
        shows(records.last().host)
    }

    /**
     * Words shared through the entry as the system's share sheet starts it, while the activity is running: the entry
     * hands them to the app, and the activity takes them.
     */
    private fun share(shortcut: String? = null) {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .setComponent(ComponentName(app, SHARE_ENTRY))
                .putExtra(Intent.EXTRA_TEXT, WORDS)
        if (shortcut != null) intent.putExtra(Intent.EXTRA_SHORTCUT_ID, shortcut)
        rule.runOnUiThread { Robolectric.buildActivity(ShareTarget::class.java, intent).create() }
    }

    private fun above(): List<NavKey> = ViewModelProvider(rule.activity)[AppNavigator::class.java].above.value

    private fun shareState(): ShareState = ViewModelProvider(rule.activity)[ShareModel::class.java].state.value

    /** [record]'s main chat's draft, as its model holds it, once the share landed. */
    private fun draft(record: Instance): String {
        val key = "chat:${record.id}:main"
        rule.waitUntil(SETTLE_MILLIS) {
            ViewModelProvider(rule.activity)[key, ChatViewModel::class.java]
                .composer.field.value.text == WORDS
        }
        return ViewModelProvider(rule.activity)[key, ChatViewModel::class.java]
            .composer.field.value.text
    }

    /** The app lock on, and the app back past the grace, locked. */
    private fun lockedReturn() {
        Shadow
            .extract<ShadowBiometricManager>(
                app.getSystemService(BiometricManager::class.java),
            ).setCanAuthenticate(true)
        runBlocking { app.services.settings.setAppLock(true) }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ShadowSystemClock.advanceBy(Duration.ofMillis(BACKGROUND_GRACE_MILLIS + 1))
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        shows("Fermix is locked")
    }

    private fun sheetRow(host: String) = rule.onNode(hasText(host) and hasAnyAncestor(isDialog()))

    @Test
    fun `with one Fermix paired, shared words land at the end of its chat's draft and its chat shows`() {
        pair(mac)
        share()
        rule.waitUntil(SETTLE_MILLIS) { above() == listOf(ChatKey(mac.id, "main")) }
        assertEquals(WORDS, draft(mac))
        shows(WORDS)
        assertEquals(ShareState.None, shareState())
    }

    @Test
    fun `with two paired, the share asks which, through a rotation, and lands in the one picked`() {
        pair(mac, linux)
        share()
        shows(ASKS)
        assertEquals(emptyList<NavKey>(), above())
        rule.activityRule.scenario.recreate()
        shows(ASKS)
        sheetRow(LINUX).performClick()
        rule.waitUntil(SETTLE_MILLIS) { above() == listOf(ChatKey(linux.id, "main")) }
        assertEquals(WORDS, draft(linux))
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(ASKS).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a Direct Share naming no paired Fermix asks, and never opens a chat of its own`() {
        pair(mac)
        share(shortcut = "${"ab".repeat(32)}:main")
        shows(ASKS)
        sheetRow(MAC).assertExists()
        assertEquals(emptyList<NavKey>(), above())
    }

    @Test
    fun `a Direct Share naming a paired Fermix's conversation lands there without asking`() {
        pair(mac, linux)
        share(shortcut = conversationId(mac.id, "main"))
        rule.waitUntil(SETTLE_MILLIS) { above() == listOf(ChatKey(mac.id, "main")) }
        assertEquals(WORDS, draft(mac))
        assertTrue(rule.onAllNodesWithText(ASKS).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `with the app lock on, the lock comes first and the share lands once it is passed`() {
        pair(mac)
        lockedReturn()
        share()
        rule.waitForIdle()
        shows("Fermix is locked")
        assertEquals(emptyList<NavKey>(), above())
        assertTrue(rule.onAllNodesWithText(WORDS).fetchSemanticsNodes().isEmpty())
        rule.waitUntil(SETTLE_MILLIS) { ShadowBiometricPrompt.getCurrentPrompt() != null }
        rule.runOnUiThread { ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully() }
        rule.waitUntil(SETTLE_MILLIS) { above() == listOf(ChatKey(mac.id, "main")) }
        assertEquals(WORDS, draft(mac))
    }

    @Test
    fun `the sheet never shows over the lock, which comes back as it asks, and shows again once the lock is passed`() {
        pair(mac, linux)
        share()
        shows(ASKS)
        lockedReturn()
        rule.waitUntil(SETTLE_MILLIS) { (shareState() as? ShareState.Pending)?.behindLock == true }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(ASKS).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText(LINUX).fetchSemanticsNodes().isEmpty())
        assertEquals(emptyList<NavKey>(), above())
        rule.waitUntil(SETTLE_MILLIS) { ShadowBiometricPrompt.getCurrentPrompt() != null }
        rule.runOnUiThread { ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully() }
        shows(ASKS)
        sheetRow(MAC).performClick()
        rule.waitUntil(SETTLE_MILLIS) { above() == listOf(ChatKey(mac.id, "main")) }
        assertEquals(WORDS, draft(mac))
    }

    @Test
    fun `a share behind the lock is lost once the app leaves without the unlock`() {
        pair(mac)
        lockedReturn()
        share()
        rule.waitUntil(SETTLE_MILLIS) { (shareState() as? ShareState.Pending)?.behindLock == true }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(ShareState.None, shareState())
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        shows("Fermix is locked")
        rule.waitUntil(SETTLE_MILLIS) { ShadowBiometricPrompt.getCurrentPrompt() != null }
        rule.runOnUiThread { ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully() }
        shows(MAC)
        rule.waitForIdle()
        assertEquals(emptyList<NavKey>(), above())
        assertEquals(ShareState.None, shareState())
    }

    @Test
    fun `with no Fermix paired, a share is dropped and Welcome stays`() {
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() }
        share()
        rule.waitForIdle()
        assertEquals(ShareState.None, shareState())
        assertTrue(rule.onAllNodesWithText(ASKS).fetchSemanticsNodes().isEmpty())
        rule.onAllNodesWithText("Get started").fetchSemanticsNodes().single()
    }
}
