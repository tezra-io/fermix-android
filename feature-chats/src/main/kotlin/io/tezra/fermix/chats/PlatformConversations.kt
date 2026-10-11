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
import android.graphics.Rect
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import io.tezra.fermix.data.AvatarPictures
import io.tezra.fermix.instance.tintColor
import java.io.IOException

// An adaptive icon's 108 dp: the avatar as the M51 update's reference player draws the Chats row's, the tint alone,
// or the owner's photo across the 72 dp at its centre that a launcher's mask shows, over the tint.
private const val ICON_DP = 108
private const val SHOWN_DP = 72

/**
 * The phone's conversations (design sections 9.1 and 13.10, item 11): a long-lived conversation shortcut per
 * (instance, profile), its icon the avatar, the tint or the owner's photo read from [pictures], opening [intentFor]'s
 * intent, and a Direct Share target in the share sheet ([SHARE_CATEGORY]), whose share comes to the app's share entry
 * naming the shortcut; and a notification channel of the same id, named as the row reads ([conversationName]), at
 * high importance, as a chat's messages are. Removal takes both away. A photo that cannot be read is told to [log],
 * and the icon is the tint. It reads files: ConversationSync runs it off the main thread.
 */
class PlatformConversations(
    private val context: Context,
    private val pictures: AvatarPictures,
    private val log: (String, Throwable?) -> Unit,
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
        val picture = conversation.pictureAt?.let { pictureOf(conversation.instanceId) }
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
                .setIcon(Icon.createWithAdaptiveBitmap(conversationAvatar(conversation.tint, density, picture)))
                .setIntent(intentFor(conversation))
                .setCategories(setOf(SHARE_CATEGORY))
                .build()
        shortcuts.pushDynamicShortcut(shortcut)
    }

    override fun remove(ids: Set<String>) {
        shortcuts.removeLongLivedShortcuts(ids.toList())
        ids.forEach(notifications::deleteNotificationChannel)
    }

    /** [instanceId]'s photo at the size the icon shows it, none when it has no file or cannot be read (logged). */
    private fun pictureOf(instanceId: String): Bitmap? =
        try {
            pictures.read(instanceId, (SHOWN_DP * density).toInt())
        } catch (unreadable: IOException) {
            log("A conversation's picture could not be read; its icon is the tint", unreadable)
            null
        }
}

/**
 * The avatar as an adaptive icon's bitmap at [density]: the tint to the edges, with nothing on it, or with [picture]
 * across the [SHOWN_DP] at its centre, which a launcher's mask shows of its [ICON_DP], so the whole photo is seen
 * round as the Chats row draws it.
 */
internal fun conversationAvatar(
    tint: String,
    density: Float,
    picture: Bitmap?,
): Bitmap {
    val side = (ICON_DP * density).toInt()
    val bitmap = createBitmap(side, side)
    val canvas = Canvas(bitmap)
    canvas.drawColor(tintColor(tint).toArgb())
    if (picture != null) {
        val inset = (side - (SHOWN_DP * density).toInt()) / 2
        canvas.drawBitmap(
            picture,
            null,
            Rect(inset, inset, side - inset, side - inset),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }
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
