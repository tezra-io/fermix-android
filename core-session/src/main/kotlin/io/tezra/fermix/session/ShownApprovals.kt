package io.tezra.fermix.session

/** The cards one session remembers; the daemon holds at most 64 pending (design R21). */
private const val MAX_SHOWN_APPROVALS = 64

/**
 * The approval cards this session has handed the app and not seen resolved, by `approval_id`, so a
 * reconnect can tell which of them the daemon no longer holds (design section 8.2, D23).
 */
internal class ShownApprovals {
    private val ids = LinkedHashSet<String>()

    /** A card was shown, or replayed in place; past the bound the oldest is forgotten. */
    fun shown(approvalId: String) {
        ids.remove(approvalId)
        ids.add(approvalId)
        if (ids.size > MAX_SHOWN_APPROVALS) ids.remove(ids.first())
    }

    fun resolved(approvalId: String) {
        ids.remove(approvalId)
    }

    /** The cards [pending] does not list, which closed while this phone was away; they are forgotten. */
    fun closedExcept(pending: List<String>): List<String> {
        val closed = ids.filter { it !in pending }
        ids.removeAll(closed.toSet())
        return closed
    }
}
