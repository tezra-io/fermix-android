package io.tezra.fermix

import android.content.Context
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.Incoming
import computer.iroh.IrohException
import computer.iroh.RelayMode
import computer.iroh.presetMinimal
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.IrohContext
import io.tezra.fermix.transport.MOBILE_ALPN
import io.tezra.fermix.transport.RouteDial
import io.tezra.fermix.transport.RouteHalves
import io.tezra.fermix.transport.RouteStream
import io.tezra.fermix.transport.relayDials
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The revision the test routes name. */
internal const val TEST_RELAYS_REV = "0123456789abcdef"

/** Relays no network answers: the phone reaches the test daemon over its loopback address alone. */
private val TEST_RELAYS = listOf("relay-a.invalid", "relay-b.invalid")
private const val TEST_QUIC_PORT = 7842

/**
 * The daemon's side of the iroh rail in a device test: a real iroh endpoint in this process, bound with the minimal
 * preset, no relay and the phone ALPN, as fermix-iroh's is, which takes each connection and each stream on it and
 * hands the stream's halves to [serve], in the daemon's [scope]: framed as a [RouteStream] by [framed], or raw for a
 * test that writes what no daemon would. The phone reaches it by its node id over the loopback address, which
 * [plan] adds to each relay's dial; the relays themselves answer nothing. It counts the [connections] it took and the
 * [streams] they opened. Its coroutines run on the test's [threads].
 */
internal class IrohTestDaemon(
    context: Context,
    threads: CoroutineDispatcher,
    private val serve: suspend IrohTestDaemon.(RouteHalves) -> Unit,
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + threads)
    private val endpoint: Endpoint = runBlocking { bindDaemon(context) }
    val connections = CopyOnWriteArrayList<Connection>()
    val streams = AtomicInteger()
    private val accepting = scope.launch { acceptAll() }

    /** The daemon's node id, as its route names it. */
    val nodeId: String = endpoint.id().use { it.toBytes().toHexString() }

    /** Where the phone's endpoint finds this one: its IPv4 socket's port on loopback. */
    private val addresses: List<String> =
        endpoint.boundSockets().filterNot { it.startsWith("[") }.map { "127.0.0.1:" + it.substringAfterLast(':') }

    /** The daemon's route, over [relays] of the test's relays. */
    fun route(relays: Int = 1): Candidate.Route =
        Candidate.Route(nodeId, TEST_RELAYS.take(relays).map { Candidate.Relay(it, TEST_QUIC_PORT) }, TEST_RELAYS_REV)

    /** The rail's dials of [route]: each relay's, with this endpoint's loopback address beside it. */
    fun plan(route: Candidate.Route): List<RouteDial> = relayDials(route).map { it.copy(addresses = addresses) }

    /** [halves] as the daemon's end of a session's stream. */
    fun framed(halves: RouteHalves): RouteStream = RouteStream(halves, scope)

    /** The link's route parameters for [route], form-encoded. */
    fun linkParameters(route: Candidate.Route): String =
        "&iroh=${route.nodeId}&relays=" + route.relays.joinToString("%2C") { "${it.host}%3A${it.quicPort}" } +
            "&relays_rev=${route.revision}"

    override fun close() =
        runBlocking {
            accepting.cancelAndJoin()
            connections.forEach { it.close(0L, "test over".encodeToByteArray()) }
            scope.coroutineContext[Job]?.cancelAndJoin()
            endpoint.shutdown()
            endpoint.destroy()
        }

    private suspend fun acceptAll() {
        var incoming: Incoming? = endpoint.acceptNext()
        while (incoming != null) {
            val taken = incoming
            scope.launch { serveConnection(taken) }
            incoming = endpoint.acceptNext()
        }
    }

    private suspend fun serveConnection(incoming: Incoming) {
        val connection = incoming.accept().connect()
        connections += connection
        var stream = nextStream(connection)
        while (stream != null) {
            streams.incrementAndGet()
            val taken = stream
            scope.launch { serve(DaemonHalves(taken)) }
            stream = nextStream(connection)
        }
    }
}

private suspend fun bindDaemon(context: Context): Endpoint {
    // As every endpoint of the process, after Android's context is in the library.
    IrohContext.install(context)
    val options =
        EndpointOptions(
            preset = presetMinimal(),
            alpns = listOf(MOBILE_ALPN.encodeToByteArray()),
            relayMode = RelayMode.disabled(),
        )
    return try {
        Endpoint.bind(options)
    } finally {
        options.destroy()
    }
}

/** The connection's next stream from the phone, or none once it has closed. */
private suspend fun nextStream(connection: Connection): BiStream? =
    try {
        connection.acceptBi()
    } catch (expectedClosed: IrohException) {
        // The phone closed the connection, or the test ended it.
        null
    }

/** One stream's halves on the daemon's side, as the rail's own are on the phone's. */
private class DaemonHalves(
    private val stream: BiStream,
) : RouteHalves {
    private val sending = stream.send()
    private val receiving = stream.recv()

    override suspend fun readExact(size: Int): ByteArray = receiving.readExact(size.toUInt())

    override suspend fun writeAll(bytes: ByteArray) = sending.writeAll(bytes)

    override suspend fun finish() = sending.finish()

    override suspend fun stopped(): Boolean = sending.stopped() != null

    override suspend fun reset(): Boolean = ended { sending.reset(0uL) }

    override suspend fun stopReading(): Boolean = ended { receiving.stop(0uL) }

    override fun release() {
        sending.destroy()
        receiving.destroy()
        stream.destroy()
    }
}

private suspend fun ended(call: suspend () -> Unit): Boolean =
    try {
        call()
        true
    } catch (expectedEnded: IrohException) {
        // The half had ended already.
        false
    }
