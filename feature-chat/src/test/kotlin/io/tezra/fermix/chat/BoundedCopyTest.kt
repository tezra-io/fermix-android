package io.tezra.fermix.chat

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A provider's stream that never ends, as a hostile one may hand over: a byte of 7 for every read. */
private class EndlessStream : InputStream() {
    var handedOver = 0L

    override fun read(): Int {
        handedOver++
        return 7
    }

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        into.fill(7, offset, offset + length)
        handedOver += length
        return length
    }
}

/** A stream that answers a read with no bytes and no end, which InputStream's contract does not allow. */
private class StuckStream : InputStream() {
    override fun read(): Int = 0

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int = 0
}

/**
 * The copy an item lands as, from a share, a paste or the keyboard (copyAtMost): at most the daemon's limit and a
 * byte more, which tells an item past it, however much its provider would hand over.
 */
class BoundedCopyTest {
    @Test
    fun `a stream that never ends is copied a byte past the limit and no further`() {
        val endless = EndlessStream()
        val into = ByteArrayOutputStream()
        assertEquals(1_001L, copyAtMost(endless, into, 1_000L))
        assertEquals(1_001, into.size())
        assertEquals(1_001L, endless.handedOver)
    }

    @Test
    fun `a stream far past a limit larger than one read is stopped a byte past it`() {
        val limit = 200_000L
        val into = ByteArrayOutputStream()
        assertEquals(limit + 1, copyAtMost(EndlessStream(), into, limit))
        assertEquals(limit + 1, into.size().toLong())
    }

    @Test
    fun `a stream within the limit, or at it, is copied whole`() {
        val bytes = ByteArray(1_000) { it.toByte() }
        val whole = ByteArrayOutputStream()
        assertEquals(1_000L, copyAtMost(ByteArrayInputStream(bytes), whole, 1_000L))
        assertArrayEquals(bytes, whole.toByteArray())
        val small = ByteArrayOutputStream()
        assertEquals(3L, copyAtMost(ByteArrayInputStream(byteArrayOf(1, 2, 3)), small, 1_000L))
        assertEquals(0L, copyAtMost(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 0L))
        assertEquals(1L, copyAtMost(ByteArrayInputStream(byteArrayOf(9)), ByteArrayOutputStream(), 0L))
    }

    @Test
    fun `with no limit, a record whose daemon gave no caps, a stream is copied whole`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val into = ByteArrayOutputStream()
        assertEquals(200_000L, copyAtMost(ByteArrayInputStream(bytes), into, Long.MAX_VALUE))
        assertArrayEquals(bytes, into.toByteArray())
    }

    @Test
    fun `a stream that hands over nothing and never ends is an IOException, never a loop`() {
        assertThrows<IOException> { copyAtMost(StuckStream(), ByteArrayOutputStream(), 1_000L) }
    }

    @Test
    fun `a negative limit is refused`() {
        assertThrows<IllegalArgumentException> { copyAtMost(EndlessStream(), ByteArrayOutputStream(), -1L) }
    }
}
