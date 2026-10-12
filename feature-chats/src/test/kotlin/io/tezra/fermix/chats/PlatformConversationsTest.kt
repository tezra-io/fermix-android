package io.tezra.fermix.chats

import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import io.tezra.fermix.data.AvatarPictures
import io.tezra.fermix.design.AVATAR_EYE_INK
import io.tezra.fermix.instance.tintColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A picture's colour, which no tint and no ink is. */
private const val MAGENTA = 0xFFFF00FF.toInt()

/** The picture's side as AvatarPictures would decode it. */
private const val PICTURE_PX = 64

/** An adaptive icon's 18 dp at each side outside the 72 the launcher shows, of its 108. */
private const val SAFE_INSET = 18f / 108f

/**
 * A conversation as the phone holds it (design sections 9.1 and 13.6): a shortcut carrying the share
 * category, so the share sheet offers it as a Direct Share target whose share comes to the app's share entry. On
 * Robolectric, for the platform's ShortcutManager.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformConversationsTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `a published conversation is a shortcut in the share category`() {
        val conversation =
            Conversation("ab".repeat(32), "main", HOST, agent = null, dev = false, tint = "Slate", eyes = "Open")
        val pictures = AvatarPictures(File(app.noBackupFilesDir, "avatars"))
        val surface =
            PlatformConversations(app, pictures, { _, fault -> throw AssertionError("logged", fault) }) {
                Intent(Intent.ACTION_VIEW, Uri.parse("fermix://chat/x/main"))
            }
        surface.publish(conversation)
        val shortcut =
            app
                .getSystemService(ShortcutManager::class.java)
                .getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC)
                .single()
        assertEquals(conversation.id, shortcut.id)
        assertEquals(setOf(SHARE_CATEGORY), shortcut.categories)
    }

    // The eyes' line runs through the icon's centre, so its middle row crosses both eyes: two runs of the mark's ink,
    // one each side of the centre, inside the 72 dp a mask shows, the centre between them and the edges the tint.
    @Test
    fun `a conversation's icon is its avatar, the tint with its eyes across the 72 dp a mask shows`() {
        val icon = conversationAvatar("Amber", "Open", app.resources.displayMetrics.density, picture = null)
        val side = icon.width
        val tint = tintColor("Amber").toArgb()
        val ink = AVATAR_EYE_INK.toArgb()
        val middle = side / 2
        assertEquals(tint, icon.getPixel(middle, middle))
        assertEquals(tint, icon.getPixel(0, 0))
        assertEquals(tint, icon.getPixel(0, middle))
        val inked = (0 until side).filter { column -> icon.getPixel(column, middle) == ink }
        val inset = (side * SAFE_INSET).toInt()
        assertTrue("the ink along the middle row: $inked", inked.isNotEmpty())
        assertTrue(inked.first() > inset && inked.last() < side - inset)
        assertTrue(inked.any { it < middle } && inked.any { it > middle })
        assertTrue("the eyes are drawn whole, not a few pixels", inked.size > side / 20)
    }

    @Test
    fun `a conversation with a picture has it for its icon, across the adaptive icon's 72 dp, over the tint`() {
        val density = app.resources.displayMetrics.density
        val picture = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888).apply { eraseColor(MAGENTA) }
        val icon = conversationAvatar("Amber", "Open", density, picture)
        val side = icon.width
        // The adaptive icon's 108 dp, of which the launcher shows the 72 at its centre: the picture fills those.
        val inset = (side * SAFE_INSET).toInt()
        assertEquals(MAGENTA, icon.getPixel(side / 2, side / 2))
        assertEquals(MAGENTA, icon.getPixel(inset + 2, side / 2))
        assertEquals(MAGENTA, icon.getPixel(side - inset - 3, side - inset - 3))
        assertEquals(tintColor("Amber").toArgb(), icon.getPixel(inset - 2, side / 2))
        assertEquals(tintColor("Amber").toArgb(), icon.getPixel(0, 0))
        val ink = AVATAR_EYE_INK.toArgb()
        assertTrue("no eye lies over the picture", (0 until side).none { icon.getPixel(it, side / 2) == ink })
    }

    @Test
    fun `a published icon reads its picture from the avatars, and an unreadable one is the tint, logged`() {
        val logged = mutableListOf<String>()
        val pictures = AvatarPictures(File(app.noBackupFilesDir, "avatars"))
        val id = "ab".repeat(32)
        assertTrue(checkNotNull(pictures.fileOf(id).parentFile).mkdirs())
        pictures.fileOf(id).writeText("not a JPEG")
        val surface =
            PlatformConversations(app, pictures, { message, _ -> logged += message }) {
                Intent(Intent.ACTION_VIEW, Uri.parse("fermix://chat/x/main"))
            }
        val pictured = Conversation(id, "main", HOST, null, dev = false, tint = "Slate", eyes = "Open", pictureAt = 1L)
        surface.publish(pictured)
        assertEquals(1, logged.size)
        val shortcut =
            app
                .getSystemService(ShortcutManager::class.java)
                .getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC)
                .single()
        assertEquals("$id:main", shortcut.id)
    }
}
