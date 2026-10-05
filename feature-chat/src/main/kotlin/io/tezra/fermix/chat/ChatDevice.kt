package io.tezra.fermix.chat

import java.io.File

/**
 * What the phone makes of a picked item (design section 8.5, "Images"): the phone's ContentResolver,
 * ImageDecoder and bitmap compression for the app (PhoneMedia), a fake for the JVM tests.
 */
interface MediaPipeline {
    /**
     * What [uri] names, picked from [from]: its kind, type, name and size; none when it cannot be read. A URI the chat
     * may not read as it lands from [from] (mayRead) is a SecurityException. Another app's provider that fails throws
     * what the binder carries back from it, an IllegalArgumentException or an UnsupportedOperationException among them.
     * It returns as soon as its caller is cancelled, whatever the provider does.
     */
    suspend fun describe(
        uri: String,
        from: PickedFrom,
    ): Picked?

    /**
     * [picked]'s bytes as they go up, written to [into]: an image as a JPEG at most LONG_EDGE_PX on its long
     * edge, its EXIF and GPS left behind, unless [asFile], which, like anything not an image, sends its own
     * bytes. Throws an IOException when the item cannot be read, and a SecurityException when the chat may not read
     * it as the tray holds it (mayRead).
     */
    suspend fun prepare(
        picked: Picked,
        asFile: Boolean,
        into: File,
    ): Prepared

    /**
     * [picked]'s own bytes written to [into] as it lands, at most [maxBytes] and one byte more, which tells an item
     * past them, however much its provider would hand over: how many it wrote. [maxBytes] is a landing's, at most
     * LANDING_MAX_BYTES. Throws as [prepare] does. It returns as soon as its caller is cancelled, its stream closed, so
     * the copy stops.
     */
    suspend fun copyAtMost(
        picked: Picked,
        into: File,
        maxBytes: Long,
    ): Long

    /** The colour an image's first chunk of [bytes] decodes to on average, as an ARGB int; none when it does not. */
    fun dominantColour(bytes: ByteArray): Int?
}

/** A picked item made ready to go: its type and name; its bytes are in the file it was made in. */
data class Prepared(
    val mime: String,
    val name: String?,
)

/** The primary clip, which Paste takes an image or a file from: the phone's clipboard for the app, a fake for tests. */
fun interface ChatClip {
    /** Another app's content URI, the clip's first item; none when the clipboard holds no such image or file. */
    fun media(): String?
}

/**
 * The microphone a voice note is recorded with (design section 8.5, D15): MediaRecorder writing OGG/Opus for the
 * app (PhoneRecorder), a fake for the tests. Whoever starts it hears through [onInterrupted] that the recording
 * was cut by the phone: audio focus lost, which an incoming call takes too.
 */
interface VoiceRecorder {
    /**
     * Starts recording into [into]; [onInterrupted] is called, on the main thread, if the phone cuts it. Throws an
     * IOException when the microphone cannot record.
     */
    fun start(
        into: File,
        onInterrupted: () -> Unit,
    )

    /** The loudest sample since the last ask, 0 to 32767. */
    fun amplitude(): Int

    fun pause()

    fun resume()

    /** Stops and lets the microphone go: whether the file holds a recording to play or send. */
    fun stop(): Boolean
}

/**
 * What a voice note's file holds: how long it plays, and its level every SAMPLE_MS, 0 to 1 as levelOf reads a
 * sample, from which its bubble draws its 40 bars (barsOf).
 */
data class NoteFile(
    val durationMs: Long,
    val levels: List<Float>,
)

/**
 * The one player a voice note plays through (design section 13.5, "Voice notes"): MediaPlayer for the app
 * (PhonePlayer), a fake for the tests.
 */
interface VoicePlayer {
    /**
     * Plays [file] from [fromMs] at [speed]; [onEnd] is called on the main thread when it played to its end. Throws
     * an IOException when [file] cannot be opened, and then nothing plays.
     */
    fun play(
        file: File,
        fromMs: Long,
        speed: Float,
        onEnd: () -> Unit,
    )

    /** Pauses: the position it stopped at. */
    fun pause(): Long

    /** The position of the note playing, 0 when none plays. */
    fun position(): Long

    /** Lets the note go, and with it what the player holds. */
    fun stop()

    /** How long [file] plays and its levels, read off the main thread; throws an IOException when it cannot be. */
    fun read(file: File): NoteFile
}
