package io.tezra.fermix.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Every server frame of the vendored fixtures, byte for byte (design section 12.6): each line
 * decodes to its typed model, never to Unknown, and encodes back to the same bytes; the binary
 * frames split and rebuild; and the two-frame run joins into its logical event.
 */
class ServerFixtureTest {
    @ParameterizedTest
    @MethodSource("lines")
    fun `a server line decodes to its typed model and encodes back to the same bytes`(line: HeaderLine) {
        val decoded = decodeServerEvent(line.frame())
        val event = decoded.event
        assertTrue(event is ServerEvent.Known, "$line decoded to $event")
        assertEquals(1, decoded.v)
        assertEquals(line.text, headerOf(encodeServerEvent(decoded.v, decoded.seq, event as ServerEvent.Known)))
    }

    @ParameterizedTest
    @MethodSource("binaryLines")
    fun `a server binary frame splits into its header and tail and is built back from them`(line: BinaryLine) {
        val frame = Frame.decode(line.frame())
        assertArrayEquals(line.headerBytes, frame.header)
        assertArrayEquals(line.raw, frame.raw)
        assertArrayEquals(line.frame(), Frame(line.headerBytes, line.raw).encode())

        val decoded = decodeServerEvent(line.frame())
        val event = decoded.event as ServerEvent.Known
        assertArrayEquals(line.raw, decoded.raw)
        assertArrayEquals(line.frame(), encodeServerEvent(decoded.v, decoded.seq, event, decoded.raw))
    }

    @Test
    fun `the vendored run joins into the reply it carries, at the run's v and first seq`() {
        val assembler = EventPartAssembler()
        val run = binaryLines(SERVER_BINARY_FRAMES).filter { it.t == EVENT_PART }
        assertEquals(2, run.size)

        assertNull(assembler.accept(decodeServerEvent(run[0].frame())))
        val logical = checkNotNull(assembler.accept(decodeServerEvent(run[1].frame())))

        assertEquals(1, logical.v)
        assertEquals(34uL, logical.seq)
        assertEquals(ServerEvent.TextDone("turn-client-6", 19uL, "A reply sent as two parts"), logical.event)
        assertEquals(0, logical.raw.size)
    }

    @Test
    fun `a frame outside a run passes through the assembler as it is`() {
        val line = binaryLines(SERVER_BINARY_FRAMES).first { it.t == "media_chunk" }
        val decoded = decodeServerEvent(line.frame())
        assertSame(decoded, EventPartAssembler().accept(decoded))
    }

    companion object {
        const val SERVER_EVENTS = "fixtures/server_events.jsonl"
        const val SERVER_BINARY_FRAMES = "fixtures/server_binary_frames.jsonl"

        @JvmStatic
        fun lines() = headerLines(SERVER_EVENTS)

        @JvmStatic
        fun binaryLines() = binaryLines(SERVER_BINARY_FRAMES)
    }
}
