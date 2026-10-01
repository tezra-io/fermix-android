package io.tezra.fermix.session

/** What the app did with a row it was handed. */
enum class Announcement {
    /** The row landed in the chat on screen: announced, and read at once. */
    ON_SCREEN,

    /** The app posted the row's notification. */
    NOTIFIED,

    /**
     * The owner knows of the row without being told: their own message, or a row the notified set
     * already holds. The daemon never pushes the owner's own message.
     */
    ALREADY_KNOWN,

    /** The owner has not been told of the row. It is never acked (tla/specs/mobile_push, PUSH-2). */
    NOT_ANNOUNCED,
}

/**
 * The app's side of every row a session applies at its cursor: the rows new to this phone, live or
 * from a forward or the newest page, one call per row, in timeline order, the next row only after
 * this one returns. Older pages are not new and never come here: their rows reach the cache through
 * [SessionEvent.OlderLoaded]. The app persists the row, then shows it in the chat on screen or posts
 * its notification when its chat is not on screen, reading the notified set (design section 10,
 * "Lifecycle on the phone"), and says which. It does all of that in one go (onboarding gotcha 14): the
 * session acks the row only on the answer, and records it as applied only then, so a row whose call
 * never returns, the app frozen or killed before it announced the row, is pulled again on the next
 * connection. The call may use the session, [Session.markRead] for a row it shows read, or
 * [Session.close]; a call that ends the session, close or suspend, ends this call with it.
 */
fun interface Announcer {
    suspend fun announce(row: TimelineRow): Announcement
}
