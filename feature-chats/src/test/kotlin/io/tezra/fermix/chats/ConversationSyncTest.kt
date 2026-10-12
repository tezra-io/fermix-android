package io.tezra.fermix.chats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** When the owner set a photo as a row's avatar. */
private const val PICTURED_AT = 1_790_000_000_000L

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
    private val second = sample(2, tint = "Sky", nickname = "Dev")

    @Test
    fun `a row's conversation is made with it, under its instance and profile, tinted as the row`() {
        val surface = FakeSurface()
        val sync = ConversationSync(surface)
        sync.sync(listOf(first), emptyMap())
        val made = Conversation(first.id, "main", HOST, null, dev = false, "Slate", "Open")
        assertEquals(listOf(made), surface.published)
        assertEquals(setOf("${first.id}:main"), surface.present)
        sync.sync(listOf(first, second), emptyMap())
        // Named "Dev", it carries the DEV tag, as its row does (design section 9.2).
        assertEquals(Conversation(second.id, "main", "Dev", null, dev = true, "Sky", "Open"), surface.published.last())
        assertEquals(2, surface.published.size)
    }

    @Test
    fun `a conversation is named as its row, the agent's name unless it is Fermix and the DEV tag on a dev daemon's`() {
        val surface = FakeSurface()
        val sync = ConversationSync(surface)
        // The canon's production and dev daemons on one Mac, both titled suj-mbp.
        val dev = sample(3, profile = "fermix-dev", tint = "Sky")
        sync.sync(listOf(first, dev), mapOf(first.id to "Juno", dev.id to "Fermix"))
        assertEquals(
            listOf(
                Conversation(first.id, "main", HOST, "Juno", dev = false, "Slate", "Open"),
                Conversation(dev.id, "main", HOST, null, dev = true, "Sky", "Open"),
            ),
            surface.published,
        )
        // The agent renamed on the computer: its conversation is published again under the new name.
        sync.sync(listOf(first, dev), mapOf(first.id to "Ada", dev.id to "Fermix"))
        val renamed = Conversation(first.id, "main", HOST, "Ada", dev = false, "Slate", "Open")
        assertEquals(renamed, surface.published.last())
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
    fun `a conversation carries its row's picture, and is published again only as the picture or the tint changes`() {
        val surface = FakeSurface()
        val sync = ConversationSync(surface)
        val pictured = first.copy(pictureAt = PICTURED_AT)
        sync.sync(listOf(first), emptyMap())
        sync.sync(listOf(pictured), emptyMap())
        val expected = Conversation(first.id, "main", HOST, null, dev = false, "Slate", "Open", pictureAt = PICTURED_AT)
        assertEquals(expected, surface.published.last())
        sync.sync(listOf(pictured), emptyMap())
        assertEquals(2, surface.published.size)
        sync.sync(listOf(pictured.copy(tint = "Amber")), emptyMap())
        assertEquals("Amber", surface.published.last().tint)
        sync.sync(listOf(pictured.copy(tint = "Amber", pictureAt = null)), emptyMap())
        assertEquals(null, surface.published.last().pictureAt)
        assertEquals(4, surface.published.size)
    }

    @Test
    fun `a conversation is published again when its eyes change, and only then`() {
        val surface = FakeSurface()
        val sync = ConversationSync(surface)
        sync.sync(listOf(first, second), emptyMap())
        val winking = first.copy(eyes = "Wink")
        sync.sync(listOf(winking, second), emptyMap())
        assertEquals(Conversation(first.id, "main", HOST, null, dev = false, "Slate", "Wink"), surface.published.last())
        assertEquals(3, surface.published.size)
        sync.sync(listOf(winking, second), emptyMap())
        assertEquals(3, surface.published.size)
        sync.sync(listOf(winking.copy(eyes = "Open"), second), emptyMap())
        assertEquals("Open", surface.published.last().eyes)
        assertEquals(4, surface.published.size)
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
