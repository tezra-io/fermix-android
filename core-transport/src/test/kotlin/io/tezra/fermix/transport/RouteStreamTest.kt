package io.tezra.fermix.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The remote stream's carrier (PROTOCOL.md "Remote reach"): one Noise message per type-1 frame, a type-2 close
 * with its code and reason, the length read and judged before anything past it is, and each way it ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteStreamTest {
    private fun TestScope.stream(pipe: PipeHalves): RouteStream =
        RouteStream(
            pipe,
            CoroutineScope(coroutineContext + kotlinx.coroutines.Job()),
        )

    @Test
    fun `each message goes out as one type-1 frame, and queued bytes count what the writer has not taken`() =
        runTest {
            val pipe = PipeHalves()
            val route = stream(pipe)
            assertTrue(route.send(byteArrayOf(7, 8, 9)))
            assertEquals(8L, route.queuedBytes)
            runCurrent()
            assertArrayEquals(frame(1, byteArrayOf(7, 8, 9)), pipe.written.receive() as ByteArray)
            assertEquals(0L, route.queuedBytes)
            route.close(1000, "")
            advanceUntilIdle()
        }

    @Test
    fun `a message is 1 to 65,535 bytes`() =
        runTest {
            val route = stream(PipeHalves())
            assertThrows<IllegalArgumentException> { route.send(ByteArray(0)) }
            assertThrows<IllegalArgumentException> { route.send(ByteArray(MAX_MESSAGE_BYTES + 1)) }
            assertTrue(route.send(ByteArray(MAX_MESSAGE_BYTES)))
            route.close(1000, "")
            advanceUntilIdle()
        }

    @Test
    fun `each type-1 frame the daemon sends is one message, in order`() =
        runTest {
            val pipe = PipeHalves()
            val route = stream(pipe)
            pipe.feed(frame(1, byteArrayOf(1)) + frame(1, ByteArray(MAX_MESSAGE_BYTES) { 2 }))
            assertArrayEquals(byteArrayOf(1), route.incoming.receive())
            assertEquals(MAX_MESSAGE_BYTES, route.incoming.receive().size)
            route.close(1000, "")
            advanceUntilIdle()
        }

    @Test
    fun `the daemon's close frame ends the stream with its code and reason, after the messages before it`() =
        runTest {
            val pipe = PipeHalves()
            val route = stream(pipe)
            pipe.feed(
                frame(1, byteArrayOf(5)) +
                    frame(2, closePayload(1000, "Noise session lifetime reached".encodeToByteArray())),
            )
            val closed = route.closed.await() as TransportException.Closed
            assertEquals(1000, closed.code)
            assertEquals("Noise session lifetime reached", closed.reason)
            assertTrue(closed.byDaemon)
            assertArrayEquals(byteArrayOf(5), route.incoming.receive())
            assertThrows<ClosedReceiveChannelException> { route.incoming.receive() }
            assertFalse(route.send(byteArrayOf(1)))
            route.close(1000, "")
            advanceUntilIdle()
            // The daemon closed first: no close frame of this side's, only the finish.
            assertSame(PipeHalves.Finished, pipe.written.receive())
            assertEquals(1, pipe.released)
        }

    @Test
    fun `a length past 65,536 ends the stream with no close frame before anything past the length is read`() =
        runTest {
            val pipe = PipeHalves()
            val route = stream(pipe)
            pipe.feed(lengthOnly(65_537))
            val closed = route.closed.await() as TransportException.Closed
            assertFalse(closed.byDaemon)
            advanceUntilIdle()
            assertEquals(listOf(4), pipe.reads)
            assertSame(PipeHalves.Reset, pipe.written.receive())
            assertTrue(pipe.stopped)
            assertEquals(1, pipe.released)
            route.close(1000, "")
        }

    @Test
    fun `a length of 0 or 1 is closed 1002 before anything past the length is read`() =
        runTest {
            listOf(0L, 1L).forEach { length ->
                val pipe = PipeHalves()
                val route = stream(pipe)
                pipe.feed(lengthOnly(length))
                val closed = route.closed.await() as TransportException.Closed
                assertEquals(1002, closed.code)
                assertEquals("mobile protocol error", closed.reason)
                advanceUntilIdle()
                assertEquals(listOf(4), pipe.reads, "length $length")
                assertArrayEquals(
                    frame(2, closePayload(1002, "mobile protocol error".encodeToByteArray())),
                    pipe.written.receive() as ByteArray,
                )
                assertSame(PipeHalves.Finished, pipe.written.receive())
                route.close(1000, "")
            }
        }

    @Test
    fun `another type, a short close and a close reason not UTF-8 or past 123 bytes are closed 1002`() =
        runTest {
            val cases =
                listOf(
                    frame(3, byteArrayOf(1)),
                    frame(0, byteArrayOf(1)),
                    frame(2, byteArrayOf(3)),
                    frame(2, closePayload(1000, byteArrayOf(0xC3.toByte()))),
                    frame(2, closePayload(1000, ByteArray(124) { 'a'.code.toByte() })),
                )
            cases.forEach { bad ->
                val pipe = PipeHalves()
                val route = stream(pipe)
                pipe.feed(bad)
                val closed = route.closed.await() as TransportException.Closed
                assertEquals(1002, closed.code, bad.contentToString())
                assertFalse(closed.byDaemon)
                route.close(1000, "")
                advanceUntilIdle()
            }
        }

    @Test
    fun `a close reason of 123 bytes is the daemon's`() =
        runTest {
            val pipe = PipeHalves()
            val route = stream(pipe)
            pipe.feed(frame(2, closePayload(4003, ByteArray(123) { 'r'.code.toByte() })))
            val closed = route.closed.await() as TransportException.Closed
            assertEquals(4003, closed.code)
            assertEquals(123, closed.reason.length)
            route.close(1000, "")
            advanceUntilIdle()
        }

    @Test
    fun `this side's close sends its frame after what is queued, finishes sending and stops receiving`() =
        runTest {
            val pipe = PipeHalves()
            val route = stream(pipe)
            route.send(byteArrayOf(4))
            route.close(1000, "bye")
            advanceUntilIdle()
            assertArrayEquals(frame(1, byteArrayOf(4)), pipe.written.receive() as ByteArray)
            assertArrayEquals(
                frame(2, closePayload(1000, "bye".encodeToByteArray())),
                pipe.written.receive() as ByteArray,
            )
            assertSame(PipeHalves.Finished, pipe.written.receive())
            // The daemon took the close and the finish: nothing to reset.
            assertTrue(pipe.written.isEmpty)
            assertTrue(pipe.stopped)
            assertEquals(1, pipe.released)
            val closed = route.closed.await() as TransportException.Closed
            assertFalse(closed.byDaemon)
            assertFalse(route.send(byteArrayOf(1)))
        }

    @Test
    fun `a sending half the daemon stopped is reset after its finish, so the stream frees its slot`() =
        runTest {
            // The helper stops the phone's sending half once the daemon has closed, and a finish then holds one of
            // the connection's four stream slots until the half is reset (ENGINE_RAIL section 13, helper B7).
            val pipe = PipeHalves(stopsSending = true)
            val route = stream(pipe)
            pipe.feed(frame(2, closePayload(1000, "Noise session lifetime reached".encodeToByteArray())))
            route.closed.await()
            route.close(1000, "")
            advanceUntilIdle()
            assertSame(PipeHalves.Finished, pipe.written.receive())
            assertSame(PipeHalves.Reset, pipe.written.receive())
            assertEquals(1, pipe.released)
        }

    @Test
    fun `a close the far end never takes is reset after the close wait`() =
        runTest {
            val pipe = StuckHalves()
            val route = RouteStream(pipe, CoroutineScope(coroutineContext + kotlinx.coroutines.Job()))
            route.send(byteArrayOf(1))
            route.close(1000, "")
            advanceTimeBy(ROUTE_CLOSE_WAIT_MS - 1)
            runCurrent()
            assertFalse(pipe.wasReset)
            advanceTimeBy(2)
            runCurrent()
            route.awaitReleased()
            assertTrue(pipe.wasReset)
            assertTrue(pipe.wasReleased)
        }

    @Test
    fun `a receiving half that ends with no close frame ends the stream as unreachable`() =
        runTest {
            val pipe = PipeHalves()
            val route = stream(pipe)
            pipe.end()
            assertTrue(route.closed.await() is TransportException.Unreachable)
            route.close(1000, "")
            advanceUntilIdle()
            assertEquals(1, pipe.released)
        }

    /** Halves whose writes never complete, as a far end that stopped reading holds them. */
    private class StuckHalves : RouteHalves {
        @Volatile var wasReset = false

        @Volatile var wasReleased = false

        override suspend fun readExact(size: Int): ByteArray = kotlinx.coroutines.awaitCancellation()

        override suspend fun writeAll(bytes: ByteArray): Unit = kotlinx.coroutines.awaitCancellation()

        override suspend fun finish(): Unit = kotlinx.coroutines.awaitCancellation()

        override suspend fun stopped(): Boolean = kotlinx.coroutines.awaitCancellation()

        override suspend fun reset(): Boolean {
            wasReset = true
            return true
        }

        override suspend fun stopReading(): Boolean = true

        override fun release() {
            wasReleased = true
        }
    }
}
