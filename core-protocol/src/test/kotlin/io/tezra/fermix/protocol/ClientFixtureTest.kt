package io.tezra.fermix.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

private const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

/**
 * Every client frame of the vendored fixtures, byte for byte (design section 12.6): each line
 * decodes to its model and encodes back to the same bytes, and the same model built in Kotlin from
 * its fields encodes to them too, which pins the order of every field.
 */
class ClientFixtureTest {
    @ParameterizedTest
    @MethodSource("lines")
    fun `a client line decodes to its model and encodes back to the same bytes`(line: HeaderLine) {
        val decoded = decodeClientEvent(line.frame())
        assertEquals(1, decoded.v)
        assertEquals(line.text, headerOf(encodeClientEvent(decoded.v, decoded.seq, decoded.event)))
    }

    @Test
    fun `each client line is the model built from its fields`() {
        val lines = headerLines(CLIENT_EVENTS)
        assertEquals(BUILT.size, lines.size)
        BUILT.zip(lines).forEach { (built, line) ->
            val (seq, event) = built
            assertEquals(line.text, headerOf(encodeClientEvent(1, seq, event)), "$line")
            assertEquals(event, decodeClientEvent(line.frame()).event, "$line")
        }
    }

    @ParameterizedTest
    @MethodSource("binaryLines")
    fun `a client binary frame splits into its header and tail and is built back from them`(line: BinaryLine) {
        val frame = Frame.decode(line.frame())
        assertArrayEquals(line.headerBytes, frame.header)
        assertArrayEquals(line.raw, frame.raw)
        assertArrayEquals(line.frame(), Frame(line.headerBytes, line.raw).encode())

        val decoded = decodeClientEvent(line.frame())
        assertArrayEquals(line.raw, decoded.raw)
        assertArrayEquals(line.frame(), encodeClientEvent(decoded.v, decoded.seq, decoded.event, decoded.raw))
    }

    @Test
    fun `the client binary frame is the chunk built from its fields`() {
        val line = binaryLines(CLIENT_BINARY_FRAMES).single()
        val built = encodeClientEvent(1, 4uL, ClientEvent.AttachChunk("attach-1", 0), "1234".encodeToByteArray())
        assertArrayEquals(line.frame(), built)
    }

    companion object {
        const val CLIENT_EVENTS = "fixtures/client_events.jsonl"
        const val CLIENT_BINARY_FRAMES = "fixtures/client_binary_frames.jsonl"

        @JvmStatic
        fun lines() = headerLines(CLIENT_EVENTS)

        @JvmStatic
        fun binaryLines() = binaryLines(CLIENT_BINARY_FRAMES)

        /** client_events.jsonl, line by line, as the app builds each event. */
        private val BUILT: List<Pair<ULong, ClientEvent>> =
            listOf(
                1uL to ClientEvent.Hello("device-1", "1.0.0", 0uL, 1),
                2uL to ClientEvent.Msg("client-1", "main", "Hello", emptyList()),
                3uL to ClientEvent.AttachBegin("attach-1", AttachKind.IMAGE, "image/jpeg", 4, "photo.jpg", SHA),
                5uL to ClientEvent.AttachEnd("attach-1", SHA),
                6uL to ClientEvent.Msg("client-2", "main", "", listOf("attach-1")),
                7uL to ClientEvent.Command("client-3", "main", "help", ""),
                8uL to ClientEvent.Command("client-4", "main", "confirm", "opaque-token"),
                9uL to ClientEvent.Cancel("main", "client-1"),
                10uL to ClientEvent.HistoryPull("main", afterSeq = 0uL, limit = 200),
                11uL to ClientEvent.HistoryPull("main", afterSeq = ULong.MAX_VALUE, limit = 50),
                12uL to ClientEvent.MediaFetch(SHA),
                13uL to
                    ClientEvent.PushRegister(apnsToken = "0123456789abcdef", environment = PushEnvironment.DEVELOPMENT),
                14uL to ClientEvent.Ack(12uL),
                15uL to ClientEvent.ReadState("main", 12uL),
                16uL to ClientEvent.Ping,
                17uL to ClientEvent.Unpair,
                1uL to ClientEvent.PairRequest("Owner's iPhone", "iPhone", "1.0.0"),
            )
    }
}
