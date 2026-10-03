package io.tezra.fermix.chats

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A conversation's name, its shortcut's and its channel's, reads as its row (design sections 9.2 and 13.10,
 * item 11), so the phone's settings and its share sheet tell two daemons of one computer apart. On
 * Robolectric, for the module's strings.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationNameTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    private fun named(
        agent: String?,
        dev: Boolean,
    ) = conversationName(resources, Conversation("ab".repeat(32), "main", HOST, agent, dev, "Slate"))

    @Test
    fun `a conversation carries the agent's name and the DEV tag as its row does`() {
        assertEquals(HOST, named(agent = null, dev = false))
        assertEquals("$HOST · Juno", named(agent = "Juno", dev = false))
        assertEquals("$HOST (DEV)", named(agent = null, dev = true))
        assertEquals("$HOST · Juno (DEV)", named(agent = "Juno", dev = true))
    }
}
