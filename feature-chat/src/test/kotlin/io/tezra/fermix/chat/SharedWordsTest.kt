package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.encodeClientEvent
import io.tezra.fermix.session.MAX_ATTACHMENTS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** The widest `msg` the composer's words can go in: ten attachments, a `retry_of`, the largest seq. */
private fun widest(words: String): ByteArray {
    val id = { UUID.randomUUID().toString() }
    val msg = ClientEvent.Msg(id(), PROFILE, words, List(MAX_ATTACHMENTS) { id() }, retryOf = id())
    return encodeClientEvent(2, ULong.MAX_VALUE, msg)
}

/**
 * A share's words at the end of the composer's draft (design section 13.6), held to what one `msg` carries: its
 * header is at most 4,096 bytes (PROTOCOL.md), and no client event is split into parts.
 */
class SharedWordsTest {
    @Test
    fun `words land as they are, after the draft on a line of their own`() {
        assertEquals("look at this", withSharedWords("", "look at this", PROFILE))
        assertEquals("draft\nlook at this", withSharedWords("draft", "look at this", PROFILE))
        assertEquals("draft\nlook", withSharedWords("draft\n", "look", PROFILE))
    }

    @Test
    fun `words past what a msg carries are cut to the most that still goes, on a character's edge`() {
        for (unit in listOf("a", "\"", "\u0001", "é", "😀", "\n")) {
            val words = unit.repeat(MAX_SHARED_CHARS)
            val landed = withSharedWords("draft", words, PROFILE)
            assertTrue(landed.length < "draft\n$words".length, "nothing was cut of ${unit.codePointAt(0)}")
            // The cut words go, and one character more would not.
            widest(landed)
            val more = landed + unit
            assertThrows(ProtocolException::class.java) { widest(more) }
            assertTrue(!landed.last().isHighSurrogate(), "cut inside a character")
            assertEquals("draft\n" + words.take(landed.length - "draft\n".length), landed)
        }
    }

    @Test
    fun `a draft that fills a msg already takes no words, and stays as it was`() {
        val draft = "x".repeat(MAX_SHARED_CHARS)
        assertEquals(draft, withSharedWords(draft, "more", PROFILE))
    }

    @Test
    fun `words that fit whole are never cut`() {
        val words = "y".repeat(1_000)
        assertEquals(words, withSharedWords("", words, PROFILE))
        widest(words)
    }
}
