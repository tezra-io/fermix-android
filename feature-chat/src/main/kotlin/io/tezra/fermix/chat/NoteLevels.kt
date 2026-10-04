package io.tezra.fermix.chat

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.abs

/** How long the decoder is waited on for one buffer, in and out. */
private const val DECODE_WAIT_US = 10_000L

/** One Opus frame, the shortest a buffer of a note decodes to. */
private const val OPUS_FRAME_MS = 20L

/** Decoder steps one note takes at most: each of a ten-minute note's frames in and out, four times over. */
private const val MAX_DECODE_STEPS = (4 * MAX_VOICE_MS / OPUS_FRAME_MS).toInt()

private const val MILLIS_PER_SECOND = 1_000

/**
 * [file]'s audio decoded to its level every [SAMPLE_MS], as the recording row samples it (levelOf of the loudest
 * sample): the bars of a note this process did not record, or recorded before it started (design section 13.5,
 * the 40-bar waveform). MediaExtractor and MediaCodec, both released however it ends; throws an IOException when
 * the file holds no audio the phone decodes.
 */
fun noteLevels(file: File): List<Float> {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        val track =
            (0 until extractor.trackCount).firstOrNull { audio(extractor.getTrackFormat(it)) }
                ?: throw IOException("$file holds no audio")
        extractor.selectTrack(track)
        return decoded(extractor, extractor.getTrackFormat(track))
    } finally {
        extractor.release()
    }
}

private fun audio(format: MediaFormat): Boolean = format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true

/** [extractor]'s selected track, of [format], through a decoder made for it and released however it ends. */
private fun decoded(
    extractor: MediaExtractor,
    format: MediaFormat,
): List<Float> {
    val codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
    try {
        codec.configure(format, null, null, 0)
        codec.start()
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val perSecond = format.getInteger(MediaFormat.KEY_SAMPLE_RATE) * channels
        return pump(extractor, codec, LevelReader(perSecond * SAMPLE_MS.toInt() / MILLIS_PER_SECOND))
    } catch (expected: IllegalStateException) {
        // MediaCodec reports a stream it cannot decode as a CodecException, an IllegalStateException.
        throw IOException("the note could not be decoded", expected)
    } finally {
        codec.release()
    }
}

/** Feeds [codec] and reads it until its output ends, at most [MAX_DECODE_STEPS] steps, past which it throws. */
private fun pump(
    extractor: MediaExtractor,
    codec: MediaCodec,
    reader: LevelReader,
): List<Float> {
    val info = MediaCodec.BufferInfo()
    var fed = false
    repeat(MAX_DECODE_STEPS) {
        if (!fed) fed = feed(extractor, codec)
        if (drained(codec, info, reader)) return reader.levels()
    }
    throw IOException("the note did not decode within $MAX_DECODE_STEPS steps")
}

/** One buffer of [extractor]'s samples into [codec], or the end of them: whether the input has ended. */
private fun feed(
    extractor: MediaExtractor,
    codec: MediaCodec,
): Boolean {
    val index = codec.dequeueInputBuffer(DECODE_WAIT_US)
    if (index < 0) return false
    val size = extractor.readSampleData(checkNotNull(codec.getInputBuffer(index)), 0)
    val ended = size < 0
    if (ended) {
        codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    } else {
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
    }
    return ended
}

/** One buffer of [codec]'s 16-bit PCM into [reader], if one is ready: whether it was the last. */
private fun drained(
    codec: MediaCodec,
    info: MediaCodec.BufferInfo,
    reader: LevelReader,
): Boolean {
    val index = codec.dequeueOutputBuffer(info, DECODE_WAIT_US)
    if (index < 0) return false
    val pcm = checkNotNull(codec.getOutputBuffer(index))
    reader.add(pcm.order(ByteOrder.nativeOrder()).asShortBuffer())
    codec.releaseOutputBuffer(index, false)
    return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
}

/** Samples in, the loudest of each [window] of them out as one level. */
private class LevelReader(
    private val window: Int,
) {
    private val levels = ArrayList<Float>()
    private var loudest = 0
    private var counted = 0

    fun add(pcm: ShortBuffer) {
        repeat(pcm.remaining()) {
            loudest = maxOf(loudest, abs(pcm.get().toInt()))
            counted++
            if (counted == window) close()
        }
    }

    fun levels(): List<Float> {
        if (counted > 0) close()
        return levels
    }

    private fun close() {
        levels += levelOf(loudest)
        loudest = 0
        counted = 0
    }
}
