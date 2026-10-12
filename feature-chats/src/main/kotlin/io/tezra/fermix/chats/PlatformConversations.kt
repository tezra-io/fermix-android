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
import android.graphics.drawable.Icon
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.createBitmap
import io.tezra.fermix.data.AvatarPictures
import io.tezra.fermix.design.MarkEyePaths
import io.tezra.fermix.design.drawAvatarEyes
import io.tezra.fermix.design.eyeStyleOf
import io.tezra.fermix.instance.tintColor
import java.io.IOException

// An adaptive icon's 108 dp: the avatar as the Chats row draws it, the tint to the icon's edges with the mark's eyes
// across the 72 dp at its centre that a launcher's mask shows, or the owner's photo across those 72 dp instead.
private const val ICON_DP = 108
private const val SHOWN_DP = 72

/**
 * The phone's conversations (design sections 9.1 and 13.10, item 11): a long-lived conversation shortcut per
 * (instance, profile), its icon the avatar, the tint with its eyes or the owner's photo read from [pictures], opening
 * [intentFor]'s intent, and a Direct Share target in the share sheet ([SHARE_CATEGORY]), whose share comes to the app's
 * share entry naming the shortcut; and a notification channel of the same id, named as the row reads
 * ([conversationName]), at high importance, as a chat's messages are. Removal takes both away. A photo that cannot be
 * read is told to [log], and the icon is the tint with its eyes. It reads files: ConversationSync runs it off the main
 * thread.
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
        val icon = conversationAvatar(conversation.tint, conversation.eyes, density, picture)
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
                .setIcon(Icon.createWithAdaptiveBitmap(icon))
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
            log("A conversation's picture could not be read; its icon is the tint with its eyes", unreadable)
            null
        }
}

/**
 * The avatar as an adaptive icon's bitmap at [density], drawn once on the CPU through Compose's own drawing, as the
 * Chats row's avatar is: the [tint] to the edges, with the [eyes] across the [SHOWN_DP] at its centre, which a
 * launcher's mask shows of its [ICON_DP], or with [picture] across those instead, so the whole photo is seen round.
 */
internal fun conversationAvatar(
    tint: String,
    eyes: String,
    density: Float,
    picture: Bitmap?,
): Bitmap {
    val side = (ICON_DP * density).toInt()
    val inset = (side - (SHOWN_DP * density).toInt()) / 2
    val shown = side - 2 * inset
    val style = eyeStyleOf(eyes)
    val bitmap = createBitmap(side, side)
    val area = Size(side.toFloat(), side.toFloat())
    CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), area) {
        drawRect(tintColor(tint))
        translate(left = inset.toFloat(), top = inset.toFloat()) {
            if (picture == null) {
                drawAvatarEyes(style, Size(shown.toFloat(), shown.toFloat()), MarkEyePaths())
            } else {
                drawImage(picture.asImageBitmap(), dstSize = IntSize(shown, shown), filterQuality = FilterQuality.Low)
            }
        }
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
