package io.tezra.fermix

import android.content.res.Resources

/** The notifications' words from design section 13.9, an instance's name a format argument of each. */
class NotificationCopy(
    val appName: String,
    val newMessage: String,
    val needsApproval: (instance: String) -> String,
    val approvalExpired: (instance: String) -> String,
    val replyDidNotFinish: (instance: String) -> String,
) {
    companion object {
        fun of(resources: Resources): NotificationCopy =
            NotificationCopy(
                appName = resources.getString(R.string.app_name),
                newMessage = resources.getString(R.string.notification_new_message),
                needsApproval = { resources.getString(R.string.notification_approval, it) },
                approvalExpired = { resources.getString(R.string.notification_approval_expired, it) },
                replyDidNotFinish = { resources.getString(R.string.notification_turn_failed, it) },
            )
    }
}

/**
 * The conversation a notification belongs to, every part of it from the instance record and its chat, never
 * from a push's plaintext: [instanceId] and [profileId] name the chat its tap opens, [conversationId] is its
 * channel's and its shortcut's id (ConversationSync), [title] the instance's name as its row reads, and
 * [agent] the host-owned agent's name, the messages' sender.
 */
data class ConversationFacts(
    val instanceId: String,
    val profileId: String,
    val conversationId: String,
    val title: String,
    val agent: String,
)

/** One line of a conversation's notification: a row's [serverSeq], its [words], and when it came. */
data class NotifiedLine(
    val serverSeq: ULong,
    val words: String,
    val atMs: Long,
)

/** Which notification of a conversation, by the id of its kind (design section 10, "Lifecycle on the phone"). */
sealed interface NotificationKey {
    /** The conversation's one `MessagingStyle` notification, built from the notified set. */
    data object Messages : NotificationKey

    data class Approval(
        val approvalId: String,
    ) : NotificationKey

    data class TurnFailed(
        val turnId: String,
    ) : NotificationKey

    /** What is shown when a push is opened but cannot be read, or is of a kind this app does not know. */
    data object Generic : NotificationKey
}

/**
 * A notification as the app posts it, before Android's builder, which only copies it: the tests read this.
 * [chat] is the conversation it belongs to, none for the generic notification of a push no key opened, which
 * has no instance, no tint and no conversation, and opens the app. [lines] are the `MessagingStyle` messages,
 * written only with previews on and the app lock off; otherwise the notification is [title] and [text], or
 * [title] alone. [timeoutAfterMs] ends an approval's notification at its expiry. [onlyAlertOnce] keeps a post
 * from alerting again while its notification shows (`setOnlyAlertOnce`): a rebuild that adds no row, and the
 * generic notification, which a sender who is no paired Fermix can repeat; a post that adds a row alerts.
 */
data class NotificationContent(
    val chat: ConversationFacts?,
    val key: NotificationKey,
    val title: String,
    val text: String? = null,
    val lines: List<NotifiedLine> = emptyList(),
    val timeoutAfterMs: Long? = null,
    val onlyAlertOnce: Boolean = false,
)

/**
 * A conversation's message notification (design section 10, D22; section 13.9): with previews on, the unread
 * rows the phone announced, each with its words, the agent the sender; with previews off, the instance's name
 * and "New message"; and while the app lock is on, "New message" alone, whatever the previews setting, its
 * words never written, as the lock's title-only rule asks.
 */
fun messagesNotification(
    chat: ConversationFacts,
    lines: List<NotifiedLine>,
    previews: Boolean,
    locked: Boolean,
    copy: NotificationCopy,
): NotificationContent {
    require(lines.isNotEmpty()) { "a conversation's notification holds a row" }
    return when {
        locked -> NotificationContent(chat, NotificationKey.Messages, title = copy.newMessage)
        !previews -> NotificationContent(chat, NotificationKey.Messages, title = chat.title, text = copy.newMessage)
        else -> NotificationContent(chat, NotificationKey.Messages, title = chat.title, lines = lines)
    }
}

/**
 * An approval's notification, keyed by its id (design section 10): "{instance} needs your approval", which
 * times out at [expiresAtMs]; one that arrives at or after it, at [nowMs], is "An approval on {instance}
 * expired", never dropped (onboarding gotcha 18). Both are title-only, so the lock changes neither. An id is
 * one character or more, as the wire and the notified set take it: one of spaces is still an id.
 */
fun approvalNotification(
    chat: ConversationFacts,
    approvalId: String,
    expiresAtMs: Long,
    nowMs: Long,
    copy: NotificationCopy,
): NotificationContent {
    require(approvalId.isNotEmpty()) { "an approval's notification is keyed by its id" }
    val key = NotificationKey.Approval(approvalId)
    if (nowMs >= expiresAtMs) return NotificationContent(chat, key, title = copy.approvalExpired(chat.title))
    return NotificationContent(chat, key, title = copy.needsApproval(chat.title), timeoutAfterMs = expiresAtMs - nowMs)
}

/**
 * A failed turn's notification, keyed by its id as an approval's is: "{instance}: the reply didn't finish",
 * title-only.
 */
fun turnFailedNotification(
    chat: ConversationFacts,
    turnId: String,
    copy: NotificationCopy,
): NotificationContent {
    require(turnId.isNotEmpty()) { "a failed turn's notification is keyed by its id" }
    return NotificationContent(chat, NotificationKey.TurnFailed(turnId), copy.replyDidNotFinish(chat.title))
}

/**
 * The generic notification (onboarding gotcha 10: never nothing): "New message" under the instance's name when
 * a key opened the push, or under the app's when none did; while the app lock is on, "New message" alone, as
 * every notification is title-only then. It alerts once while it shows, however often it is posted again.
 */
fun genericNotification(
    chat: ConversationFacts?,
    locked: Boolean,
    copy: NotificationCopy,
): NotificationContent {
    val key = NotificationKey.Generic
    if (locked) return NotificationContent(chat, key, title = copy.newMessage, onlyAlertOnce = true)
    val title = chat?.title ?: copy.appName
    return NotificationContent(chat, key, title = title, text = copy.newMessage, onlyAlertOnce = true)
}
