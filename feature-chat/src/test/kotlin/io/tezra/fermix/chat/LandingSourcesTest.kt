package io.tezra.fermix.chat

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** How long a describe on free threads is given here: a file's name and length, read at once. */
private const val ANSWER_MILLIS = 5_000L

/** How long a describe that waits for a held thread is watched before it is called waiting. */
private const val WAITING_MILLIS = 500L

/**
 * Where PhoneMedia asks as an item lands: another app's paste, keyboard commit or share on the landing threads every
 * chat shares, the owner's own picks on the app's `io`, so providers that stall and hold every landing thread never
 * hold the owner's camera, Photo Picker or Files pick. On Robolectric, over a camera's capture in the app's cache, the
 * one source read with no provider, its landing threads every one held, as by providers that ignore their cancel.
 */
@RunWith(RobolectricTestRunner::class)
class LandingSourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val held = Executors.newFixedThreadPool(LANDING_THREADS)
    private val io = Executors.newSingleThreadExecutor()
    private val release = CountDownLatch(1)

    @After
    fun threadsBack() {
        release.countDown()
        held.shutdownNow()
        io.shutdownNow()
    }

    /** PhoneMedia whose every landing thread is held until the test ends, its `io` a thread of its own. */
    private fun heldMedia(): PhoneMedia {
        repeat(LANDING_THREADS) { held.execute { release.await() } }
        return PhoneMedia(context, { _, _ -> }, held.asCoroutineDispatcher(), io.asCoroutineDispatcher())
    }

    @Test
    fun `the camera's capture is described while another app's providers hold every landing thread`() {
        val media = heldMedia()
        val capture = File(context.cacheDir, "capture.jpg").apply { writeBytes(ByteArray(3)) }
        val picked =
            runBlocking {
                withTimeoutOrNull(ANSWER_MILLIS) { media.describe(capture.toURI().toString(), PickedFrom.CAMERA) }
            }
        assertEquals("capture.jpg" to 3L, picked?.let { it.name to it.sizeBytes })
    }

    @Test
    fun `another app's share is asked on the landing threads, and waits while they are held`() {
        val media = heldMedia()
        val answered =
            runBlocking {
                withTimeoutOrNull(WAITING_MILLIS) {
                    media.describe("content://another.app/1.png", PickedFrom.SHARE)
                    true
                }
            }
        assertEquals(null, answered)
    }
}
