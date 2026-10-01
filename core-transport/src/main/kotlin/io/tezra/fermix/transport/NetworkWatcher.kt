package io.tezra.fermix.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads [NetworkFacts] from two callbacks (design sections 5.1 and 5.2): the default network's,
 * which says whether this app has a network, whether a VPN carries it and with which addresses; and
 * one for every VPN network, other apps' included, which is how a VPN that does not apply to this
 * app shows itself. The only class here that needs Android, and so the one the JVM tests leave out:
 * what it derives is [networkFacts], and that is tested. The system calls both callbacks on its
 * connectivity thread; [start] and [stop] come from the app's. NsdManager is not used (section 5.1).
 * A registered callback holds the watcher, and a ConnectivityManager holds the Context it came from,
 * so the watcher takes its manager from the application context of whatever [context] it is given,
 * and never holds an Activity.
 */
class NetworkWatcher(
    context: Context,
) {
    private val connectivity: ConnectivityManager =
        checkNotNull(context.applicationContext.getSystemService(ConnectivityManager::class.java)) {
            "the application context has no ConnectivityManager"
        }

    private val published = MutableStateFlow(NetworkFacts.NONE)
    private val lock = Any()
    private var running = false
    private var defaultNetwork: Network? = null
    private var defaultCapabilities: NetworkCapabilities? = null
    private var defaultLink: LinkProperties? = null
    private val vpnNetworks = mutableSetOf<Network>()

    /** The facts as last read; [NetworkFacts.NONE] before [start] and after [stop]. */
    val facts: StateFlow<NetworkFacts> = published.asStateFlow()

    private val defaultCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) =
                update {
                    defaultNetwork = network
                    defaultCapabilities = null
                    defaultLink = null
                }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = update { if (network == defaultNetwork) defaultCapabilities = networkCapabilities }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) = update { if (network == defaultNetwork) defaultLink = linkProperties }

            override fun onLost(network: Network) = update { if (network == defaultNetwork) forgetDefault() }
        }

    private val vpnCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update { vpnNetworks += network }

            override fun onLost(network: Network) = update { vpnNetworks -= network }
        }

    /**
     * Registers both callbacks; the facts follow from the system's first reports. A registration that
     * throws leaves the watcher stopped, with nothing registered.
     */
    fun start() =
        synchronized(lock) {
            check(!running) { "the network watcher is already running" }
            registerBoth()
            // Reports wait on the lock until here, so none is dropped as one from a stopped watcher.
            running = true
        }

    /** Forgets what the callbacks reported and releases both, the second even when the first fails. */
    fun stop() =
        synchronized(lock) {
            check(running) { "the network watcher is not running" }
            running = false
            forgetDefault()
            vpnNetworks.clear()
            published.value = NetworkFacts.NONE
            try {
                connectivity.unregisterNetworkCallback(defaultCallback)
            } finally {
                connectivity.unregisterNetworkCallback(vpnCallback)
            }
        }

    /**
     * Registers both callbacks or neither: when the second throws, as it does past the system's limit
     * on callbacks, the first is released and the failure thrown on.
     */
    private fun registerBoth() {
        connectivity.registerDefaultNetworkCallback(defaultCallback)
        var registered = false
        try {
            connectivity.registerNetworkCallback(vpnRequest(), vpnCallback)
            registered = true
        } finally {
            if (!registered) connectivity.unregisterNetworkCallback(defaultCallback)
        }
    }

    /**
     * Applies one report and publishes the facts; a report that arrives after [stop] is dropped. A new
     * default network is published once its capabilities and link properties have come too, which the
     * system sends right after it: half of one would read as a network with no VPN for a moment.
     */
    private fun update(change: () -> Unit) =
        synchronized(lock) {
            if (!running) return@synchronized
            change()
            val partial = defaultNetwork != null && (defaultCapabilities == null || defaultLink == null)
            if (!partial) published.value = readFacts()
        }

    private fun readFacts(): NetworkFacts =
        networkFacts(
            defaultNetwork = defaultNetwork?.networkHandle,
            defaultIsVpn = defaultCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            defaultAddresses = defaultLink?.linkAddresses.orEmpty().map { it.address },
            vpnNetworks = vpnNetworks.mapTo(mutableSetOf()) { it.networkHandle },
        )

    private fun forgetDefault() {
        defaultNetwork = null
        defaultCapabilities = null
        defaultLink = null
    }
}

/**
 * Every VPN network, whichever apps it applies to (API 31). A request starts with capabilities a VPN
 * may lack, NOT_VPN among them, so they are cleared and the VPN transport alone is asked for.
 */
private fun vpnRequest(): NetworkRequest =
    NetworkRequest
        .Builder()
        .clearCapabilities()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .setIncludeOtherUidNetworks(true)
        .build()
