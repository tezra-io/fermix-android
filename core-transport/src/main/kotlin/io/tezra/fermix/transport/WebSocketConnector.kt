package io.tezra.fermix.transport

import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** The daemon's TCP connect, bounded as PROTOCOL.md bounds its TLS handshake. */
private const val CONNECT_TIMEOUT_MS = 10_000L

/**
 * From the first DNS lookup to the upgrade. The daemon closes a connection that has not upgraded 15 s
 * after it accepted it (PROTOCOL.md "Transport"), so past a 10 s connect and those 15 s an open has
 * failed. OkHttp lifts this bound once the socket has upgraded.
 */
private const val SETUP_TIMEOUT_MS = 25_000L

/** How long the pool's cleanup waits; with no idle connection kept, it has nothing to wait for. */
private const val POOL_KEEP_ALIVE_MS = 1L

/** The daemon serves the WebSocket here, and only here (PROTOCOL.md "Transport"). */
private const val WS_PATH = "/ws"

private const val MAX_PORT = 65_535

/**
 * Opens the WebSocket every session runs on: `wss://<candidate>:<port>/ws`, pinned to one instance's
 * certificate. One connector serves every instance: their clients share its OkHttp dispatcher, and
 * each open builds a client pinned to that instance's [PinnedTrust]. It keeps no idle connection: the
 * daemon serves one request per connection (PROTOCOL.md "Transport") and an upgrade keeps its own, so
 * a refused open's connection is closed, never kept to be reused. One connector lives as long as the
 * app, as OkHttp advises for a client; there is nothing to close, and the dispatcher's idle threads
 * end on their own after a minute.
 */
class WebSocketConnector {
    private val shared =
        OkHttpClient
            .Builder()
            .connectionPool(ConnectionPool(0, POOL_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS))
            .build()

    /**
     * Suspends until the upgrade succeeds, and fails with a [TransportException]: [TransportException.PinMismatch]
     * for a certificate that is not pinned, [TransportException.Refused] for an HTTP answer,
     * [TransportException.Closed] for a daemon that negotiated compression, and
     * [TransportException.Unreachable] for anything else. Cancelling the open cancels its socket.
     *
     * OkHttp tries a name's addresses one after another, and a certificate that is not pinned on one
     * of them does not stop it: the open fails as a PinMismatch only when every address fails, and it
     * succeeds, reporting nothing, when another address presents the pinned certificate. Design
     * section 13.3 retries no wrong machine, so a name candidate falls short of it until each of its
     * addresses becomes a route of its own; how is the owner's decision.
     */
    suspend fun open(
        candidate: Candidate,
        port: Int,
        trust: PinnedTrust,
    ): Connection {
        require(port in 1..MAX_PORT) { "port $port is not a TCP port" }
        val events = SocketEvents()
        val socket = pinnedClient(shared, trust).newWebSocket(upgradeRequest(candidate, port), events)
        var opened = false
        try {
            events.opened.await()
            opened = true
        } finally {
            // An open that failed or was cancelled lets its socket go; an open one is the Connection's.
            if (!opened) socket.cancel()
        }
        return Connection(socket, events)
    }
}

/**
 * A client for one instance (design section 12.3): its trust alone, HTTP/1.1 and TLS 1.3 or 1.2 only,
 * no proxy, since a daemon's candidates are its own addresses, and no redirect, since the daemon serves
 * two routes. No retry and no WebSocket ping: the racer and the reconnect loop own retries, and the
 * app's own `ping` event owns keepalive. No read timeout either, so a quiet socket stays open; beating
 * the daemon's 150 s idle close is the app's job.
 */
internal fun pinnedClient(
    shared: OkHttpClient,
    trust: PinnedTrust,
): OkHttpClient {
    val tls =
        ConnectionSpec
            .Builder(ConnectionSpec.RESTRICTED_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()
    return shared
        .newBuilder()
        .sslSocketFactory(trust.socketFactory, trust.trustManager)
        .hostnameVerifier(trust.hostnameVerifier)
        .connectionSpecs(listOf(tls))
        .protocols(listOf(Protocol.HTTP_1_1))
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
}

private fun upgradeRequest(
    candidate: Candidate,
    port: Int,
): Request {
    val url =
        HttpUrl
            .Builder()
            .scheme("https")
            .host(candidate.host)
            .port(port)
            .encodedPath(WS_PATH)
            .build()
    return Request.Builder().url(url).build()
}
