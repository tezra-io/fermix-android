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

/** A stream that answers every read with no bytes and no end, as a non-blocking pipe does once its writer is silent. */
private class StuckStream : InputStream() {
    override fun read(): Int = 0

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int = 0
}

/**
 * A stream that hands over nothing [empties] times, as a non-blocking pipe's empty read does (EAGAIN reads as no
 * bytes), then a byte of 7, [rounds] times, then ends.
 */
private class HaltingStream(
    private val empties: Int,
    private val rounds: Int,
) : InputStream() {
    private var round = 0
    private var empty = 0

    override fun read(): Int = if (read(ByteArray(1), 0, 1) < 0) -1 else 7

    override fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (round == rounds) return -1
        val handing = empty == empties
        empty = if (handing) 0 else empty + 1
        if (handing) round++
        if (handing) into[offset] = 7
        return if (handing) 1 else 0
    }
}

/**
 * The copy an item lands as, from a share, a paste or the keyboard, and the one Send makes of an item's own bytes
 * (copyAtMost): at most the daemon's limit and a byte more, which tells an item past it, however much its provider
 * would hand over; a stream that hands over nothing for a while, a non-blocking pipe's, is read again until its idle
 * time passes.
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
    fun `a stream that hands over nothing a few times and then its bytes is copied whole`() {
        val into = ByteArrayOutputStream()
        assertEquals(3L, copyAtMost(HaltingStream(empties = 3, rounds = 3), into, 1_000L))
        assertArrayEquals(byteArrayOf(7, 7, 7), into.toByteArray())
    }

    @Test
    fun `each byte a stream hands over starts its idle time again`() {
        val into = ByteArrayOutputStream()
        assertEquals(20L, copyAtMost(HaltingStream(empties = 4, rounds = 20), into, 1_000L, idleMillis = 50L))
    }

    @Test
    fun `a stream that hands over nothing and never ends is an IOException once its idle time passes, never a loop`() {
        assertThrows<IOException> { copyAtMost(StuckStream(), ByteArrayOutputStream(), 1_000L, idleMillis = 50L) }
    }

    @Test
    fun `a negative limit is refused`() {
        assertThrows<IllegalArgumentException> { copyAtMost(EndlessStream(), ByteArrayOutputStream(), -1L) }
    }
}
