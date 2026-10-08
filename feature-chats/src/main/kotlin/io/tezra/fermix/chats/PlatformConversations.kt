package io.tezra.fermix.chats

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import io.tezra.fermix.instance.tintColor

// An adaptive icon's 108 dp: the avatar as the M51 update's reference player draws the Chats row's, the tint alone.
private const val ICON_DP = 108

/**
 * The phone's conversations (design sections 9.1 and 13.10, item 11): a long-lived conversation shortcut per
 * (instance, profile), its icon the tinted avatar, opening [intentFor]'s intent, and a Direct Share target in the
 * share sheet ([SHARE_CATEGORY]), whose share comes to the app's share entry naming the shortcut; and a notification
 * channel of the same id, named as the row reads ([conversationName]), at high importance, as a chat's messages are.
 * Removal takes both away.
 */
class PlatformConversations(
    private val context: Context,
    private val intentFor: (Conversation) -> Intent,
) : ConversationSurface {
    private val shortcuts = context.getSystemService(ShortcutManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val density = context.resources.displayMetrics.density

    override fun published(): Set<String> {
        val dynamic = shortcuts.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC or ShortcutManager.FLAG_MATCH_CACHED)
        val channels = notifications.notificationChannels.map { it.id }
        return (dynamic.map { it.id } + channels).filter { Conversation.SEPARATOR in it }.toSet()
    }

    override fun publish(conversation: Conversation) {
        val name = conversationName(context.resources, conversation)
        notifications.createNotificationChannel(
            NotificationChannel(conversation.id, name, NotificationManager.IMPORTANCE_HIGH),
        )
        val person =
            Person
                .Builder()
                .setName(name)
                .setKey(conversation.id)
                .build()
        val shortcut =
            ShortcutInfo
                .Builder(context, conversation.id)
                .setShortLabel(name)
                .setLongLived(true)
                .setPerson(person)
                .setIcon(Icon.createWithAdaptiveBitmap(conversationAvatar(conversation.tint, density)))
                .setIntent(intentFor(conversation))
                .setCategories(setOf(SHARE_CATEGORY))
                .build()
        shortcuts.pushDynamicShortcut(shortcut)
    }

    override fun remove(ids: Set<String>) {
        shortcuts.removeLongLivedShortcuts(ids.toList())
        ids.forEach(notifications::deleteNotificationChannel)
    }
}

/** The avatar as an adaptive icon's bitmap at [density]: the tint to the edges, nothing on it. */
internal fun conversationAvatar(
    tint: String,
    density: Float,
): Bitmap {
    val side = (ICON_DP * density).toInt()
    val bitmap = createBitmap(side, side)
    Canvas(bitmap).drawColor(tintColor(tint).toArgb())
    return bitmap
}

/**
 * [conversation]'s name as its row reads: the title, with the agent's name when it is not "Fermix", and the
 * DEV tag after it on a dev daemon's (design section 9.2).
 */
internal fun conversationName(
    resources: Resources,
    conversation: Conversation,
): String {
    val title = conversation.title
    val named = conversation.agent?.let { resources.getString(R.string.chats_title_with_agent, title, it) } ?: title
    if (!conversation.dev) return named
    return resources.getString(R.string.chats_conversation_dev, named, resources.getString(R.string.chats_dev_tag))
}
