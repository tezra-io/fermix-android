package io.tezra.fermix.chat

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How long a test's take records. */
private const val TAKE_MS = 1_500L

/** How far a read length may stand from the take's clock: a frame's start and the encoder's tail. */
private const val LENGTH_SLACK_MS = 500L

/** How long the take waits to hear it lost audio focus. */
private const val FOCUS_WAIT_S = 5L

/** OGG's capture pattern, the first four bytes of every page. */
private const val OGG_MAGIC = "OggS"

/**
 * The phone's microphone and player on a device (design sections 8.5 and 13.5, D15): a take records OGG/Opus, from
 * which the player reads its length and its levels, one every SAMPLE_MS, which draw a note's bars whoever recorded
 * it; another app taking audio focus, as a call does, interrupts the take on the main thread.
 */
class PhoneVoiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val logged: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val recorder = PhoneRecorder(context) { message, _ -> logged += message }
    private val take = File(context.cacheDir, "voice-device-test.ogg")

    @After
    fun end() {
        instrumentation.runOnMainSync { recorder.stop() }
        take.delete()
    }

    /** Starts the take on the main thread, as the chat does; [onInterrupted] hears the phone cut it. */
    private fun record(onInterrupted: () -> Unit) {
        instrumentation.runOnMainSync { recorder.start(take, onInterrupted) }
    }

    @Test
    fun a_take_records_ogg_opus_whose_length_and_levels_the_player_reads() {
        record { error("nothing cut the take") }
        SystemClock.sleep(TAKE_MS)
        var kept = false
        instrumentation.runOnMainSync { kept = recorder.stop() }
        assertTrue("the take kept audio: $logged", kept)
        assertEquals(OGG_MAGIC, take.readBytes().copyOf(OGG_MAGIC.length).decodeToString())
        val read = PhonePlayer().read(take)
        val length = read.durationMs
        assertTrue("its length, $length ms", length in TAKE_MS - LENGTH_SLACK_MS..TAKE_MS + LENGTH_SLACK_MS)
        val levels = read.levels.size.toLong()
        assertTrue("$levels levels for $length ms", levels in length / SAMPLE_MS - 2..length / SAMPLE_MS + 2)
        assertTrue("levels within a bar: ${read.levels}", read.levels.all { it in QUIET_LEVEL..1f })
        assertEquals(WAVE_BARS, barsOf(read.levels).size)
    }

    @Test
    fun another_app_taking_audio_focus_interrupts_the_take_on_the_main_thread() {
        val interrupted = CountDownLatch(1)
        var onMain = false
        record {
            onMain = Looper.myLooper() == Looper.getMainLooper()
            interrupted.countDown()
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(media).build()
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(other))
            assertTrue("the take heard it lost audio focus", interrupted.await(FOCUS_WAIT_S, TimeUnit.SECONDS))
            assertTrue("on the main thread", onMain)
        } finally {
            audio.abandonAudioFocusRequest(other)
        }
    }
}
