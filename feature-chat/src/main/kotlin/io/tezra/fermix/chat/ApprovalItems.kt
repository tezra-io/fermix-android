package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ApprovalOutcome

/** What an approval card became (design section 13.9's receipt lines). */
enum class Receipt { APPROVED, DENIED, EXPIRED, CLOSED }

/** Which of the canon's cards: the sandbox's shield, the soul's pencil, or another kind, shown by its own word. */
enum class CardKind { SANDBOX, SOUL, OTHER }

/**
 * An approval card as the list draws it (design section 13.5, "Approval poll"): its kind, [text] and [detail],
 * the seconds it came with, when it expires on the monotonic clock, whether the owner's answer is on its way,
 * and its [receipt] once the daemon or the reconnect ended it. A waiting card whose time ran out on the clock
 * reads as expired at once, whatever the screen drew before (gotcha 18): [receiptAt] says so.
 */
data class ShownApproval(
    val approvalId: String,
    val kind: CardKind,
    val kindWord: String,
    val text: String,
    val detail: String?,
    val ttlS: Int,
    val expiresAtMono: Long,
    val answering: Boolean,
    val receipt: Receipt?,
) {
    /** The receipt at [nowMono]: the daemon's word, or expired once the card's time ran out; none while it waits. */
    fun receiptAt(nowMono: Long): Receipt? = receipt ?: Receipt.EXPIRED.takeIf { nowMono >= expiresAtMono }

    /** Whole seconds left at [nowMono], rounded up as the daemon rounds a replay's (PROTOCOL.md "Approvals"). */
    fun secondsLeft(nowMono: Long): Int =
        ((expiresAtMono - nowMono).coerceAtLeast(0L) + SECOND_MS - 1).div(SECOND_MS).toInt()

    /** Whether Approve and Deny take a press at [nowMono]: the card waits, and no answer is on its way. */
    fun answerable(nowMono: Long): Boolean = receiptAt(nowMono) == null && !answering
}

private const val SECOND_MS = 1_000L

/** The cards of [live], each after the newest row as it first came, at its first sighting's time. */
internal fun approvalItems(live: ChatLive): List<Pair<Placed, Long?>> =
    live.approvals.map { card ->
        val item = ChatItem.Approval("approval:${card.approvalId}", shownOf(card))
        Placed(card.afterSeq, 1, item) to card.shownWall
    }

internal fun shownOf(card: LiveApproval): ShownApproval =
    ShownApproval(
        approvalId = card.approvalId,
        kind = kindOf(card.kind),
        kindWord = card.kind,
        text = card.text,
        detail = card.detail,
        ttlS = card.ttlS,
        expiresAtMono = card.expiresAtMono,
        answering = card.answer != null,
        receipt = receiptOf(card.ended),
    )

private fun kindOf(kind: String): CardKind =
    when (kind) {
        "sandbox" -> CardKind.SANDBOX
        "soul" -> CardKind.SOUL
        else -> CardKind.OTHER
    }

private fun receiptOf(end: CardEnd?): Receipt? =
    when (end) {
        null -> {
            null
        }

        CardEnd.ClosedWhileAway -> {
            Receipt.CLOSED
        }

        is CardEnd.Resolved -> {
            when (end.outcome) {
                ApprovalOutcome.APPROVED -> Receipt.APPROVED
                ApprovalOutcome.DENIED -> Receipt.DENIED
                ApprovalOutcome.EXPIRED -> Receipt.EXPIRED
            }
        }
    }
