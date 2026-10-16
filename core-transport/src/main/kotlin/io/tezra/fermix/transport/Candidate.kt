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

/** An iroh endpoint id: 64 lowercase hex digits (PROTOCOL.md "Remote reach"). */
private val NODE_ID = Regex("[0-9a-f]{64}")

/** A relay list's revision: 16 lowercase hex digits. */
private val RELAY_LIST_REV = Regex("[0-9a-f]{16}")

/** A relay's host as the route names it: lowercase letters, digits, dots and inner hyphens. */
private val RELAY_HOST = Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")

/** The relays a route names at most (the schema's `irohCandidate.relays`). */
const val MAX_ROUTE_RELAYS = 8

private const val MAX_PORT = 65_535

/**
 * One route to a daemon: a [Host] to open `wss://<host>:<port>/ws` on, or the daemon's iroh [Route], reached
 * from any network through Fermix's relays (PROTOCOL.md "Remote reach"). The phone reads a host's scope from
 * `hello_ack` or `pair_approved`, and its kind from the host itself; a pairing link's candidates carry neither,
 * and [linkCandidate] classifies them by the ranges the daemon draws them from. A route's scope is [Scope.REMOTE].
 */
sealed interface Candidate {
    /** Where the candidate lies, which the Instance screen's path line names. */
    val scope: Scope

    /** A host: an address literal or a name, on the owner's LAN or tailnet. */
    data class Host(
        val host: String,
        override val scope: Scope,
        val kind: Kind,
    ) : Candidate {
        init {
            require(host.isNotEmpty()) { "a candidate's host is empty" }
            val bytes = host.encodeToByteArray().size
            require(bytes <= MAX_HOST_BYTES) { "a candidate's host is $bytes bytes, past $MAX_HOST_BYTES" }
            require(scope != Scope.REMOTE) { "a host lies on the LAN or the tailnet; the route alone is remote" }
        }
    }

    /**
     * The daemon's iroh endpoint, [nodeId], the relays to reach it through, at most [MAX_ROUTE_RELAYS], and that
     * list's [revision], which `hello.relay_list_rev` names back once it is stored. Its node id is the daemon's,
     * never the phone's, and the screens show none.
     */
    data class Route(
        val nodeId: String,
        val relays: List<Relay>,
        val revision: String,
    ) : Candidate {
        override val scope: Scope get() = Scope.REMOTE

        init {
            require(NODE_ID.matches(nodeId)) { "a route's node id is not 64 lowercase hex digits" }
            require(
                relays.size in 1..MAX_ROUTE_RELAYS,
            ) { "a route names ${relays.size} relays, not 1 to $MAX_ROUTE_RELAYS" }
            require(RELAY_LIST_REV.matches(revision)) { "a route's revision is not 16 lowercase hex digits" }
        }
    }

    /** One relay of a route: `https://<host>/`, with QUIC address discovery on [quicPort]. */
    data class Relay(
        val host: String,
        val quicPort: Int,
    ) {
        init {
            require(RELAY_HOST.matches(host)) { "a relay's host '$host' is not a lowercase DNS name" }
            require(quicPort in 1..MAX_PORT) { "a relay's QUIC port $quicPort is outside 1 to $MAX_PORT" }
        }

        /** The relay's URL, as iroh's relay map keys it. */
        val url: String get() = "https://$host/"
    }

    /** Where a candidate lies: on the owner's LAN, on their tailnet, or anywhere, through the relays. */
    enum class Scope { LAN, TAILNET, REMOTE }

    /** What a host is: an address literal, or a name (a MagicDNS name on the tailnet). */
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
fun linkCandidate(host: String): Candidate.Host? {
    if (IPV4_LITERAL.matches(host)) return addressScope(host)?.let { Candidate.Host(host, it, Candidate.Kind.IP) }
    return if (isMagicDnsName(host)) Candidate.Host(host, Candidate.Scope.TAILNET, Candidate.Kind.NAME) else null
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
 * Design section 5.1's order, and M62's: the last successful candidate first, then the tailnet addresses, the
 * MagicDNS names and the LAN addresses, and the route last. A LAN name, which section 5.1 does not name, goes
 * after the LAN addresses. Within a class the order given is kept: the daemon lists its candidates best first. A
 * candidate listed twice is tried once, and a last successful candidate the list no longer holds is not tried.
 * The race holds at most [MAX_CANDIDATES]: up to that many hosts and one route come in (a pairing link may name
 * 16 hosts beside its route), and the hosts past the room the route leaves are cut, as the daemon cuts its own to
 * 15 when it lists its route.
 */
fun candidateOrder(
    candidates: List<Candidate>,
    lastSuccessful: Candidate? = null,
): List<Candidate> {
    val distinct = candidates.distinct()
    val hosts = distinct.filterIsInstance<Candidate.Host>()
    val routes = distinct.filterIsInstance<Candidate.Route>()
    require(hosts.size <= MAX_CANDIDATES) { "${hosts.size} hosts is past the wire's $MAX_CANDIDATES" }
    require(routes.size <= 1) { "${routes.size} routes; an instance has one" }
    val first = listOfNotNull(lastSuccessful?.takeIf { it in distinct })
    // The tailnet before the LAN, then an address before a name; false sorts first, and the sort is
    // stable, so a class keeps its order.
    val classes = compareBy<Candidate.Host>({ it.scope != Candidate.Scope.TAILNET }, { it.kind != Candidate.Kind.IP })
    val last = routes.filterNot { it in first }
    val room = MAX_CANDIDATES - first.size - last.size
    return first + hosts.filterNot { it in first }.sortedWith(classes).take(room) + last
}
