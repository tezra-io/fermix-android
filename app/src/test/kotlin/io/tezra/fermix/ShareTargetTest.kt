package io.tezra.fermix

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.chat.Shared
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The share entry as the system starts it (design section 13.6, "Share into Fermix"): it hands the share to the app
 * in the process and brings the activity forward with the launcher's own intent, then finishes; anything else it is
 * sent, and a share replayed from Recents, it finishes on with nothing handed and nothing started.
 */
@RunWith(RobolectricTestRunner::class)
class ShareTargetTest {
    private val app: FermixApplication = ApplicationProvider.getApplicationContext()

    @After
    fun nothingHanded() {
        app.services.shares.value = null
    }

    private fun entry(intent: Intent): ShareTarget {
        val named = intent.setComponent(ComponentName(app, SHARE_ENTRY))
        return Robolectric.buildActivity(ShareTarget::class.java, named).create().get()
    }

    @Test
    fun `a share is handed to the app, the activity brought forward, and the entry finishes`() {
        val entry = entry(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "look"))
        assertTrue(entry.isFinishing)
        assertEquals(Share(Shared(emptyList(), "look", emptyList(), 0), null), app.services.shares.value)
        val forward = shadowOf(app).nextStartedActivity
        assertEquals(ComponentName(app, MainActivity::class.java), forward.component)
        assertEquals(Intent.ACTION_MAIN, forward.action)
        assertNull(forward.extras)
    }

    @Test
    fun `anything but a share, and a share replayed from Recents, is nothing`() {
        val id = "ab".repeat(32)
        val sent =
            listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("fermix://chat/$id/main")),
                Intent(ACTION_ADD_FERMIX),
                Intent(Intent.ACTION_SEND).setType("text/plain"),
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "look")
                    .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
            )
        for (intent in sent) {
            assertTrue(entry(intent).isFinishing)
            assertNull("$intent", app.services.shares.value)
            assertNull("$intent", shadowOf(app).nextStartedActivity)
        }
    }
}
