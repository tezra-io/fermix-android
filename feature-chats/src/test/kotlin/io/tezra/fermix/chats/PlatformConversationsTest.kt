package io.tezra.fermix.chats

import android.content.Intent
import android.content.pm.ShortcutManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A conversation as the phone holds it (design sections 9.1 and 13.6): a shortcut carrying the share
 * category, so the share sheet offers it as a Direct Share target whose share comes to the app's share entry. On
 * Robolectric, for the platform's ShortcutManager.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformConversationsTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `a published conversation is a shortcut in the share category`() {
        val conversation = Conversation("ab".repeat(32), "main", HOST, agent = null, dev = false, tint = "Slate")
        val surface = PlatformConversations(app) { Intent(Intent.ACTION_VIEW, Uri.parse("fermix://chat/x/main")) }
        surface.publish(conversation)
        val shortcut =
            app
                .getSystemService(ShortcutManager::class.java)
                .getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC)
                .single()
        assertEquals(conversation.id, shortcut.id)
        assertEquals(setOf(SHARE_CATEGORY), shortcut.categories)
    }
}
