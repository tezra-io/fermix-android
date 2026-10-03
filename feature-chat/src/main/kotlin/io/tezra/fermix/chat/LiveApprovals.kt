package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ApprovalOutcome
import io.tezra.fermix.session.SessionEvent

/** Approval cards a chat remembers at most; past it the oldest that ended is let go, and then the oldest. */
internal const val MAX_REMEMBERED_APPROVALS = 32

private const val SECOND_MS = 1_000L

/** How a card stands, as the daemon and the reconnect said: waiting, resolved, or gone while the phone was away. */
sealed interface CardEnd {
    data class Resolved(
        val outcome: ApprovalOutcome,
    ) : CardEnd

    data object ClosedWhileAway : CardEnd
}

/** The owner's answer on its way: which button, and the outbox item that carries its route. */
data class LiveAnswer(
    val approve: Boolean,
    val clientMsgId: String,
)

/**
 * An approval card as the chat remembers it (design sections 8.4 and 13.5): what the session told of it, never
 * its token; [ttlS] the seconds it first came with, which "no answer in {ttl} s" names; when it expires on the
 * monotonic clock, from its latest sighting, a replay's time left included; when it first came, and the newest
 * row then, after which it stands; the owner's [answer] while one is on its way; and how it [ended], none
 * while it waits.
 */
data class LiveApproval(
    val approvalId: String,
    val kind: String,
    val text: String,
    val detail: String?,
    val ttlS: Int,
    val expiresAtMono: Long,
    val shownWall: Long,
    val afterSeq: ULong,
    val answer: LiveAnswer? = null,
    val ended: CardEnd? = null,
)

/** [approvals] after [event], which came [at]; any event that is no card's leaves them as they were. */
internal fun approvalsAfter(
    approvals: List<LiveApproval>,
    event: SessionEvent,
    at: Moment,
): List<LiveApproval> =
    when (event) {
        is SessionEvent.Approval -> shown(approvals, event, at)
        is SessionEvent.ApprovalResolved -> approvals.ending(event.approvalId, CardEnd.Resolved(event.outcome))
        is SessionEvent.ApprovalClosedWhileAway -> approvals.ending(event.approvalId, CardEnd.ClosedWhileAway)
        is SessionEvent.ApprovalAnswered -> approvals.answered(event)
        is SessionEvent.RequestFailed -> approvals.refused(event.clientMsgId)
        else -> approvals
    }

/**
 * A card shown, or replayed in place by its id with the time it has left (PROTOCOL.md "Approvals"): it keeps
 * its place, its first `ttl_s` and the answer on its way.
 */
private fun shown(
    approvals: List<LiveApproval>,
    event: SessionEvent.Approval,
    at: Moment,
): List<LiveApproval> {
    val expires = at.monoMs + event.ttlS * SECOND_MS
    val held = approvals.find { it.approvalId == event.approvalId }
    if (held != null) return approvals.map { if (it === held) it.copy(expiresAtMono = expires) else it }
    val card =
        LiveApproval(
            event.approvalId,
            event.kind,
            event.text,
            event.detail,
            event.ttlS,
            expires,
            at.wallMs,
            at.newestSeq,
        )
    return bounded(approvals + card)
}

private fun List<LiveApproval>.ending(
    approvalId: String,
    end: CardEnd,
): List<LiveApproval> = map { if (it.approvalId == approvalId && it.ended == null) it.copy(ended = end) else it }

private fun List<LiveApproval>.answered(event: SessionEvent.ApprovalAnswered): List<LiveApproval> =
    map {
        if (it.approvalId ==
            event.approvalId
        ) {
            it.copy(answer = LiveAnswer(event.approve, event.clientMsgId))
        } else {
            it
        }
    }

/** The answer [clientMsgId] was refused: its card may be answered again. */
private fun List<LiveApproval>.refused(clientMsgId: String): List<LiveApproval> =
    map { if (it.answer?.clientMsgId == clientMsgId) it.copy(answer = null) else it }

/** At most [MAX_REMEMBERED_APPROVALS]: the oldest that ended goes first, and only then a waiting one. */
private fun bounded(approvals: List<LiveApproval>): List<LiveApproval> {
    if (approvals.size <= MAX_REMEMBERED_APPROVALS) return approvals
    val oldestEnded = approvals.indexOfFirst { it.ended != null }
    val dropped = if (oldestEnded >= 0) oldestEnded else 0
    return approvals.filterIndexed { index, _ -> index != dropped }
}
