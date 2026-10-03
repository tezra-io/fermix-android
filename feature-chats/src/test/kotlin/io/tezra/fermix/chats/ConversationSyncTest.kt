package io.tezra.fermix.chats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Conversation shortcuts and channels follow the rows (design section 9.1). */
class ConversationSyncTest {
    /** The system's shortcuts and channels, as one set of ids, and every call made to it. */
    private class FakeSurface(
        initial: Set<String> = emptySet(),
    ) : ConversationSurface {
        val present = initial.toMutableSet()
        val published = mutableListOf<Conversation>()
        val removed = mutableListOf<Set<String>>()

        override fun published(): Set<String> = present.toSet()

        override fun publish(conversation: Conversation) {
            published += conversation
            present += conversation.id
        }

        override fun remove(ids: Set<String>) {
            removed += ids
            present -= ids
        }
    }

    private val first = sample(1, tint = "Slate")
    private val second = sample(2, tint = "Ocean", nickname = "Dev")

    @Test
    fun `a row's conversation is made with it, under its instance and profile, tinted as the row`() {
        val surface = FakeSurface()
        val sync = ConversationSync(surface)
        sync.sync(listOf(first), emptyMap())
        assertEquals(listOf(Conversation(first.id, "main", HOST, null, dev = false, "Slate")), surface.published)
        assertEquals(setOf("${first.id}:main"), surface.present)
        sync.sync(listOf(first, second), emptyMap())
        // Named "Dev", it carries the DEV tag, as its row does (design section 9.2).
        assertEquals(Conversation(second.id, "main", "Dev", null, dev = true, "Ocean"), surface.published.last())
        assertEquals(2, surface.published.size)
    }

    @Test
    fun `a conversation is named as its row, the agent's name unless it is Fermix and the DEV tag on a dev daemon's`() {
        val surface = FakeSurface()
        val sync = ConversationSync(surface)
        // The canon's production and dev daemons on one Mac, both titled suj-mbp.
        val dev = sample(3, profile = "fermix-dev", tint = "Ocean")
        sync.sync(listOf(first, dev), mapOf(first.id to "Juno", dev.id to "Fermix"))
        assertEquals(
            listOf(
                Conversation(first.id, "main", HOST, "Juno", dev = false, "Slate"),
                Conversation(dev.id, "main", HOST, null, dev = true, "Ocean"),
            ),
            surface.published,
        )
        // The agent renamed on the computer: its conversation is published again under the new name.
        sync.sync(listOf(first, dev), mapOf(first.id to "Ada", dev.id to "Fermix"))
        assertEquals(Conversation(first.id, "main", HOST, "Ada", dev = false, "Slate"), surface.published.last())
        assertEquals(3, surface.published.size)
    }

    @Test
    fun `an unpaired row's conversation goes, and a renamed one is published again`() {
        val surface = FakeSurface()
        val sync = ConversationSync(surface)
        sync.sync(listOf(first, second), emptyMap())
        sync.sync(listOf(second), emptyMap())
        assertEquals(listOf(setOf("${first.id}:main")), surface.removed)
        assertEquals(setOf("${second.id}:main"), surface.present)
        sync.sync(listOf(second.copy(nickname = "Studio")), emptyMap())
        assertEquals("Studio", surface.published.last().title)
        sync.sync(listOf(second.copy(nickname = "Studio")), emptyMap())
        assertEquals(3, surface.published.size)
    }

    @Test
    fun `the first sync after a start removes what a removal cut short left behind`() {
        val stale = "${"ab".repeat(32)}:main"
        val surface = FakeSurface(initial = setOf(stale, "${first.id}:main"))
        ConversationSync(surface).sync(listOf(first), emptyMap())
        assertEquals(listOf(setOf(stale)), surface.removed)
        assertEquals(setOf("${first.id}:main"), surface.present)
    }
}
