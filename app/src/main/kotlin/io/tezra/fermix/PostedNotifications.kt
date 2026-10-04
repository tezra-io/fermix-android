package io.tezra.fermix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent

/** Every notification of a push or a session is posted under this id, each kind and key told apart by its tag. */
internal const val POSTED_NOTIFICATION_ID = 10

/**
 * The channel of the generic notification no key opened (design section 10): it has no instance, so no
 * conversation's channel is its.
 */
internal const val APP_CHANNEL = "push"

/** The tag of [key]'s notification in the conversation [conversationId], or the app's when there is none. */
internal fun tagOf(
    conversationId: String?,
    key: NotificationKey,
): String {
    val kind =
        when (key) {
            NotificationKey.Messages -> "messages"
            is NotificationKey.Approval -> "approval/${key.approvalId}"
            is NotificationKey.TurnFailed -> "turn/${key.turnId}"
            NotificationKey.Generic -> "generic"
        }
    return "${conversationId ?: APP_CHANNEL}/$kind"
}

/**
 * The phone's side of the notifications: a [NotificationContent] as Android's notification, posted on its
 * conversation's channel with its conversation's shortcut (design section 13.10, item 11) and the tap that
 * opens its chat with the list beneath (section 13.4), alerting as the content says; whether a conversation's
 * notifications can show; and whether one of its notifications shows now. Every tap is an immutable
 * PendingIntent of the app's own activity, explicit, its chat named by the record's ids alone.
 */
class PostedNotifications(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /**
     * Whether a notification on [conversationId]'s channel can show: `POST_NOTIFICATIONS` granted and the app's
     * notifications on, and the channel not blocked in the system's settings. A channel not made yet is not
     * blocked: the records' sync makes it.
     */
    fun canShow(conversationId: String): Boolean {
        require(conversationId.isNotBlank()) { "a channel has an id" }
        val channel = manager.getNotificationChannel(conversationId)
        return manager.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun post(content: NotificationContent) {
        require(content.title.isNotBlank()) { "a notification has a title" }
        if (content.chat == null) appChannel()
        manager.notify(tagOf(content.chat?.conversationId, content.key), POSTED_NOTIFICATION_ID, notification(content))
    }

    fun cancel(
        conversationId: String,
        key: NotificationKey,
    ) {
        require(conversationId.isNotBlank()) { "a conversation has an id" }
        manager.cancel(tagOf(conversationId, key), POSTED_NOTIFICATION_ID)
    }

    /** Whether [key]'s notification of [conversationId] shows now, not dismissed nor cancelled. */
    fun isShowing(
        conversationId: String,
        key: NotificationKey,
    ): Boolean {
        require(conversationId.isNotBlank()) { "a conversation has an id" }
        val tag = tagOf(conversationId, key)
        return manager.activeNotifications.any { it.tag == tag && it.id == POSTED_NOTIFICATION_ID }
    }

    private fun notification(content: NotificationContent): Notification {
        val chat = content.chat
        val builder =
            Notification
                .Builder(context, chat?.conversationId ?: APP_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(content.title)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(content.onlyAlertOnce)
                .setAutoCancel(true)
                .setContentIntent(tap(chat))
        content.text?.let(builder::setContentText)
        content.timeoutAfterMs?.let(builder::setTimeoutAfter)
        chat?.let { builder.setShortcutId(it.conversationId) }
        if (chat != null && content.lines.isNotEmpty()) builder.setStyle(style(chat, content.lines))
        return builder.build()
    }

    /** The app's activity, opening [chat] when there is one: the deep link the conversation's shortcut opens too. */
    private fun tap(chat: ConversationFacts?): PendingIntent {
        val intent =
            chat?.let { chatIntent(context, it.instanceId, it.profileId) }
                ?: Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * The conversation's messages, its agent the sender of each, under the instance's name: a group
     * conversation's title is the one every Android release shows, and it tells two Fermixes apart.
     */
    private fun style(
        chat: ConversationFacts,
        lines: List<NotifiedLine>,
    ): Notification.MessagingStyle {
        val self = Person.Builder().setName(context.getString(R.string.notification_self)).build()
        val agent =
            Person
                .Builder()
                .setName(chat.agent)
                .setKey(chat.conversationId)
                .build()
        val style =
            Notification
                .MessagingStyle(self)
                .setConversationTitle(chat.title)
                .setGroupConversation(true)
        lines.forEach { style.addMessage(Notification.MessagingStyle.Message(it.words, it.atMs, agent)) }
        return style
    }

    /** The generic notification's channel, made at its first post. */
    private fun appChannel() {
        val name = context.getString(R.string.push_channel)
        manager.createNotificationChannel(NotificationChannel(APP_CHANNEL, name, NotificationManager.IMPORTANCE_HIGH))
    }
}
