package io.tezra.fermix.transport

import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A stream's two halves in memory, as a test's daemon holds the far end: [feed] puts bytes on the phone's
 * receiving half, a [Ended] marker ends it, and what the phone writes arrives in [written], ended by [Finished]
 * or [Reset]. The far end takes everything the phone sends, or, with [stopsSending], stops its sending half, as the
 * helper does once the daemon has closed. Each half records how it was let go.
 */
internal class PipeHalves(
    private val stopsSending: Boolean = false,
) : RouteHalves {
    /** What ends the receiving half: the far end finished or failed. */
    object Ended

    /** The phone's sending half was finished after everything it wrote. */
    object Finished

    /** The phone's sending half was reset. */
    object Reset

    private val inbound = Channel<Any>(Channel.UNLIMITED)
    private var pending = ByteArray(0)

    /** Each frame the phone wrote, then [Finished] or [Reset]. */
    val written = Channel<Any>(Channel.UNLIMITED)

    @Volatile var stopped = false
        private set

    @Volatile var released = 0
        private set

    /** The sizes of every read the carrier asked for, in order. */
    val reads = mutableListOf<Int>()

    fun feed(bytes: ByteArray) {
        inbound.trySend(bytes).getOrThrow()
    }

    fun end() {
        inbound.trySend(Ended).getOrThrow()
    }

    override suspend fun readExact(size: Int): ByteArray {
        synchronized(reads) { reads += size }
        while (pending.size < size) {
            val next = inbound.receive()
            if (next === Ended) throw IOException("the far end finished the stream")
            pending += next as ByteArray
        }
        return pending.copyOfRange(0, size).also { pending = pending.copyOfRange(size, pending.size) }
    }

    override suspend fun writeAll(bytes: ByteArray) {
        written.send(bytes)
    }

    override suspend fun finish() {
        written.send(Finished)
    }

    override suspend fun stopped(): Boolean = stopsSending

    override suspend fun reset(): Boolean = written.trySend(Reset).isSuccess

    override suspend fun stopReading(): Boolean {
        stopped = true
        return true
    }

    override fun release() {
        released++
    }
}

/** A frame as the daemon writes one: `uint32be length | type | payload`, the length counting the type byte. */
internal fun frame(
    type: Int,
    payload: ByteArray,
): ByteArray =
    ByteBuffer
        .allocate(4 + 1 + payload.size)
        .putInt(1 + payload.size)
        .put(type.toByte())
        .put(payload)
        .array()

/** A close frame's payload: the code as a uint16be, then the reason's bytes. */
internal fun closePayload(
    code: Int,
    reason: ByteArray,
): ByteArray =
    ByteBuffer
        .allocate(2 + reason.size)
        .putShort(code.toShort())
        .put(reason)
        .array()

/** Just a length field, for a frame whose length alone is refused. */
internal fun lengthOnly(length: Long): ByteArray = ByteBuffer.allocate(4).putInt(length.toInt()).array()
