package io.tezra.fermix.transport

/**
 * The candidates a `hello_ack`, a `pair_approved` or a pairing link carries at most (PROTOCOL.md),
 * and so the most one race tries.
 */
const val MAX_CANDIDATES = 16

/** A host's UTF-8 bytes at most: a DNS name's bound, and the schema's on `candidate.host`. */
private const val MAX_HOST_BYTES = 253

/** An IPv4 address as four plain decimal octets, 0 to 255, none with a leading zero. */
private val IPV4_LITERAL = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")

/** One label of a MagicDNS name as the daemon writes it: lowercase letters, digits and inner hyphens. */
private val NAME_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/** Every MagicDNS name ends so. */
private const val MAGIC_DNS_SUFFIX = ".ts.net"

// The ranges the daemon draws a link's addresses from (its Discovery.classify): the tailnet's
// 100.64.0.0/10, and the private LAN's 10.0.0.0/8, 172.16.0.0/12 and 192.168.0.0/16.
private const val TAILNET_FIRST_OCTET = 100
private val TAILNET_SECOND_OCTETS = 64..127
private const val LAN_A_FIRST_OCTET = 10
private const val LAN_B_FIRST_OCTET = 172
private val LAN_B_SECOND_OCTETS = 16..31
private const val LAN_C_FIRST_OCTET = 192
private const val LAN_C_SECOND_OCTET = 168

/**
 * One route to a daemon: a [host] to open `wss://<host>:<port>/ws` on, where it lies, and whether it
 * is an address literal or a name. The phone reads the scope from `hello_ack` or `pair_approved`, and
 * the kind from the host itself; a pairing link's candidates carry neither, and [linkCandidate]
 * classifies them by the ranges the daemon draws them from.
 */
data class Candidate(
    val host: String,
    val scope: Scope,
    val kind: Kind,
) {
    init {
        require(host.isNotEmpty()) { "a candidate's host is empty" }
        val bytes = host.encodeToByteArray().size
        require(bytes <= MAX_HOST_BYTES) { "a candidate's host is $bytes bytes, past $MAX_HOST_BYTES" }
    }

    /** Where a candidate lies: on the owner's LAN, or on their tailnet. */
    enum class Scope { LAN, TAILNET }

    /** What a candidate's host is: an address literal, or a name (a MagicDNS name on the tailnet). */
    enum class Kind { IP, NAME }
}

/**
 * A pairing link's [host] as a candidate, or null for a host outside the ranges a daemon puts in a link
 * (PROTOCOL.md "Pairing link"; design section 13.3, step 3, "candidates in LAN/tailnet ranges"): an
 * address in the tailnet's 100.64.0.0/10 or a MagicDNS name under ts.net is a tailnet candidate, and an
 * address in 10.0.0.0/8, 172.16.0.0/12 or 192.168.0.0/16 a LAN one. The daemon offers nothing else, no
 * IPv6 address and no other name, so anything else is a link it did not write. Classified from the text
 * alone, never through DNS.
 */
fun linkCandidate(host: String): Candidate? {
    if (IPV4_LITERAL.matches(host)) return addressScope(host)?.let { Candidate(host, it, Candidate.Kind.IP) }
    return if (isMagicDnsName(host)) Candidate(host, Candidate.Scope.TAILNET, Candidate.Kind.NAME) else null
}

/** The scope of an IPv4 literal's range, or null outside the tailnet's and the private LAN's. */
private fun addressScope(literal: String): Candidate.Scope? {
    val (first, second) = literal.split('.').map(String::toInt)
    return when {
        first == TAILNET_FIRST_OCTET && second in TAILNET_SECOND_OCTETS -> Candidate.Scope.TAILNET
        first == LAN_A_FIRST_OCTET -> Candidate.Scope.LAN
        first == LAN_B_FIRST_OCTET && second in LAN_B_SECOND_OCTETS -> Candidate.Scope.LAN
        first == LAN_C_FIRST_OCTET && second == LAN_C_SECOND_OCTET -> Candidate.Scope.LAN
        else -> null
    }
}

private fun isMagicDnsName(host: String): Boolean =
    host.endsWith(MAGIC_DNS_SUFFIX) &&
        host.length > MAGIC_DNS_SUFFIX.length &&
        host.encodeToByteArray().size <= MAX_HOST_BYTES &&
        host.split('.').all(NAME_LABEL::matches)

/**
 * Design section 5.1's order: the last successful candidate first, then the tailnet addresses, the
 * MagicDNS names and the LAN addresses. A LAN name, which section 5.1 does not name, goes last. Within
 * a class the order given is kept: the daemon lists its candidates best first. A candidate listed
 * twice is tried once, and a last successful candidate the list no longer holds is not tried: the
 * daemon's current list, at most 16, is the race's cap.
 */
fun candidateOrder(
    candidates: List<Candidate>,
    lastSuccessful: Candidate? = null,
): List<Candidate> {
    require(candidates.size <= MAX_CANDIDATES) { "${candidates.size} candidates is past the wire's $MAX_CANDIDATES" }
    val distinct = candidates.distinct()
    val first = listOfNotNull(lastSuccessful?.takeIf { it in distinct })
    // The tailnet before the LAN, then an address before a name; false sorts first, and the sort is
    // stable, so a class keeps its order.
    val classes = compareBy<Candidate>({ it.scope != Candidate.Scope.TAILNET }, { it.kind != Candidate.Kind.IP })
    return first + (distinct - first.toSet()).sortedWith(classes)
}
