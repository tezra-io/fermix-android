package io.tezra.fermix.demo

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.MainActivity
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.TINT_NAMES
import io.tezra.fermix.instance.TestOutcome
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.session.WebSocketDialer
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import org.w3c.dom.Element
import java.io.File
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/** The most times the test lets the main thread run the entry's work, a few milliseconds apart, before it fails. */
private const val MAX_IDLES = 500
private const val IDLE_PAUSE_MILLIS = 10L

/** WebSocket's normal close, which ends the demo's link the test dialled. */
private const val NORMAL_CLOSURE = 1000

/** A pin no demo Fermix names: a real daemon's. */
private val REAL_PIN = ByteArray(32) { 0x4f }

/**
 * The debug app's demo wiring (README, "The demo"): the debug app's application is the demo's, and the services it
 * makes, every session, pairing and connection test, reach the demo for a demo pin only once the "Fermix demo"
 * entry has started the demo in this process, and the pinned WebSocket for every other pin, the release's; a dialer
 * made before the entry, as a session's is when it opens, reaches the demo at its next dial after it; the entry
 * copies a demo link under the demo Fermix's name, which the system's own preview confirms, and brings the app
 * forward; the debug manifest adds that one launcher entry, after the app's own, and no permission. On Robolectric,
 * which reads the debug variant's merged manifest.
 */
@RunWith(RobolectricTestRunner::class)
class DemoWiringTest {
    private val app: DemoApplication get() = ApplicationProvider.getApplicationContext<Application>() as DemoApplication

    @Test
    fun theDebugAppsServicesReachTheDemoForItsPinsOnlyOnceItsEntryStartedItAnyOtherPinsWebSocket() {
        val fermix = demoFermixes(DEMO_SEED).first()
        val record = recordOf(fermix)
        val tester = app.services.instanceParts().tester
        val before = app.dialerFor(DEMO_PORT, fermix.tlsFingerprint)
        assertEquals(TestOutcome.NotReached(failed = setOf(fermix.route)), runBlocking { tester.test(record) })
        assertThrows(TransportException.Unreachable::class.java) { runBlocking { before.dial(fermix.route) } }

        val link = launchDemoEntry()

        assertArrayEquals(fermix.tlsFingerprint, PairingLink.parse(link).tlsFingerprint)
        val reached = runBlocking { tester.test(record) }
        assertEquals("the services' test reaches the demo", fermix.route, (reached as? TestOutcome.Reached)?.candidate)
        val kept = runBlocking { before.dial(fermix.route) }
        assertTrue("a dialer made before the entry reaches the demo at its next dial", kept is MemoryLink)
        kept.close(NORMAL_CLOSURE, "")
        assertTrue(app.dialerFor(DEMO_PORT, REAL_PIN) is WebSocketDialer)
        assertTrue(app.dialerFor(DEMO_PORT + 1, fermix.tlsFingerprint) is WebSocketDialer)
    }

    @Test
    fun theDemoEntryCopiesTheNextDemoLinkUnderItsNameAndBringsTheAppForwardAsItsLauncherIconDoes() {
        val link = launchDemoEntry()

        val parsed = PairingLink.parse(link)
        assertEquals("suj-mbp" to "fermix", parsed.name to parsed.profile)
        val clip = checkNotNull(app.getSystemService(ClipboardManager::class.java).primaryClip)
        assertEquals("Fermix demo: suj-mbp · fermix", clip.description.label.toString())
        // The system confirms every copy itself (Android 13 and on), and its preview covered a toast of the entry's.
        assertEquals(0, ShadowToast.shownToastCount())
        val forward = checkNotNull(shadowOf(app).nextStartedActivity) { "the entry started nothing" }
        assertEquals(MainActivity::class.java.name, forward.component?.className)
        assertEquals(Intent.ACTION_MAIN, forward.action)
        assertTrue(forward.hasCategory(Intent.CATEGORY_LAUNCHER))
    }

    @Test
    fun theDebugAppsLaunchIsTheAppsOwnTheDemoASecondEntryAndTheDebugManifestAsksNoPermission() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val entries = app.packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        val labels = entries.associate { it.activityInfo.name to it.loadLabel(app.packageManager).toString() }
        assertEquals(setOf(MainActivity::class.java.name, DemoEntry::class.java.name), labels.keys)
        assertEquals("Fermix demo", labels[DemoEntry::class.java.name])

        val manifest =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                File("src/debug/AndroidManifest.xml"),
            )
        // The merge keeps this manifest's order, and a phone's launch by package is its first launcher activity,
        // which Robolectric does not keep (it lists them by name): the app's own activity comes first here.
        val first = manifest.getElementsByTagName("activity").item(0) as Element
        val firstName = first.getAttribute("android:name")
        assertEquals("a start by package is the app's", MainActivity::class.java.name, firstName)
        assertEquals(0, manifest.getElementsByTagName("uses-permission").length)
        assertEquals(0, manifest.getElementsByTagName("permission").length)
    }

    /** Starts the "Fermix demo" entry and runs the main thread until it finished; the link it copied. */
    private fun launchDemoEntry(): String {
        val entry = Robolectric.buildActivity(DemoEntry::class.java).setup().get()
        var idles = 0
        while (!entry.isFinishing && idles < MAX_IDLES) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(IDLE_PAUSE_MILLIS)
            idles++
        }
        assertTrue("the entry finished within $MAX_IDLES idles", entry.isFinishing)
        val clip = checkNotNull(app.getSystemService(ClipboardManager::class.java).primaryClip)
        return clip.getItemAt(0).text.toString()
    }
}

/** The record a pairing with [fermix] writes, whose connection the Instance screen tests. */
private fun recordOf(fermix: DemoFermix): Instance {
    val base64 = Base64.getEncoder()
    return Instance(
        gatewayPk = base64.encodeToString(fermix.gatewayKey.publicKey),
        tlsFp = fermix.tlsFingerprint.toHexString(),
        host = fermix.host,
        profile = fermix.profile,
        label = fermix.agent,
        tint = TINT_NAMES.first(),
        candidates = listOf(fermix.route),
        port = DEMO_PORT,
        deviceId = "demo-${fermix.index}-1",
        keyAlias = "demo-wiring",
        pushSalt = base64.encodeToString(fermix.pushSalt),
        pushPlatforms = emptyList(),
        notificationsEnabled = false,
    )
}
