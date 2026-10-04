package io.tezra.fermix

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
import org.robolectric.annotation.Config

/** How long the supervisor may take to see an upload end, which it does off the main thread. */
private const val SETTLE_MILLIS = 10_000L

/** How often a wait looks again. */
private const val POLL_MILLIS = 10L

/** The upload notification's id, as the service posts it. */
private const val UPLOAD_NOTIFICATION = 2

/**
 * Design section 12.5's upload service on Robolectric, over the app's own services with a session whose upload
 * the test says: what it shows while the app is out of sight, and that it ends itself when the upload ends, the
 * app comes back, or the platform's timeout comes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UploadingApplication::class)
class UploadServiceTest {
    private val app: UploadingApplication = ApplicationProvider.getApplicationContext()
    private val sessions = sessionScope()

    @After
    fun end() = sessions.cancel()

    private fun started(): ServiceController<UploadService> {
        val controller = Robolectric.buildService(UploadService::class.java).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    /** The upload notification as it was last posted. */
    private fun shownTitle(): String? {
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        return manager.getNotification(UPLOAD_NOTIFICATION)?.extras?.getString(Notification.EXTRA_TITLE)
    }

    /**
     * Runs the main thread until [done] or [SETTLE_MILLIS] pass: the service follows the records, which DataStore
     * reads off the main thread.
     */
    private fun settled(done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + SETTLE_MILLIS * 1_000_000
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        return done()
    }

    /** Waits for the supervisor to see no upload in flight. */
    private fun uploadEnds() {
        app.upload.value = false
        runBlocking {
            withTimeout(SETTLE_MILLIS) {
                app.services.supervisor.uploading
                    .first { it.isEmpty() }
            }
        }
    }

    @Test
    fun `out of sight with an upload in flight, it names the computer, and ends itself when the upload ends`() {
        app.upload.value = true
        app.hold(record(1), sessions)
        app.services.inBackground.value = true
        val service = shadowOf(started().get())
        settled { shownTitle() == "Sending to suj-mbp" }
        assertEquals("Sending to suj-mbp", shownTitle())
        assertFalse(service.isStoppedBySelf)
        uploadEnds()
        assertTrue(settled { service.isStoppedBySelf })
        assertTrue(service.isForegroundStopped)
    }

    @Test
    fun `it ends itself when the app comes back with the upload still in flight`() {
        app.upload.value = true
        app.hold(record(1), sessions)
        app.services.inBackground.value = true
        val service = shadowOf(started().get())
        settled { shownTitle() == "Sending to suj-mbp" }
        assertEquals("Sending to suj-mbp", shownTitle())
        assertFalse(service.isStoppedBySelf)
        app.services.inBackground.value = false
        assertTrue(settled { service.isStoppedBySelf })
    }

    @Test
    fun `at the platform's timeout it ends itself, and the upload stays the session's to finish or leave queued`() {
        app.upload.value = true
        val paired = record(1)
        app.hold(paired, sessions)
        app.services.inBackground.value = true
        val controller = started()
        val service = shadowOf(controller.get())
        settled { shownTitle() == "Sending to suj-mbp" }
        controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        assertTrue(service.isStoppedBySelf)
        assertTrue(service.isForegroundStopped)
        assertEquals(setOf(paired.id), app.services.supervisor.uploading.value)
    }

    @Test
    fun `started with no upload in flight, it ends at once`() {
        app.hold(record(1), sessions)
        app.services.inBackground.value = true
        val service = shadowOf(started().get())
        assertTrue(settled { service.isStoppedBySelf })
        assertTrue(service.isForegroundStopped)
    }

    @Test
    fun `the upload shows only out of sight, naming the computer once its record is read`() {
        val paired = record(1)
        assertEquals(UploadShown("suj-mbp"), uploadShown(setOf(paired.id), inBackground = true, listOf(paired)))
        assertEquals(UploadShown(null), uploadShown(setOf(paired.id), inBackground = true, emptyList()))
        assertNull(uploadShown(setOf(paired.id), inBackground = false, listOf(paired)))
        assertNull(uploadShown(emptySet(), inBackground = true, listOf(paired)))
    }
}
