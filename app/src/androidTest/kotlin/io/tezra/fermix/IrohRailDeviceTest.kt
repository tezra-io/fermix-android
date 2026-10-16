package io.tezra.fermix

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.IrohRail
import io.tezra.fermix.transport.RouteDial
import io.tezra.fermix.transport.RouteFailure
import io.tezra.fermix.transport.RouteHalves
import io.tezra.fermix.transport.RouteStream
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** How long a refusal may take: it reads a frame's header alone, never waiting for a payload. */
private const val REFUSAL_MILLIS = 3_000L

/** How long a cancelled dial may take to return. */
private const val CANCEL_MILLIS = 1_500L

/** How long a test's end waits for what it started to end, before its threads are let go of. */
private const val END_MILLIS = 10_000L

/** A test's bound on what should take seconds, well past the rail's 10 s connect. */
private const val SLOW_MILLIS = 30_000L

/** How long an open on a connection with room for a stream may take; one past it waits for room. */
private const val ROOM_MILLIS = 1_000L

/** The most streams a test opens to fill a connection: past the stream credit a QUIC endpoint gives by default. */
private const val MAX_FILL = 256

private const val CLOSE_FRAME_TYPE: Byte = 2
private const val PROTOCOL_ERROR = 1002
private const val MESSAGE_TOO_BIG = 1009

/**
 * The iroh rail on a device (M62 stage 6, the brief's device rows), over two real iroh endpoints in this process: the
 * phone's, which the rail binds, and the test daemon's (IrohTestDaemon), reached over loopback. That the native library
 * loads and the endpoint binds, the connection kept between streams, frames refused from their header alone, a close
 * that comes after the daemon's, a dial cancelled while it connects and one that fails at every relay, each named; a
 * connection with no room for another stream kept through an open cancelled while it waits, and closed streams' room
 * given back for the next.
 */
@RunWith(AndroidJUnit4::class)
class IrohRailDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The test's own threads, which it ends last: the rails', the daemons' and its own coroutines run there. */
    private val threads = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + threads)
    private val daemons = mutableListOf<IrohTestDaemon>()
    private val rails = mutableListOf<IrohRail>()

    @After
    fun end() {
        runBlocking { rails.forEach { it.shutdown() } }
        daemons.forEach { it.close() }
        // What the test started ends before its threads do, bounded.
        runBlocking { withTimeout(END_MILLIS) { scope.coroutineContext.job.cancelAndJoin() } }
        threads.close()
    }

    private fun daemon(serve: suspend IrohTestDaemon.(RouteHalves) -> Unit) =
        IrohTestDaemon(context, threads, serve).also { daemons += it }

    private fun rail(plan: (Candidate.Route) -> List<RouteDial>) =
        IrohRail(context, knownRelays = { emptySet() }, scope, plan).also { rails += it }

    @Test
    fun the_native_library_loads_the_endpoint_binds_once_and_the_connection_is_kept_between_streams() =
        runBlocking {
            val daemon = daemon { halves -> echo(framed(halves)) }
            val rail = rail(daemon::plan)
            val first = rail.open(daemon.route())
            assertTrue(first.notes.toString(), first.notes.any { it.startsWith("the iroh endpoint is bound") })
            assertArrayEquals(HELLO, roundTrip(first))
            first.close()
            first.awaitReleased()

            val second = rail.open(daemon.route())
            assertTrue("one endpoint per process: ${second.notes}", second.notes.none { "bound" in it })
            assertArrayEquals(HELLO, roundTrip(second))
            second.close()
            assertEquals(1, daemon.connections.size)
            assertEquals(2, daemon.streams.get())
        }

    @Test
    fun a_65537_byte_frame_is_refused_from_its_header_alone_with_no_close_frame() =
        runBlocking {
            // The daemon never sends the 65,537 bytes its header names: a reader that waited for them would hang.
            val reset = CompletableDeferred<Boolean>()
            val daemon =
                daemon { halves ->
                    skipFrame(halves)
                    halves.writeAll(lengthOnly(MAX_LENGTH + 1))
                    reset.complete(runCatching { halves.readExact(Int.SIZE_BYTES) }.isFailure)
                }
            val stream = opened(rail(daemon::plan), daemon)
            val ended = withTimeout(REFUSAL_MILLIS) { stream.closed.await() }
            val close = ended as TransportException.Closed
            assertEquals(MESSAGE_TOO_BIG, close.code)
            assertTrue(close.message, "65537" in close.message.orEmpty())
            assertTrue("the stream ends with no close frame", withTimeout(SLOW_MILLIS) { reset.await() })
            stream.close()
        }

    @Test
    fun a_zero_length_frame_is_refused_from_its_header_and_closed_1002_with_a_close_frame() =
        runBlocking {
            val heard = CompletableDeferred<Int>()
            val daemon =
                daemon { halves ->
                    skipFrame(halves)
                    halves.writeAll(lengthOnly(0))
                    heard.complete(closeCodeOf(halves))
                }
            val stream = opened(rail(daemon::plan), daemon)
            val close = withTimeout(REFUSAL_MILLIS) { stream.closed.await() } as TransportException.Closed
            assertEquals(PROTOCOL_ERROR, close.code)
            assertEquals(PROTOCOL_ERROR, withTimeout(REFUSAL_MILLIS) { heard.await() })
            stream.close()
        }

    @Test
    fun an_open_cancelled_while_it_waits_for_room_keeps_the_connection_and_closed_streams_give_their_room_back() =
        runBlocking {
            val daemon = daemon { halves -> echo(framed(halves)) }
            val rail = rail(daemon::plan)
            // The streams fill the connection's room, and the open past them is cancelled while it waits for some, as
            // an hour-old session's stream may be on the connection when its renewal's open is cancelled.
            val streams = filled(rail, daemon)
            assertArrayEquals("the first stream is still carried", HELLO, roundTrip(streams.first()))
            assertEquals("streams ended", 0, streams.count { it.closed.isCompleted })

            // Their closes give their room back on the same connection. iroh 1.1.0 gives room back in batches: of a
            // hundred streams, one or ten closed gave none back within 20 s, sixty more than fifty; so all are closed.
            streams.forEach { it.close() }
            withTimeout(SLOW_MILLIS) { streams.forEach { it.awaitReleased() } }
            val next = withTimeout(SLOW_MILLIS) { rail.open(daemon.route()) }
            assertArrayEquals(HELLO, roundTrip(next))
            assertEquals(1, daemon.connections.size)
            next.close()
        }

    @Test
    fun a_late_close_after_the_daemons_own_ends_quietly_and_lets_go_of_the_stream() =
        runBlocking {
            val daemon = daemon { halves -> framed(halves).close(1000, "done") }
            val stream = opened(rail(daemon::plan), daemon)
            val ended = withTimeout(REFUSAL_MILLIS) { stream.closed.await() } as TransportException.Closed
            assertEquals(1000 to "done", ended.code to ended.reason)
            assertTrue(ended.byDaemon)
            delay(LATE_MILLIS)
            stream.close()
            withTimeout(SLOW_MILLIS) { stream.awaitReleased() }
        }

    @Test
    fun a_dial_cancelled_while_it_connects_returns_at_once_and_the_same_endpoint_still_dials() =
        runBlocking {
            val daemon = daemon { halves -> echo(framed(halves)) }
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
                // One rail, so one endpoint: its first dial goes to a socket that never answers, its second to the
                // daemon.
                var silenced = true
                val rail = rail { route -> if (silenced) silentPlan(daemon, silent, route) else daemon.plan(route) }
                val opening = async { rail.open(daemon.route()) }
                delay(CANCEL_MILLIS)
                val cancelledAt = SystemClock.elapsedRealtime()
                opening.cancel()
                opening.join()
                assertTrue(SystemClock.elapsedRealtime() - cancelledAt < CANCEL_MILLIS)
                silenced = false
                val stream = rail.open(daemon.route())
                assertTrue(
                    "the endpoint the cancelled dial bound: ${stream.notes}",
                    stream.notes.none { "bound" in it },
                )
                assertArrayEquals(HELLO, roundTrip(stream))
                stream.close()
            }
        }

    @Test
    fun production_dials_a_route_through_its_relays_alone_never_an_address_of_its_own() =
        runBlocking {
            // The rail as the app makes it, with no plan of the test's: the daemon is on loopback, and its route's
            // relays answer nothing, so a dial that tried any address but the relays' would reach it.
            val daemon = daemon { halves -> echo(framed(halves)) }
            val rail = IrohRail(context, knownRelays = { emptySet() }, scope).also { rails += it }
            val failure =
                withTimeout(SLOW_MILLIS) { runCatching { rail.open(daemon.route(relays = 2)) }.exceptionOrNull() }
            val cause = (failure as TransportException.Unreachable).cause as RouteFailure
            assertTrue(cause.message, "relay-a.invalid" in cause.message.orEmpty())
            assertTrue(cause.message, "relay-b.invalid" in cause.message.orEmpty())
            assertEquals(0, daemon.connections.size)
        }

    @Test
    fun a_route_that_fails_at_every_relay_names_each_relay_and_why() =
        runBlocking {
            val daemon = daemon { halves -> echo(framed(halves)) }
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
                val rail = rail { route -> silentPlan(daemon, silent, route) }
                val failure =
                    withTimeout(SLOW_MILLIS) { runCatching { rail.open(daemon.route(relays = 2)) }.exceptionOrNull() }
                val cause = (failure as TransportException.Unreachable).cause as RouteFailure
                assertTrue(cause.message, "relay-a.invalid: timed out" in cause.message.orEmpty())
                assertTrue(cause.message, "relay-b.invalid: timed out" in cause.message.orEmpty())
            }
        }
}

/** A message the daemon echoes. */
private val HELLO = "hello over iroh".encodeToByteArray()

private const val MAX_LENGTH = 65_536

/** How long after the daemon's close the phone closes its own. */
private const val LATE_MILLIS = 500L

private suspend fun roundTrip(stream: RouteStream): ByteArray {
    assertTrue(stream.send(HELLO))
    return withTimeout(SLOW_MILLIS) { stream.incoming.receive() }
}

private suspend fun echo(stream: RouteStream) {
    for (message in stream.incoming) stream.send(message)
    stream.close()
}

/**
 * A stream to [daemon] with one message sent on it: QUIC tells a peer of a stream with its first bytes, as a session's
 * prelude does, so the daemon takes a stream only once the phone has written to it.
 */
private suspend fun opened(
    rail: IrohRail,
    daemon: IrohTestDaemon,
): RouteStream = rail.open(daemon.route()).also { assertTrue(it.send(HELLO)) }

/**
 * Streams to [daemon] on one connection until it has no room for another: the open past them waits for room, and
 * is cancelled [ROOM_MILLIS] in. A stream the phone has not written to yet holds its room all the same.
 */
private suspend fun filled(
    rail: IrohRail,
    daemon: IrohTestDaemon,
): List<RouteStream> {
    val streams = mutableListOf(withTimeout(SLOW_MILLIS) { rail.open(daemon.route()) })
    var next = withTimeoutOrNull(ROOM_MILLIS) { rail.open(daemon.route()) }
    while (next != null && streams.size < MAX_FILL) {
        streams += next
        next = withTimeoutOrNull(ROOM_MILLIS) { rail.open(daemon.route()) }
    }
    check(next == null) { "the connection still had room after $MAX_FILL streams" }
    assertEquals(1, daemon.connections.size)
    return streams
}

/** Reads past the phone's first frame, the one that made the stream known to the daemon. */
private suspend fun skipFrame(halves: RouteHalves) {
    val length = ByteBuffer.wrap(halves.readExact(Int.SIZE_BYTES)).int
    halves.readExact(length)
}

/** A frame's 4-byte length and nothing after it. */
private fun lengthOnly(length: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(length).array()

/** The code of the close frame the phone sends next. */
private suspend fun closeCodeOf(halves: RouteHalves): Int {
    val length = ByteBuffer.wrap(halves.readExact(Int.SIZE_BYTES)).int
    val frame = ByteBuffer.wrap(halves.readExact(length))
    check(frame.get() == CLOSE_FRAME_TYPE) { "the phone's next frame is not a close" }
    return frame.short.toInt()
}

/** Each relay's dial to a loopback socket that never answers: a connect that runs to its bound. */
private fun silentPlan(
    daemon: IrohTestDaemon,
    silent: DatagramSocket,
    route: Candidate.Route,
): List<RouteDial> = daemon.plan(route).map { it.copy(addresses = listOf("127.0.0.1:${silent.localPort}")) }
