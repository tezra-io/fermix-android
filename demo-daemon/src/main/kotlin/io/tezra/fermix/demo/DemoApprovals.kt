package io.tezra.fermix.demo

import io.tezra.fermix.protocol.ApprovalOutcome
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MILLIS_PER_SECOND = 1_000L
private const val HEX_RADIX = 16

/**
 * An approval a demo Fermix waits on (PROTOCOL.md "Approvals"): its card, its token, which the phone sends back in
 * a route and never shows, how long it waits from when it was raised, the words the request it held up answers
 * with once the owner grants it ([resumed]), and the timer that withdraws it `expired` ([expiry]).
 */
internal class Approval(
    val card: AskCard,
    val token: String,
    private val raisedAtMs: Long,
    private val ttlMs: Long,
    val resumed: String,
    private val expiry: Job,
) {
    /** The card as a phone connecting at [nowMs] gets it: its `ttl_s` the whole seconds left, rounded up. */
    fun event(nowMs: Long): ServerEvent.Approval {
        val leftMs = (raisedAtMs + ttlMs - nowMs).coerceAtLeast(1L)
        val ttlS = ((leftMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
        return ServerEvent.Approval(
            card.id,
            card.kind,
            card.text,
            token,
            ttlS,
            "/confirm $token",
            "/deny $token",
            card.detail,
        )
    }

    /** Its ttl no longer runs: the owner answered it. */
    fun answered() = expiry.cancel()
}

/**
 * What an approval's card says: its id, its kind (`sandbox`), its question and its detail; and what the grant
 * changes in the sandbox's config, the line `/confirm`'s answer shows ([grants], as the engine's config diff
 * writes one).
 */
internal class AskCard(
    val id: String,
    val kind: String,
    val text: String,
    val detail: String,
    val grants: String,
)

/**
 * Raises [card] on [home], as a turn of a phone's request does when its tool needs the owner (PROTOCOL.md
 * "Approvals"): it goes to every phone connected now, and again right after each `hello_ack` while it waits; [ttlMs]
 * later, unanswered, `approval_resolved` withdraws it `expired`. The turn that raised it does not wait on it: the
 * turn ends, and the owner's `/confirm` or `/deny` answers the card ([resolve]), the request it held up running
 * again as a turn of its own on a grant, with [resumed] as its answer (DemoTurns.resume).
 */
internal fun raise(
    home: DemoHome,
    parts: DemoParts,
    card: AskCard,
    ttlMs: Long,
    resumed: String,
) {
    val token = "demo-${home.random.nextLong().toULong().toString(HEX_RADIX)}"
    val expiry =
        parts.scope.launch(start = CoroutineStart.LAZY) {
            delay(ttlMs)
            if (home.approvals.remove(card.id) != null) {
                home.broadcast(ServerEvent.ApprovalResolved(card.id, ApprovalOutcome.EXPIRED))
            }
        }
    val approval = Approval(card, token, parts.nowMs(), ttlMs, resumed, expiry)
    home.approvals[card.id] = approval
    home.broadcast(approval.event(parts.nowMs()))
    expiry.start()
}

/**
 * The approval [token] names, answered [outcome]: withdrawn with `approval_resolved` to every phone connected, its
 * ttl stopped; or none when no card waits with that token (it expired, was answered, or never was).
 */
internal fun resolve(
    home: DemoHome,
    token: String?,
    outcome: ApprovalOutcome,
): Approval? {
    val waiting = home.approvals.values.firstOrNull { it.token == token } ?: return null
    home.approvals.remove(waiting.card.id)
    waiting.answered()
    home.broadcast(ServerEvent.ApprovalResolved(waiting.card.id, outcome))
    return waiting
}
