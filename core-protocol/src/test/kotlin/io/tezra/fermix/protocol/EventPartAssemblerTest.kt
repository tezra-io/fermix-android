package io.tezra.fermix.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The rules of an `event_part` run (PROTOCOL.md "Continuation frames"), each one broken in turn. The
 * assembler is fed what the decoder hands back, as the session feeds it.
 */
class EventPartAssemblerTest {
    private fun part(
        seq: Int,
        index: Int,
        count: Int,
        tail: ByteArray,
        v: Int = 1,
    ) = decodeServerEvent(frameOf("""{"v":$v,"t":"event_part","seq":$seq,"index":$index,"count":$count}""", tail))

    private fun pong(seq: Int) = decodeServerEvent(frameOf("""{"v":1,"t":"pong","seq":$seq}"""))

    /** A logical event's JSON cut into [count] tails. */
    private fun tails(
        json: String,
        count: Int,
    ): List<ByteArray> {
        val bytes = json.encodeToByteArray()
        val size = (bytes.size + count - 1) / count
        return List(count) { bytes.copyOfRange(it * size, minOf(bytes.size, (it + 1) * size)) }
    }

    private val reply =
        """{"t":"text_done","turn_id":"turn-client-6","server_seq":19,"text":"A reply sent in three parts"}"""

    @Test
    fun `a run of three joins into its event, at the run's v and first seq`() {
        val assembler = EventPartAssembler()
        val tails = tails(reply, 3)
        assertNull(assembler.accept(part(40, 0, 3, tails[0], v = 2)))
        assertNull(assembler.accept(part(41, 1, 3, tails[1], v = 2)))
        val logical = checkNotNull(assembler.accept(part(42, 2, 3, tails[2], v = 2)))
        assertEquals(2, logical.v)
        assertEquals(40uL, logical.seq)
        assertEquals(ServerEvent.TextDone("turn-client-6", 19uL, "A reply sent in three parts"), logical.event)
        assertEquals(ServerEvent.Pong, assembler.accept(pong(43))?.event)
    }

    @Test
    fun `an unknown event inside a run comes back as Unknown`() {
        val tails = tails("""{"t":"future_event","x":1}""", 2)
        val assembler = EventPartAssembler()
        assembler.accept(part(1, 0, 2, tails[0]))
        val logical = checkNotNull(assembler.accept(part(2, 1, 2, tails[1])))
        assertEquals(ServerEvent.Unknown("future_event", 1uL, """{"t":"future_event","x":1}"""), logical.event)
    }

    @Test
    fun `a part whose count is outside 2 to 18, or whose index is not below its count, is refused as it is decoded`() {
        listOf(1 to 0, 19 to 0).forEach { (count, index) ->
            val refusal = assertThrows<ProtocolException.InvalidField> { part(1, index, count, byteArrayOf(1)) }
            assertEquals("count", refusal.field, "count $count")
        }
        listOf(2 to 2, 18 to 18, 2 to -1).forEach { (count, index) ->
            val refusal = assertThrows<ProtocolException.InvalidField> { part(1, index, count, byteArrayOf(1)) }
            assertEquals("index", refusal.field, "index $index of $count")
        }
        part(1, 17, 18, byteArrayOf(1))
    }

    @Test
    fun `a part with an empty tail is refused as it is decoded`() {
        assertEquals("event_part", assertThrows<ProtocolException.MissingRaw> { part(1, 0, 2, ByteArray(0)) }.t)
    }

    @Test
    fun `a count that changes inside a run is refused`() {
        val assembler = EventPartAssembler()
        assembler.accept(part(1, 0, 3, byteArrayOf(1)))
        val refusal =
            assertThrows<ProtocolException.PartCountChanged> { assembler.accept(part(2, 1, 4, byteArrayOf(2))) }
        assertEquals(4, refusal.count)
        assertEquals(3, refusal.runCount)
    }

    @Test
    fun `an index out of order is refused, a repeated one, and a run that does not start at 0`() {
        val assembler = EventPartAssembler()
        assembler.accept(part(1, 0, 3, byteArrayOf(1)))
        val skipped = assertThrows<ProtocolException.PartOutOfOrder> { assembler.accept(part(2, 2, 3, byteArrayOf(2))) }
        assertEquals(2, skipped.index)
        assertEquals(1, skipped.expected)
        val again = EventPartAssembler()
        again.accept(part(1, 0, 3, byteArrayOf(1)))
        val repeated = assertThrows<ProtocolException.PartOutOfOrder> { again.accept(part(2, 0, 3, byteArrayOf(1))) }
        assertEquals(0, repeated.index)
        assertEquals(1, repeated.expected)
        val late =
            assertThrows<ProtocolException.PartOutOfOrder> {
                EventPartAssembler().accept(
                    part(1, 1, 2, byteArrayOf(1)),
                )
            }
        assertEquals(0, late.expected)
    }

    @Test
    fun `a part whose seq does not follow the one before it is refused`() {
        val assembler = EventPartAssembler()
        assembler.accept(part(5, 0, 3, byteArrayOf(1)))
        val refusal =
            assertThrows<ProtocolException.PartOutOfSequence> { assembler.accept(part(900, 1, 3, byteArrayOf(2))) }
        assertEquals(900uL, refusal.seq)
        assertEquals(6uL, refusal.expected)
        val repeated = EventPartAssembler()
        repeated.accept(part(5, 0, 2, byteArrayOf(1)))
        assertThrows<ProtocolException.PartOutOfSequence> { repeated.accept(part(5, 1, 2, byteArrayOf(2))) }
    }

    @Test
    fun `a part whose v differs from its run's is refused`() {
        val assembler = EventPartAssembler()
        assembler.accept(part(5, 0, 2, byteArrayOf(1), v = 1))
        val refusal =
            assertThrows<ProtocolException.PartVersionChanged> {
                assembler.accept(
                    part(6, 1, 2, byteArrayOf(2), v = 2),
                )
            }
        assertEquals(2, refusal.version)
        assertEquals(1, refusal.runVersion)
    }

    @Test
    fun `a frame that is not a part, inside a run, is refused`() {
        val assembler = EventPartAssembler()
        assembler.accept(part(1, 0, 2, byteArrayOf(1)))
        assertEquals("pong", assertThrows<ProtocolException.FrameInsideRun> { assembler.accept(pong(2)) }.t)
        val unknown = EventPartAssembler()
        unknown.accept(part(1, 0, 2, byteArrayOf(1)))
        val future = decodeServerEvent(frameOf("""{"v":1,"t":"future","seq":2}"""))
        assertEquals("future", assertThrows<ProtocolException.FrameInsideRun> { unknown.accept(future) }.t)
    }

    @Test
    fun `a run past 1 MiB is refused`() {
        val assembler = EventPartAssembler()
        val tail = ByteArray(MAX_RAW_BYTES) { ' '.code.toByte() }
        repeat(17) { index -> assembler.accept(part(index + 1, index, 18, tail)) }
        val refusal = assertThrows<ProtocolException.RunTooLarge> { assembler.accept(part(18, 17, 18, tail)) }
        assertEquals(18L * MAX_RAW_BYTES, refusal.size)
        assertTrue(refusal.size > MAX_EVENT_BYTES)
    }

    @Test
    fun `a run of 1 MiB nested past 32 levels is refused as malformed, never overflowing the stack`() {
        val logical = """{"t":"future","x":""" + "[".repeat(MAX_EVENT_BYTES - 20)
        val tails = tails(logical, MAX_EVENT_PARTS)
        val refusal =
            onSmallStack {
                val assembler = EventPartAssembler()
                tails.dropLast(1).forEachIndexed {
                    index,
                    tail,
                    ->
                    assembler.accept(part(index + 1, index, MAX_EVENT_PARTS, tail))
                }
                assertThrows<ProtocolException.MalformedHeader> {
                    assembler.accept(part(MAX_EVENT_PARTS, MAX_EVENT_PARTS - 1, MAX_EVENT_PARTS, tails.last()))
                }
            }
        assertTrue(refusal.detail.contains("nests"), refusal.detail)
    }

    @Test
    fun `a run whose event is a part or a media chunk is refused`() {
        listOf(EVENT_PART, "media_chunk").forEach { t ->
            val tails = tails("""{"t":"$t","index":0,"count":2}""", 2)
            val assembler = EventPartAssembler()
            assembler.accept(part(1, 0, 2, tails[0]))
            assertEquals(
                t,
                assertThrows<ProtocolException.UnsplittableEvent> { assembler.accept(part(2, 1, 2, tails[1])) }.t,
            )
        }
    }

    @Test
    fun `a logical event that carries v or seq is refused`() {
        listOf("""{"t":"pong","seq":3}""" to "seq", """{"v":1,"t":"pong"}""" to "v").forEach { (json, field) ->
            val tails = tails(json, 2)
            val assembler = EventPartAssembler()
            assembler.accept(part(1, 0, 2, tails[0]))
            assertEquals(
                field,
                assertThrows<ProtocolException.InvalidEnvelope> { assembler.accept(part(2, 1, 2, tails[1])) }.field,
            )
        }
    }

    @Test
    fun `a logical event is held to its event's rules`() {
        val tails = tails("""{"t":"text_done","turn_id":"","server_seq":19,"text":"x"}""", 2)
        val assembler = EventPartAssembler()
        assembler.accept(part(1, 0, 2, tails[0]))
        assertEquals(
            "turn_id",
            assertThrows<ProtocolException.InvalidField> {
                assembler.accept(part(2, 1, 2, tails[1]))
            }.field,
        )
    }

    @Test
    fun `after a refusal the assembler refuses every frame`() {
        val assembler = EventPartAssembler()
        assembler.accept(part(1, 0, 2, byteArrayOf(1)))
        assertThrows<ProtocolException.PartOutOfOrder> { assembler.accept(part(2, 0, 2, byteArrayOf(1))) }
        assertThrows<ProtocolException.AssemblerFailed> { assembler.accept(pong(3)) }
    }
}
