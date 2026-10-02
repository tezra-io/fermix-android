package io.tezra.fermix

import android.app.Notification
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.onboarding.PairingWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Design section 12.5's pairing-wait service on Robolectric, over the app's own services: what it shows
 * while the app is out of sight, and that it ends itself when the app comes back or the wait ends.
 */
@RunWith(RobolectricTestRunner::class)
class PairingWaitServiceTest {
    private val app: FermixApplication = ApplicationProvider.getApplicationContext()

    /** The window's end, 1:42 away on a clock that stands still. */
    private val wait = PairingWait("suj-mbp", TestTimeSource().markNow() + 102.seconds)

    private fun started(): ServiceController<PairingWaitService> {
        val controller = Robolectric.buildService(PairingWaitService::class.java).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    private fun title(notification: Notification?): String? = notification?.extras?.getString(Notification.EXTRA_TITLE)

    @Test
    fun `out of sight, it says what Verify waits for, and ends itself when the app comes back`() {
        app.services.inBackground.value = true
        app.services.pairingWait.value = wait
        val service = shadowOf(started().get())
        assertEquals("Waiting for approval on suj-mbp · 1:42", title(service.lastForegroundNotification))
        assertFalse(service.isStoppedBySelf)
        app.services.inBackground.value = false
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.isForegroundStopped)
        assertTrue(service.isStoppedBySelf)
    }

    @Test
    fun `it ends itself when the wait ends, approved, refused or cancelled`() {
        app.services.inBackground.value = true
        app.services.pairingWait.value = wait
        val service = shadowOf(started().get())
        assertFalse(service.isStoppedBySelf)
        app.services.pairingWait.value = null
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.isStoppedBySelf)
    }

    @Test
    fun `started with nothing waiting, it ends at once and leaves no notification`() {
        app.services.inBackground.value = true
        val service = shadowOf(started().get())
        assertTrue(service.isForegroundStopped)
        assertNull(service.lastForegroundNotification)
        assertTrue(service.isStoppedBySelf)
    }

    @Test
    fun `the wait shows only while the app is out of sight`() {
        assertEquals(wait, pairingWaitShown(wait, inBackground = true))
        assertNull(pairingWaitShown(wait, inBackground = false))
        assertNull(pairingWaitShown(null, inBackground = true))
        assertNull(pairingWaitShown(null, inBackground = false))
    }
}
