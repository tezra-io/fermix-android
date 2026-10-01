package io.tezra.fermix.transport

/**
 * The candidates a `hello_ack`, a `pair_approved` or a pairing link carries at most (PROTOCOL.md),
 * and so the most one race tries.
 */
const val MAX_CANDIDATES = 16

/** A host's UTF-8 bytes at most: a DNS name's bound, and the schema's on `candidate.host`. */
private const val MAX_HOST_BYTES = 253

/**
 * One route to a daemon: a [host] to open `wss://<host>:<port>/ws` on, where it lies, and whether it
 * is an address literal or a name. The phone reads the scope from `hello_ack` or `pair_approved`, and
 * the kind from the host itself; a pairing link's candidates carry neither, and are classified by the
 * caller before a race.
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
