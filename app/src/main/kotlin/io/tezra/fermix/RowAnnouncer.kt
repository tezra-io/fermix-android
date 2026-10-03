package io.tezra.fermix

import io.tezra.fermix.data.NotifiedEntry
import io.tezra.fermix.data.ProfileDatabase
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.Announcer
import io.tezra.fermix.session.TimelineRow

/** The role of the owner's own message on the wire: the daemon never pushes it (Announcer). */
private const val OWNER_ROLE = "user"

/**
 * Which chat shows its rows now, asked by the announcer as each row comes, once the row is kept (PUSH-2:
 * persisted, shown, then acked): whether [instanceId]'s [profileId] chat is on screen with row [serverSeq] in
 * its list, which it may wait for, at most a bound (OnScreenChats).
 */
fun interface ChatOnScreen {
    suspend fun shows(
        instanceId: String,
        profileId: String,
        serverSeq: ULong,
    ): Boolean
}

/**
 * What posts a row's notification (design section 10, "Lifecycle on the phone"): the notifications change
 * brings the app's, and until then the app's can post none, so no row is told of and none is acked.
 */
interface RowNotifier {
    /** Whether a row of [instanceId]'s [profileId] can be notified now: its channel, its permission. */
    fun canNotify(
        instanceId: String,
        profileId: String,
    ): Boolean

    /** Posts [row]'s notification, which the notified set has just taken. */
    fun notify(
        instanceId: String,
        profileId: String,
        row: TimelineRow,
    )
}

/**
 * The announcer of [instanceId]'s [profileId] (core-session's Announcer, design section 10): every row is
 * kept in the profile's timeline, opening [database] at the first; then the owner's own message is known
 * already, a row the chat on screen lists is shown there and read, and any other is put into the notified
 * set and notified, unless the set held it or it was read already, which is known; a row that can be
 * notified of in no way is not announced, so it is never acked and its push still comes
 * (tla/specs/mobile_push, PUSH-2). The notified set is put at [now], in Unix milliseconds.
 */
class RowAnnouncer(
    private val instanceId: String,
    private val profileId: String,
    private val database: Lazy<ProfileDatabase>,
    private val onScreen: ChatOnScreen,
    private val notifier: RowNotifier,
    private val now: () -> Long,
) : Announcer {
    init {
        require(instanceId.isNotBlank() && profileId.isNotBlank()) { "an announcer names its chat" }
    }

    override suspend fun announce(row: TimelineRow): Announcement {
        val profile = database.value
        profile.timeline().persist(row)
        return when {
            row.isOwners() -> Announcement.ALREADY_KNOWN
            onScreen.shows(instanceId, profileId, row.serverSeq) -> Announcement.ON_SCREEN
            !notifier.canNotify(instanceId, profileId) -> Announcement.NOT_ANNOUNCED
            !profile.notified().put(NotifiedEntry.Row(row.serverSeq), now()) -> Announcement.ALREADY_KNOWN
            else -> notified(row)
        }
    }

    private fun notified(row: TimelineRow): Announcement {
        notifier.notify(instanceId, profileId, row)
        return Announcement.NOTIFIED
    }
}

private fun TimelineRow.isOwners(): Boolean = this is TimelineRow.Message && message.role == OWNER_ROLE
