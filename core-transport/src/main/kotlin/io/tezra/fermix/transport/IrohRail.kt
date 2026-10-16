package io.tezra.fermix.transport

import android.content.Context
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.IrohAndroid
import computer.iroh.IrohException
import computer.iroh.RelayConfig
import computer.iroh.RelayMap
import computer.iroh.RelayMode
import computer.iroh.presetMinimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's context in iroh's native library, which every endpoint of the process needs before it binds (its DNS and
 * TLS read the platform through it). The library keeps it in one process-wide slot that aborts the process when it
 * is set twice (ndk-context), so it is installed once per process here, whichever endpoint binds first, and not per
 * rail: the one shared fact this module keeps, as the native library does.
 */
object IrohContext {
    private var installed = false

    /** Installs [context]'s application context, once per process; a call after the first does nothing. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        IrohAndroid.installAndroidContext(context.applicationContext)
        installed = true
    }
}

/** The QUIC application close of a connection the rail lets go of. */
private const val RAIL_CLOSE_CODE = 0L

/**
 * The phone's one iroh endpoint for the process, and the connections it keeps, opening a session's stream on a
 * daemon's route (PROTOCOL.md "Remote reach", M62 stage 6). The endpoint is bound on first need, once Android's
 * context is installed, with the minimal preset, never n0's, and a custom relay map of every relay the stored
 * routes name ([knownRelays]), each with its QUIC port; a relay a later route names is inserted before its dial. It
 * accepts nothing, as it names no ALPN, and binds no address of its own. Its key is iroh's to make and to hold in
 * memory: never passed in, read, logged or persisted, and its endpoint id is logged nowhere. A bind that fails is
 * that dial's failure, with what failed in its [RouteFailure], never a crash; nothing here retries, and the next
 * dial binds again. A connection is kept per daemon, and the next session's stream opens on it; one that has
 * closed, the daemon's 30 s idle close among them, is dialled again, and a cancelled open leaves it kept with the
 * streams on it. Opens to one daemon take turns, so two at once share one connection rather than one closing the
 * other's. Streams run in [scope]. [dialPlan] is how a route is dialled: [relayDials] but in a device test, which
 * adds its endpoint's direct addresses.
 */
class IrohRail(
    private val context: Context,
    private val knownRelays: suspend () -> Set<Candidate.Relay>,
    private val scope: CoroutineScope,
    private val dialPlan: (Candidate.Route) -> List<RouteDial> = ::relayDials,
) : RouteOpener {
    private val lock = Mutex()
    private var endpoint: Endpoint? = null
    private val relays = HashSet<String>()
    private val connections = ConcurrentHashMap<String, Connection>()

    /** Each daemon's turn to open a stream, by node id: one per paired daemon at most. */
    private val turns = HashMap<String, Mutex>()

    override suspend fun open(route: Candidate.Route): RouteStream {
        val notes = ArrayList<String>()
        val bound = lock.withLock { endpointFor(route, notes) }
        val turn = lock.withLock { turns.getOrPut(route.nodeId) { Mutex() } }
        val stream = turn.withLock { streamTo(bound, route, notes) }
        return RouteStream(IrohHalves(stream), scope, notes)
    }

    /** Lets go of every kept connection and the endpoint, once the streams on them have ended. */
    suspend fun shutdown() {
        lock.withLock {
            connections.values.forEach(::letGo)
            connections.clear()
            turns.clear()
            endpoint?.let { bound ->
                bound.shutdown()
                bound.destroy()
            }
            endpoint = null
        }
    }

    /** The endpoint, bound on first need, with each of [route]'s relays in its map. Under [lock]. */
    private suspend fun endpointFor(
        route: Candidate.Route,
        notes: MutableList<String>,
    ): Endpoint {
        val bound = endpoint ?: bind(route, notes).also { endpoint = it }
        route.relays.filter { it.url !in relays }.forEach { relay ->
            bound.insertRelay(RelayConfig(relay.url, relay.quicPort.toUShort(), null))
            relays += relay.url
        }
        return bound
    }

    private suspend fun bind(
        route: Candidate.Route,
        notes: MutableList<String>,
    ): Endpoint {
        val known = knownRelays() + route.relays
        val bound =
            try {
                IrohContext.install(context)
                bindWith(known)
            } catch (expectedFailure: Exception) {
                // A cancel goes on as it came; any other failure is this dial's, never a crash.
                throw (expectedFailure as? CancellationException) ?: unbound(expectedFailure)
            } catch (expectedMissing: LinkageError) {
                // No native library for this ABI or this JVM: a failed dial, not a crash.
                throw unbound(expectedMissing)
            }
        relays += known.map { it.url }
        notes += "the iroh endpoint is bound, with ${known.size} relays"
        return bound
    }

    /**
     * A stream to [route]'s daemon, on its kept connection when that still opens one, or on a new one, which is
     * kept in its place. In [route]'s turn.
     */
    private suspend fun streamTo(
        endpoint: Endpoint,
        route: Candidate.Route,
        notes: MutableList<String>,
    ): BiStream {
        val reused = connections.remove(route.nodeId)?.let { keepWith(route, it) }
        if (reused != null) return reused
        val fresh = dialRoute(endpoint, dialPlan(route), notes)
        return keepWith(route, fresh)
            ?: throw TransportException.Unreachable(
                RouteFailure("the route's connection closed before its stream opened"),
            )
    }

    /**
     * A new stream on [connection], which is then kept as [route]'s. One that has closed or opens no stream within the
     * connect bound is let go of. One whose open is cancelled is kept as it was: another session's stream may still be
     * on it, as an hour-old one is while its renewal opens, and letting go would cut that stream with no close frame.
     */
    private suspend fun keepWith(
        route: Candidate.Route,
        connection: Connection,
    ): BiStream? {
        var stream: BiStream? = null
        var cancelled = false
        try {
            if (connection.closeReason() == null) stream = openOn(connection)
        } catch (cancel: CancellationException) {
            cancelled = true
            throw cancel
        } finally {
            when {
                stream != null -> connections[route.nodeId] = connection
                cancelled && connection.closeReason() == null -> connections[route.nodeId] = connection
                else -> letGo(connection)
            }
        }
        return stream
    }
}

/** A connection's next stream, or null once it has closed or holds no room for one within the connect bound. */
private suspend fun openOn(connection: Connection): BiStream? =
    try {
        withTimeoutOrNull(ROUTE_CONNECT_TIMEOUT_MS) { connection.openBi() }
    } catch (expectedClosed: IrohException) {
        // The connection is gone: the caller dials again.
        null
    }

private fun letGo(connection: Connection) {
    connection.close(RAIL_CLOSE_CODE, "superseded".encodeToByteArray())
    connection.destroy()
}

private fun unbound(failure: Throwable): TransportException =
    TransportException.Unreachable(RouteFailure("the iroh endpoint did not bind: ${reasonOf(failure)}", failure))

/** Binds with the minimal preset and [known] relays as the map, each with its QUIC port; no key, ALPN or address. */
private suspend fun bindWith(known: Set<Candidate.Relay>): Endpoint {
    val map = RelayMap.empty()
    known.forEach { map.insert(RelayConfig(it.url, it.quicPort.toUShort(), null)) }
    val options = EndpointOptions(preset = presetMinimal(), relayMode = RelayMode.custom(map))
    return try {
        Endpoint.bind(options)
    } finally {
        options.destroy()
        map.destroy()
    }
}

/** What failed, in iroh's words when iroh failed. */
internal fun reasonOf(failure: Throwable): String =
    (failure as? IrohException)?.message() ?: failure.message ?: failure.javaClass.simpleName

/** One stream's halves over iroh's handles, which [release] lets go of once the carrier is done with them. */
private class IrohHalves(
    private val stream: BiStream,
) : RouteHalves {
    private val sending = stream.send()
    private val receiving = stream.recv()

    override suspend fun readExact(size: Int): ByteArray = receiving.readExact(size.toUInt())

    override suspend fun writeAll(bytes: ByteArray) = sending.writeAll(bytes)

    override suspend fun finish() = sending.finish()

    // iroh's code for a half the far end stopped; none once it took everything up to the finish.
    override suspend fun stopped(): Boolean = sending.stopped() != null

    override suspend fun reset(): Boolean = reached { sending.reset(0uL) }

    override suspend fun stopReading(): Boolean = reached { receiving.stop(0uL) }

    override fun release() {
        sending.destroy()
        receiving.destroy()
        stream.destroy()
    }
}

/** Runs [call] on a half that may have ended already: false when it had. */
private suspend fun reached(call: suspend () -> Unit): Boolean =
    try {
        call()
        true
    } catch (expectedEnded: IrohException) {
        // The half had already ended: nothing left to reach.
        false
    }

/** Builds the address of one dial: the daemon's id, the relay and any direct addresses. */
internal fun addressOf(dial: RouteDial): EndpointAddr =
    EndpointId.fromBytes(dial.nodeId.hexToByteArray()).let { id ->
        try {
            EndpointAddr(id, dial.relayUrl, dial.addresses)
        } finally {
            id.destroy()
        }
    }
