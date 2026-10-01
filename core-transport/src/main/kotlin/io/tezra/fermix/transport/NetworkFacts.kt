package io.tezra.fermix.transport

import java.net.Inet4Address
import java.net.InetAddress

/** 100.64.0.0/10, the shared address range: its first octet, and the top two bits of its second. */
private const val CGNAT_FIRST_OCTET = 100
private const val CGNAT_SECOND_OCTET_MASK = 0xC0
private const val CGNAT_SECOND_OCTET_BITS = 0x40

/**
 * What the phone's network says about reaching a daemon (design section 5.2), as [NetworkWatcher]
 * last read it. [defaultNetwork] is this app's default network, as the system's handle for it, or
 * null with no network; a change of it is a re-race trigger as much as a VPN coming or going or a
 * 100.64/10 address appearing (section 5.1), and each of those changes these facts.
 */
data class NetworkFacts(
    val defaultNetwork: Long?,
    val defaultHasVpn: Boolean,
    val defaultHasCgnatAddress: Boolean,
    val otherUidVpnPresent: Boolean,
) {
    init {
        require(defaultNetwork != null || (!defaultHasVpn && !defaultHasCgnatAddress)) {
            "facts without a default network hold a fact of one"
        }
    }

    /** The phone has a default network, the first of gotcha 20's two facts. */
    val hasNetwork: Boolean get() = defaultNetwork != null

    companion object {
        /** No network at all: before the first callback, and after the last network is lost. */
        val NONE = NetworkFacts(null, defaultHasVpn = false, defaultHasCgnatAddress = false, otherUidVpnPresent = false)
    }
}

/**
 * The facts from what the two callbacks last reported: this app's default network, whether it is a
 * VPN and its addresses, and every VPN network the callback that includes other apps' networks sees.
 * Android runs one VPN at a time, so a VPN network that is not this app's default is one that does not
 * apply to this app: another app's, or Tailscale with this app split out of it.
 */
internal fun networkFacts(
    defaultNetwork: Long?,
    defaultIsVpn: Boolean,
    defaultAddresses: List<InetAddress>,
    vpnNetworks: Set<Long>,
): NetworkFacts =
    NetworkFacts(
        defaultNetwork = defaultNetwork,
        defaultHasVpn = defaultNetwork != null && defaultIsVpn,
        defaultHasCgnatAddress = defaultNetwork != null && defaultAddresses.any(::isCgnatAddress),
        otherUidVpnPresent = vpnNetworks.any { it != defaultNetwork },
    )

/**
 * An address in 100.64.0.0/10. Tailscale gives each device one, and so do carriers' NATs and other
 * overlays, so it is evidence of a tailnet, never identification of one (section 5.2).
 */
internal fun isCgnatAddress(address: InetAddress): Boolean {
    val bytes = address.address
    return address is Inet4Address &&
        bytes[0] == CGNAT_FIRST_OCTET.toByte() &&
        (bytes[1].toInt() and CGNAT_SECOND_OCTET_MASK) == CGNAT_SECOND_OCTET_BITS
}
