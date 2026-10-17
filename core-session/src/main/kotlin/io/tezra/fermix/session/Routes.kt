package io.tezra.fermix.session

import io.tezra.fermix.protocol.CandidateScope
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.MAX_CANDIDATES
import io.tezra.fermix.protocol.Candidate as WireCandidate

/** An IPv4 literal; any host with a `:` is an IPv6 one. */
private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** A skipped kind's text at most, as the Diagnostics show it: the daemon's text, so bounded and printable. */
private const val MAX_KIND_CHARS = 32

/**
 * The daemon's hosts from a `hello_ack` or a `pair_approved`, best first, each once and at most 16: its scope as
 * the daemon says, its kind from the host. The route and a kind this app does not know are no host.
 */
internal fun hostsOf(candidates: List<WireCandidate>): List<Candidate.Host> =
    candidates
        .filterIsInstance<WireCandidate.Host>()
        .map { Candidate.Host(it.host, scopeOf(it.scope), kindOf(it.host)) }
        .distinct()
        .take(MAX_CANDIDATES)

/** The daemon's iroh route from a `hello_ack` or a `pair_approved`: the last it lists, none without one. */
internal fun routeOf(candidates: List<WireCandidate>): Candidate.Route? =
    candidates.filterIsInstance<WireCandidate.Iroh>().lastOrNull()?.let(::routeOf)

/** One iroh candidate as the race's route; each relay's `https://<host>/` as its host. */
internal fun routeOf(iroh: WireCandidate.Iroh): Candidate.Route =
    Candidate.Route(
        iroh.nodeId,
        iroh.relays.map { Candidate.Relay(it.url.removePrefix("https://").removeSuffix("/"), it.quicPort) },
        iroh.relaysRev,
    )

/**
 * The Diagnostics' one line for the candidates of [candidates] whose kind this app does not know, which it skips
 * (PROTOCOL.md "Server events"); null when it skipped none. Each kind is the daemon's text, cut and kept to
 * printable ASCII.
 */
internal fun skippedNote(candidates: List<WireCandidate>): String? {
    val skipped = candidates.filterIsInstance<WireCandidate.Skipped>()
    if (skipped.isEmpty()) return null
    val kinds = skipped.map { it.kind?.let(::printable) ?: "a kind that is not text" }.distinct()
    return "skipped ${skipped.size} candidate(s) of a kind this app does not know: ${kinds.joinToString()}"
}

/** The current hosts and route, each kept where [hosts] or [route] brings none (PROTOCOL.md "Server events"). */
internal fun updatedCandidates(
    current: List<Candidate>,
    hosts: List<Candidate.Host>,
    route: Candidate.Route?,
): List<Candidate> =
    hosts.ifEmpty { current.filterIsInstance<Candidate.Host>() } +
        listOfNotNull(route ?: current.filterIsInstance<Candidate.Route>().lastOrNull())

/** A word of the daemon's, a candidate's kind or a block's type, as the Diagnostics show it: quoted, cut, ASCII. */
internal fun printable(kind: String): String =
    "'" + kind.take(MAX_KIND_CHARS).map { if (it.code in ' '.code..'~'.code) it else '?' }.joinToString("") + "'"

private fun kindOf(host: String): Candidate.Kind =
    if (':' in host || IPV4_LITERAL.matches(host)) Candidate.Kind.IP else Candidate.Kind.NAME

private fun scopeOf(scope: CandidateScope): Candidate.Scope =
    when (scope) {
        CandidateScope.LAN -> Candidate.Scope.LAN
        CandidateScope.TAILNET -> Candidate.Scope.TAILNET
    }
