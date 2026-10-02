package io.tezra.fermix.attest

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A generated key's chain as `pair_request` carries it: `cert_lengths`, and the DER back to back as the tail. */
class AttestedKeyTest {
    private val leaf = byteArrayOf(1, 2, 3)
    private val root = byteArrayOf(4, 5)

    @Test
    fun `the lengths split the tail back into the chain, leaf first`() {
        val key = AttestedKey("fermix.device.a.b", listOf(leaf, root))
        assertEquals(listOf(3, 2), key.certLengths)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), key.tail())
        assertEquals(listOf(leaf.toList(), root.toList()), key.chain().map { it.toList() })
    }

    @Test
    fun `the chain is held as a copy and handed out as one`() {
        val given = leaf.copyOf()
        val key = AttestedKey("fermix.device.a.b", listOf(given))
        given.fill(0)
        key.chain().first().fill(0)
        assertArrayEquals(leaf, key.tail())
    }

    @Test
    fun `a key has an alias and a chain`() {
        assertThrows<IllegalArgumentException> { AttestedKey(" ", listOf(leaf)) }
        assertThrows<IllegalArgumentException> { AttestedKey("fermix.device.a.b", emptyList()) }
    }
}
