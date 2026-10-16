package io.tezra.fermix.session

import io.tezra.fermix.transport.RouteHalves
import kotlinx.coroutines.channels.Channel
import java.io.IOException

/**
 * Both ends of one remote stream in memory: what one end writes the other reads, a finish or a reset ends the
 * other's reads, and each end records that it was let go. Each end is a [RouteStream]'s halves.
 */
internal class MemoryRoute {
    private val toDaemon = Channel<ByteArray>(Channel.UNLIMITED)
    private val toPhone = Channel<ByteArray>(Channel.UNLIMITED)

    val phone = End(toPhone, toDaemon)
    val daemon = End(toDaemon, toPhone)

    class End(
        private val reading: Channel<ByteArray>,
        private val writing: Channel<ByteArray>,
    ) : RouteHalves {
        private var pending = ByteArray(0)

        @Volatile var released = false
            private set

        override suspend fun readExact(size: Int): ByteArray {
            while (pending.size < size) {
                val next = reading.receiveCatching().getOrNull() ?: throw IOException("the far end finished the stream")
                pending += next
            }
            return pending.copyOfRange(0, size).also { pending = pending.copyOfRange(size, pending.size) }
        }

        override suspend fun writeAll(bytes: ByteArray) {
            writing.send(bytes)
        }

        override suspend fun finish() {
            writing.close()
        }

        override suspend fun stopped(): Boolean = false

        override suspend fun reset(): Boolean = writing.close()

        override suspend fun stopReading(): Boolean = true

        override fun release() {
            released = true
        }
    }
}
