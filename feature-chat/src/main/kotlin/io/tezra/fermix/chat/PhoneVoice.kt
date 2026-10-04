package io.tezra.fermix.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException

/** The voice note's bitrate: Opus speech at 32 kbps, a minute some 240 KB. */
private const val VOICE_BITRATE = 32_000

/** Opus's own sampling rate. */
private const val VOICE_SAMPLE_RATE = 48_000

/**
 * The phone's microphone for a voice note (design section 8.5, D15): MediaRecorder writing OGG/Opus, mono, from
 * the microphone, holding transient exclusive audio focus while it records. Losing that focus, as an incoming call
 * or another app's recording takes it, and a recorder error, call `onInterrupted` on the main thread; the chat
 * stops the take into a draft. A stop that kept no audio, a hold too short for a frame, is told to [log] and
 * returns false.
 */
class PhoneRecorder(
    private val context: Context,
    private val log: (String, Throwable?) -> Unit,
) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var focus: AudioFocusRequest? = null

    override fun start(
        into: File,
        onInterrupted: () -> Unit,
    ) {
        check(recorder == null) { "a take records already" }
        val audio = context.getSystemService(AudioManager::class.java)
        val request = focusRequest { if (it < 0) onInterrupted() }
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw IOException("audio focus was refused")
        }
        val made = recorderInto(into)
        made.setOnErrorListener { _, _, _ -> onInterrupted() }
        var started = false
        try {
            made.prepare()
            made.start()
            started = true
        } catch (refused: IllegalStateException) {
            throw IOException("the microphone did not start", refused)
        } finally {
            if (!started) {
                made.release()
                audio.abandonAudioFocusRequest(request)
            }
        }
        recorder = made
        focus = request
    }

    override fun amplitude(): Int = recorder?.maxAmplitude ?: 0

    override fun pause() {
        recorder?.pause()
    }

    override fun resume() {
        recorder?.resume()
    }

    override fun stop(): Boolean {
        val made = recorder ?: return false
        recorder = null
        return try {
            made.stop()
            true
        } catch (expected: RuntimeException) {
            // MediaRecorder.stop throws a bare RuntimeException, by its contract, for a take that holds no audio.
            log("The recording kept no audio", expected)
            false
        } finally {
            made.release()
            focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
            focus = null
        }
    }

    private fun recorderInto(into: File): MediaRecorder =
        MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioChannels(1)
            setAudioSamplingRate(VOICE_SAMPLE_RATE)
            setAudioEncodingBitRate(VOICE_BITRATE)
            setOutputFile(into)
        }
}

/** Transient exclusive focus for speech, its changes heard on the main thread by [onChange]. */
private fun focusRequest(onChange: (Int) -> Unit): AudioFocusRequest {
    val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    return AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ onChange(it) }, Handler(Looper.getMainLooper()))
        .build()
}

/**
 * The phone's one player for voice notes (design section 13.5): MediaPlayer, one note at a time, made for each
 * play and released at [stop] or the next play. A note it cannot open throws, released, and plays nothing; a
 * note's length is its metadata's and its levels are decoded from its audio (noteLevels).
 */
class PhonePlayer : VoicePlayer {
    private var player: MediaPlayer? = null

    override fun play(
        file: File,
        fromMs: Long,
        speed: Float,
        onEnd: () -> Unit,
    ) {
        stop()
        val made = MediaPlayer()
        var opened = false
        try {
            made.setDataSource(file.path)
            made.prepare()
            opened = true
        } finally {
            if (!opened) made.release()
        }
        made.setOnCompletionListener { onEnd() }
        made.seekTo(fromMs, MediaPlayer.SEEK_CLOSEST)
        made.playbackParams = made.playbackParams.setSpeed(speed)
        made.start()
        player = made
    }

    override fun pause(): Long {
        val made = player ?: return 0L
        made.pause()
        return made.currentPosition.toLong()
    }

    override fun position(): Long = player?.currentPosition?.toLong() ?: 0L

    override fun stop() {
        player?.release()
        player = null
    }

    override fun read(file: File): NoteFile = NoteFile(durationOf(file), noteLevels(file))
}

/** [file]'s length from its metadata; throws an IOException when it says none. */
private fun durationOf(file: File): Long {
    val duration =
        MediaMetadataRetriever().use { retriever ->
            try {
                retriever.setDataSource(file.path)
            } catch (expected: RuntimeException) {
                // setDataSource throws IllegalArgumentException for a bad path and a bare RuntimeException for a
                // file its extractor cannot read.
                throw IOException("$file could not be read", expected)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    return duration ?: throw IOException("$file says no length")
}
