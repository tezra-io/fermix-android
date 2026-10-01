package io.tezra.fermix.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The plaintext frame and its bounds (PROTOCOL.md "Stack and bounds"), both ways. */
class FrameTest {
    private fun bytes(size: Int) = ByteArray(size) { 'a'.code.toByte() }

    private fun prefixed(
        length: Long,
        rest: ByteArray,
    ): ByteArray {
        val prefix = ByteArray(4) { index -> (length shr (8 * (3 - index))).toByte() }
        return prefix + rest
    }

    @Test
    fun `a frame is the uint32be header length, the header, then the raw tail`() {
        val frame = Frame("{}".encodeToByteArray(), byteArrayOf(9, 8))
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, '{'.code.toByte(), '}'.code.toByte(), 9, 8), frame.encode())

        val decoded = Frame.decode(frame.encode())
        assertArrayEquals("{}".encodeToByteArray(), decoded.header)
        assertArrayEquals(byteArrayOf(9, 8), decoded.raw)
    }

    @Test
    fun `the largest frame is a 4,075-byte header with a 60 KiB tail`() {
        val frame = Frame(bytes(MAX_PLAINTEXT_BYTES - LENGTH_PREFIX_BYTES - MAX_RAW_BYTES), bytes(MAX_RAW_BYTES))
        assertEquals(MAX_PLAINTEXT_BYTES, frame.encode().size)
        assertEquals(4_075, frame.header.size)
        assertEquals(MAX_PLAINTEXT_BYTES, Frame.decode(frame.encode()).let { 4 + it.header.size + it.raw.size })
    }

    @Test
    fun `encoding refuses a header past 4,096 bytes`() {
        val refusal = assertThrows<ProtocolException.HeaderTooLong> { Frame(bytes(MAX_HEADER_BYTES + 1), ByteArray(0)) }
        assertEquals(MAX_HEADER_BYTES + 1L, refusal.length)
        Frame(bytes(MAX_HEADER_BYTES), ByteArray(0))
    }

    @Test
    fun `encoding refuses a raw tail past 61,440 bytes`() {
        val refusal = assertThrows<ProtocolException.RawTooLong> { Frame(bytes(1), bytes(MAX_RAW_BYTES + 1)) }
        assertEquals(MAX_RAW_BYTES + 1, refusal.size)
    }

    @Test
    fun `encoding refuses a frame past 65,519 bytes though each part is within its own bound`() {
        val header = bytes(MAX_HEADER_BYTES)
        val refusal = assertThrows<ProtocolException.FrameTooLong> { Frame(header, bytes(MAX_RAW_BYTES)) }
        assertEquals(LENGTH_PREFIX_BYTES + MAX_HEADER_BYTES + MAX_RAW_BYTES, refusal.size)
    }

    @Test
    fun `decoding refuses a frame past 65,519 bytes`() {
        val refusal = assertThrows<ProtocolException.FrameTooLong> { Frame.decode(bytes(MAX_PLAINTEXT_BYTES + 1)) }
        assertEquals(MAX_PLAINTEXT_BYTES + 1, refusal.size)
    }

    @Test
    fun `decoding refuses a declared header length past 4,096 bytes`() {
        val refusal =
            assertThrows<ProtocolException.HeaderTooLong> {
                Frame.decode(prefixed(MAX_HEADER_BYTES + 1L, bytes(MAX_HEADER_BYTES + 1)))
            }
        assertEquals(MAX_HEADER_BYTES + 1L, refusal.length)
    }

    @Test
    fun `decoding refuses a declared header length past 4,096 bytes before it looks at what follows`() {
        val refusal =
            assertThrows<ProtocolException.HeaderTooLong> { Frame.decode(prefixed(MAX_HEADER_BYTES + 1L, bytes(8))) }
        assertEquals(MAX_HEADER_BYTES + 1L, refusal.length)
    }

    @Test
    fun `a header of exactly 4,096 bytes decodes, and one of 4,097 is refused by its length before it is read`() {
        val start = """{"v":1,"t":"pong","seq":1,"pad":""""
        val header = start + "p".repeat(MAX_HEADER_BYTES - start.length - 2) + "\"}"
        assertEquals(MAX_HEADER_BYTES, header.encodeToByteArray().size)
        assertEquals(ServerEvent.Pong, decodeServerEvent(frameOf(header)).event)
        val garbage = ByteArray(MAX_HEADER_BYTES + 1) { 0xFF.toByte() }
        assertThrows<ProtocolException.HeaderTooLong> { decodeServerEvent(prefixed(garbage.size.toLong(), garbage)) }
    }

    @Test
    fun `decoding reads the length as unsigned`() {
        val refusal = assertThrows<ProtocolException.HeaderTooLong> { Frame.decode(prefixed(0xFFFF_FFFFL, bytes(8))) }
        assertEquals(0xFFFF_FFFFL, refusal.length)
    }

    @Test
    fun `decoding refuses a raw tail past 61,440 bytes`() {
        val refusal =
            assertThrows<ProtocolException.RawTooLong> { Frame.decode(prefixed(2, bytes(2 + MAX_RAW_BYTES + 1))) }
        assertEquals(MAX_RAW_BYTES + 1, refusal.size)
    }

    @Test
    fun `decoding refuses a length that overruns the buffer`() {
        val refusal = assertThrows<ProtocolException.LengthOverrunsFrame> { Frame.decode(prefixed(10, bytes(9))) }
        assertEquals(10L, refusal.length)
        assertEquals(9, refusal.available)
    }

    @Test
    fun `decoding refuses a buffer too short to hold the length`() {
        val refusal = assertThrows<ProtocolException.FrameTooShort> { Frame.decode(byteArrayOf(0, 0, 1)) }
        assertEquals(3, refusal.size)
    }
}
