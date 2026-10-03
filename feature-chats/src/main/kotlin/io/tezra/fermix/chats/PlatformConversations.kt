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
import android.graphics.Paint
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import io.tezra.fermix.instance.tintColor

// An adaptive icon's 108 dp, of which the inner 72 dp always shows: the canon's avatar, a full tint with the
// mark in white, its two dots a sixth of the visible part across, 0.28 of a dot apart, the second at 62 %.
private const val ICON_DP = 108
private const val VISIBLE_DP = 72f
private const val MARK_DOT_SHARE = 1f / 6f
private const val MARK_GAP = 0.28f
private const val SECOND_DOT_ALPHA = 0.62f
private const val WHITE = 0xFFFFFFFF.toInt()
private const val OPAQUE = 255

/**
 * The phone's conversations (design section 9.1): a long-lived conversation shortcut per (instance,
 * profile), its icon the tinted avatar, opening [intentFor]'s intent; and a notification channel of the
 * same id, named as the row reads ([conversationName]), at high importance, as a chat's messages are.
 * Removal takes both away.
 */
class PlatformConversations(
    private val context: Context,
    private val intentFor: (Conversation) -> Intent,
) : ConversationSurface {
    private val shortcuts = context.getSystemService(ShortcutManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

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
                .setIcon(Icon.createWithAdaptiveBitmap(avatar(conversation.tint)))
                .setIntent(intentFor(conversation))
                .build()
        shortcuts.pushDynamicShortcut(shortcut)
    }

    override fun remove(ids: Set<String>) {
        shortcuts.removeLongLivedShortcuts(ids.toList())
        ids.forEach(notifications::deleteNotificationChannel)
    }

    /** The avatar as an adaptive icon's bitmap: the tint to the edges, the mark in the visible middle. */
    private fun avatar(tint: String): Bitmap {
        val density = context.resources.displayMetrics.density
        val side = (ICON_DP * density).toInt()
        val bitmap = createBitmap(side, side)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tintColor(tint).toArgb())
        val dot = VISIBLE_DP * density * MARK_DOT_SHARE
        val apart = dot * (1f + MARK_GAP) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
        canvas.drawCircle(side / 2f - apart, side / 2f, dot / 2f, paint)
        paint.alpha = (SECOND_DOT_ALPHA * OPAQUE).toInt()
        canvas.drawCircle(side / 2f + apart, side / 2f, dot / 2f, paint)
        return bitmap
    }
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
