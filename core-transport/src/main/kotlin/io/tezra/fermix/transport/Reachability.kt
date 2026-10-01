package io.tezra.fermix.transport

/**
 * What the network facts say about reaching an instance (design section 5.2). The UI picks the copy;
 * this holds none. Only a completed handshake to a tailnet candidate confirms a tailnet.
 */
enum class Reachability {
    /** A VPN applies to this app and holds a 100.64/10 address: Tailscale is likely up. */
    TAILNET_LIKELY,

    /** Another app's VPN is up and none applies to this app: it is split out of Tailscale. */
    EXCLUDED_FROM_TAILSCALE,

    /** No VPN network at all. */
    TAILSCALE_OFF,

    /** A VPN applies to this app with no 100.64/10 address, and the instance has tailnet candidates. */
    VPN_HOLDS_THE_SLOT,

    /** The phone has no network. */
    NO_NETWORK,

    /** A VPN is up, but the instance has no tailnet candidate for it to hold back. */
    PLAIN,
}

/**
 * Section 5.2's definitions, read in this order, the first row that holds deciding ("tailnet" is
 * whether the instance has a candidate of scope tailnet):
 *
 * | network | default VPN | 100.64/10 on it | other app's VPN | tailnet | reachability            |
 * |---------|-------------|-----------------|-----------------|---------|-------------------------|
 * | no      | -           | -               | any             | any     | NO_NETWORK              |
 * | yes     | yes         | yes             | any             | any     | TAILNET_LIKELY          |
 * | yes     | yes         | no              | any             | yes     | VPN_HOLDS_THE_SLOT      |
 * | yes     | yes         | no              | any             | no      | PLAIN                   |
 * | yes     | no          | any             | yes             | yes     | EXCLUDED_FROM_TAILSCALE |
 * | yes     | no          | any             | yes             | no      | PLAIN                   |
 * | yes     | no          | any             | no              | any     | TAILSCALE_OFF           |
 *
 * A 100.64/10 address on a default network that is no VPN is a carrier's NAT, not a tailnet, and
 * counts for nothing. Section 5.2 has a VPN hold the slot when "only tailnet candidates are
 * reachable"; facts cannot tell which candidates are reachable, and this is read when every candidate
 * has failed, so it holds when the instance has a tailnet candidate, which the VPN keeps from Tailscale.
 * Tailscale off is section 5.2's "no VPN network at all", whatever the candidates.
 */
fun reachability(
    facts: NetworkFacts,
    candidates: List<Candidate>,
): Reachability {
    val tailnet = candidates.any { it.scope == Candidate.Scope.TAILNET }
    return when {
        !facts.hasNetwork -> Reachability.NO_NETWORK
        facts.defaultHasVpn && facts.defaultHasCgnatAddress -> Reachability.TAILNET_LIKELY
        facts.defaultHasVpn -> if (tailnet) Reachability.VPN_HOLDS_THE_SLOT else Reachability.PLAIN
        facts.otherUidVpnPresent -> if (tailnet) Reachability.EXCLUDED_FROM_TAILSCALE else Reachability.PLAIN
        else -> Reachability.TAILSCALE_OFF
    }
}
