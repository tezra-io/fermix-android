package io.tezra.fermix.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The offline search's full-text query, built from the terms the index's own tokenizer makes of each word
 * the owner typed: every word a quoted phrase of its terms, its last term a prefix.
 */
class FtsMatchTest {
    @Test
    fun `each word is a quoted phrase of its terms, the last a prefix, and all of them must match`() {
        assertEquals("\"main*\"", ftsMatch(listOf(listOf("main"))))
        assertEquals("\"cafe*\" \"street*\"", ftsMatch(listOf(listOf("cafe"), listOf("street"))))
        assertEquals("\"q a*\"", ftsMatch(listOf(listOf("q", "a"))))
    }

    @Test
    fun `a word the tokenizer makes no term of is left out, so it hides nothing the others find`() {
        assertEquals("\"hello*\"", ftsMatch(listOf(listOf("hello"), emptyList())))
    }

    @Test
    fun `a query with no term is none`() {
        assertNull(ftsMatch(emptyList()))
        assertNull(ftsMatch(listOf(emptyList(), emptyList())))
    }

    @Test
    fun `a term that could leave its quotes or end the phrase is refused`() {
        listOf("a\"b", "a*", "a b", "").forEach { term ->
            assertThrows<IllegalArgumentException> { ftsMatch(listOf(listOf(term))) }
        }
    }
}
