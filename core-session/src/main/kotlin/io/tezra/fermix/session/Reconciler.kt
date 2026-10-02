package io.tezra.fermix.session

import io.tezra.fermix.protocol.CandidateScope
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.MAX_CANDIDATES
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import io.tezra.fermix.protocol.Candidate as WireCandidate

/**
 * How long the daemon has to answer a reconciliation request, counted while the session waits on it
 * only; it answers from its store at once.
 */
internal const val ANSWER_TIMEOUT_MS = 30_000L

/** Events handled while an answer is awaited; past this it is overdue. */
private const val MAX_EVENTS_BEFORE_ANSWER = 10_000

/** `request_status.client_msg_ids` at most (design section 7, the `request_status` row). */
internal const val MAX_STATUS_IDS = 32

/** A `mutations_pull`'s rows at most. */
private const val MUTATION_PAGE_LIMIT = 200

/** Mutation pages one reconnect pulls; past this the rest waits for the next reconnect. */
private const val MAX_MUTATION_PAGES = 100

/** An IPv4 literal; anything with a colon is an IPv6 one. A candidate is classified without DNS. */
private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** The connection ended while the actor waited on it. */
internal class ConnectionEnded(
    val ending: Ending,
) : Exception("the connection ended: $ending")

/** The actor's side of a connection's events: the next one, or the first that answers a request. */
internal class Inbox(
    private val inputs: ReceiveChannel<Input>,
    private val dispatch: Dispatch,
    private val now: () -> Long,
) {
    suspend fun next(): ServerEvent =
        when (val input = inputs.receive()) {
            is Input.Frame -> input.decoded.event
            is Input.End -> throw ConnectionEnded(input.ending)
        }

    /**
     * The first event [match] takes, every event before it dispatched as usual: the daemon keeps
     * streaming while it answers (approval replays follow `hello_ack` at once, for one). Only the time
     * spent waiting on the daemon counts toward [ANSWER_TIMEOUT_MS]: the session's own work on the events
     * between, an announcer's included, is the phone's, and is never taken for the daemon's silence.
     */
    suspend fun <T : ServerEvent.Known> await(
        what: String,
        match: (ServerEvent) -> T?,
    ): T {
        var waitedMs = 0L
        repeat(MAX_EVENTS_BEFORE_ANSWER) {
            val from = now()
            val event =
                withTimeoutOrNull(ANSWER_TIMEOUT_MS - waitedMs) { next() }
                    ?: throw SessionProtocolError("no $what within $ANSWER_TIMEOUT_MS ms")
            waitedMs += now() - from
            match(event)?.let { found -> return found }
            dispatch.event(event)
        }
        throw SessionProtocolError("no $what in $MAX_EVENTS_BEFORE_ANSWER events")
    }
}

/**
 * Reconnect reconciliation (design section 8.2, review R2, R12, R16), between `hello_ack` and the
 * outbox: the read frontier; `active_turns`, a shown turn it does not list being over; the approval
 * cards against `pending_approvals`; `request_status` for every outbox item and every turn shown before
 * this reconnect; `mutations_pull` to the end, or the rebuild on `mutations_gone`; the first history
 * pull; and only then the outbox, so each queued request is submitted at most once. Every step but the
 * frontier and the pull is protocol v2's (design section 7, the `hello_ack.active_turns`,
 * `hello_ack.pending_approvals`, `request_status` and `mutation_seq` rows); protocol v1 resent the
 * outbox straight after `hello_ack`. A daemon that refuses `request_status` or `mutations_pull` with an
 * `error` naming no request has answered it: the step is skipped and told as a refusal, and the
 * reconciliation goes on. The outbox then resends what the daemon dedupes by client_msg_id, and the
 * mutation cursor stays where it was.
 *
 * The approval cards are judged before `request_status`, not after it as the brief orders: the replayed
 * cards follow `hello_ack` at once, and a card raised while the answer is awaited is live, not missed.
 * A `hello_ack` without `active_turns` or `pending_approvals`, which core-protocol takes at v2 and which
 * a daemon without `caps.approval_replay` leaves out, skips that step rather than ending every turn or
 * closing every card on silence.
 */
internal class Reconciler(
    private val core: SessionCore,
    private val live: Live,
    private val requests: Requests,
    private val inbox: Inbox,
) {
    private val profile: String get() = core.instance.profileId

    suspend fun reconcile(ack: ServerEvent.HelloAck) {
        core.emit(SessionEvent.Server(ack))
        candidates(ack)
        val timeline = core.timeline()
        if (timeline.connected(ack.historyHeadSeq, ack.readUpToSeq)) {
            core.emit(SessionEvent.ReadFrontier(timeline.readFrontier))
        }
        // The ack this socket owes, and a frontier the owner moved while away, reach the daemon first.
        live.report()
        // Asked after even when the turn is over now: its request row says how it ended.
        val turnRequests = core.turnRequests()
        // A list the daemon left out says nothing: the shown turns and cards stay as they are.
        ack.activeTurns?.let { active -> core.turns { it.reconcile(active) } }
        ack.pendingApprovals?.let { pending ->
            core.approvals.closedExcept(pending).forEach { core.emit(SessionEvent.ApprovalClosedWhileAway(it)) }
        }
        statuses(requests.pendingIds(turnRequests))
        ack.mutationHeadSeq?.let { mutations(it) }
        live.openHistory()
        requests.drain(live)
        live.checkCaughtUp()
    }

    /** The daemon's routes, best first, replace the ones the session races, when it sends any. */
    private suspend fun candidates(ack: ServerEvent.HelloAck) {
        val routes = routesOf(ack.candidates)
        if (routes.isEmpty()) return
        core.candidates = routes
        core.emit(SessionEvent.Candidates(core.candidates))
    }

    private suspend fun statuses(ids: List<String>) {
        ids.chunked(MAX_STATUS_IDS).forEach { batch ->
            live.post(ClientEvent.RequestStatus(batch))
            val answer = inbox.await("request_status_page") { it as? ServerEvent.RequestStatusPage ?: it.refusal() }
            if (answer is ServerEvent.Error) return refused(answer)
            requests.status(answer as ServerEvent.RequestStatusPage)
        }
    }

    /** An empty cache has nothing to update in place: it adopts the head and pulls nothing. */
    private suspend fun mutations(head: ULong) {
        val stored =
            core.parts.store
                .cursors()
                .lastMutationSeq
        when {
            head <= stored -> Unit
            core.timeline().cursor == 0uL -> core.parts.store.applyMutations(emptyList(), head)
            else -> pullMutations(stored, head)
        }
    }

    private suspend fun pullMutations(
        from: ULong,
        head: ULong,
    ) {
        var after: ULong? = from
        var pages = 0
        while (after != null && pages < MAX_MUTATION_PAGES) {
            after = mutationPage(after, head)
            pages++
        }
        if (after != null) core.log(DiagnosticKind.BOUND, "mutations after $after wait for the next connection")
    }

    /**
     * One page applied; the cursor to pull from next, or null once the feed is done, gone or refused. A
     * `next` that does not move past [after] would pull the same page for good: it is a protocol error.
     */
    private suspend fun mutationPage(
        after: ULong,
        head: ULong,
    ): ULong? {
        live.post(ClientEvent.MutationsPull(profile, after, MUTATION_PAGE_LIMIT))
        val answer = inbox.await("mutations_page") { it as? ServerEvent.MutationsPage ?: it.refusal() }
        if (answer is ServerEvent.Error) {
            if (answer.gone()) rebuild(head) else refused(answer)
            return null
        }
        val page = answer as ServerEvent.MutationsPage
        val next = page.next
        if (next != null && next <= after) throw SessionProtocolError("a mutations_page after $after names $next")
        val newest = page.rows.maxOfOrNull { it.mutationSeq } ?: after
        // Done: every mutation up to the head is in.
        core.parts.store.applyMutations(page.rows, next ?: maxOf(after, newest, head))
        return next
    }

    /** The daemon refused a reconciliation request: its step is skipped, and the app hears why. */
    private suspend fun refused(error: ServerEvent.Error) {
        core.log(DiagnosticKind.REFUSED, error.code)
        core.emit(SessionEvent.Refused(error))
    }

    /** `mutations_gone`: the cache is dropped and rebuilt from the newest page (design section 7). */
    private suspend fun rebuild(head: ULong) {
        core.log(DiagnosticKind.REFUSED, ServerEvent.Error.MUTATIONS_GONE)
        core.timeline().rebuild(head)
        core.emit(SessionEvent.RebuildProfileCache(head))
    }
}

private fun ServerEvent.Error.gone(): Boolean = code == ServerEvent.Error.MUTATIONS_GONE

/**
 * An `error` that names no request and no media fetch, taken as the refusal of the reconciliation request
 * awaited. The phone sends little else meanwhile: its acks, read states and pings, and an older page or
 * a cancel the app asks for. Were one of those refused, the step would be skipped, and the request's own
 * answer, coming later, would close the connection 1002 as an event out of its place.
 */
private fun ServerEvent.refusal(): ServerEvent.Error? =
    (this as? ServerEvent.Error)?.takeIf { it.clientMsgId == null && it.ref == null }

/**
 * The daemon's routes from a `hello_ack` or a `pair_approved`, best first, each once and at most 16: its
 * scope as the daemon says, its kind from the host.
 */
internal fun routesOf(candidates: List<WireCandidate>): List<Candidate> =
    candidates
        .map { Candidate(it.host, scopeOf(it.scope), kindOf(it.host)) }
        .distinct()
        .take(MAX_CANDIDATES)

private fun kindOf(host: String): Candidate.Kind =
    if (':' in host || IPV4_LITERAL.matches(host)) Candidate.Kind.IP else Candidate.Kind.NAME

private fun scopeOf(scope: CandidateScope): Candidate.Scope =
    when (scope) {
        CandidateScope.LAN -> Candidate.Scope.LAN
        CandidateScope.TAILNET -> Candidate.Scope.TAILNET
    }
