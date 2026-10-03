package io.tezra.fermix

import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.NotifiedEntry
import io.tezra.fermix.session.SessionEvent

/** Whether [instanceId]'s [profileId] chat is on screen now, whatever its list holds (OnScreenChats). */
fun interface ChatShowing {
    fun isOnScreen(
        instanceId: String,
        profileId: String,
    ): Boolean
}

/**
 * What posts an approval card's heads-up notification while its chat is off screen (design section 8.4, D23;
 * section 10): the notifications change (A4) brings the app's, and until then the app's posts none. It is
 * handed the card as the session typed it, its kind, words and time to live, never its route: the token stays
 * in the session.
 */
interface ApprovalNotifier {
    /** Whether an approval of [instanceId] can be notified now: its channel, its permission. */
    fun canNotify(instanceId: String): Boolean

    /** Posts [approval]'s notification, which the notified set has just taken. */
    fun notify(
        instanceId: String,
        approval: SessionEvent.Approval,
    )
}

/**
 * The seam between a session's approval cards and their notification (design section 8.4): a card that comes
 * while its chat is off screen, and can be notified, is put in the notified set at [now] and, only when that
 * added it, notified, so the card `hello` replays on each reconnect alerts once (NotifiedEntry.Approval). A
 * card its chat shows is seen there and never notified.
 */
class ApprovalAlerts(
    private val showing: ChatShowing,
    private val notifier: ApprovalNotifier,
    private val now: () -> Long,
) {
    /**
     * [approval] of [instanceId] arrived; [put] adds an entry to the profile's notified set, true when this
     * call added it. Whether it was notified.
     */
    suspend fun arrived(
        instanceId: String,
        approval: SessionEvent.Approval,
        put: suspend (NotifiedEntry, Long) -> Boolean,
    ): Boolean {
        require(instanceId.isNotBlank()) { "an approval names its instance" }
        val quiet = showing.isOnScreen(instanceId, MAIN_PROFILE) || !notifier.canNotify(instanceId)
        if (quiet || !put(NotifiedEntry.Approval(approval.approvalId), now())) return false
        notifier.notify(instanceId, approval)
        return true
    }
}
