package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Place
import io.tezra.fermix.protocol.PlaceLocation
import io.tezra.fermix.protocol.ReplyBlock
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private val TEA =
    Place("Corner Tea", sourceUrl = "https://provider.example/place/2", location = PlaceLocation(40.72, -74.0))
private val COFFEE =
    Place("Example Coffee", canonicalUrl = "https://example.coffee/", location = PlaceLocation(40.7233, -74.003))
private val PLACES = ReplyBlock.Places(listOf(COFFEE, TEA))

/** A block of a type this app does not know, its `type` [type]. */
private fun skipped(type: String) = ReplyBlock.Skipped(JsonObject(mapOf("type" to JsonPrimitive(type))))

/**
 * A reply's blocks through the session (the companion PROTOCOL.md "Blocks under a reply"): a `text_done`'s and a
 * live `row`'s reach the timeline on their rows, as the row's link previews do; [placesOf] is the one places
 * block's places; and a block of a type this app does not know is kept on its row and never drawn, its type
 * noted in the Diagnostics once for each event that brings it, as a skipped candidate kind is.
 */
class ReplyBlocksTest {
    @Test
    fun `a text_done seals its reply with its blocks, and a live row keeps its own`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(ServerEvent.TextDone("turn-m1", 1uL, "Try these", blocks = listOf(PLACES)))
            assertEquals(ClientEvent.Ack(1uL), connection.expect<ClientEvent.Ack>())
            connection.send(row(2uL).copy(blocks = listOf(PLACES)))
            assertEquals(ClientEvent.Ack(2uL), connection.expect<ClientEvent.Ack>())
            val (reply, message) = harness.announcer.rows
            assertEquals(TimelineRow.Reply(1uL, "turn-m1", "Try these", false, null, blocks = listOf(PLACES)), reply)
            assertEquals(listOf(PLACES), (message as TimelineRow.Message).message.blocks)
            assertEquals(listOf(COFFEE, TEA), placesOf(reply))
            assertEquals(listOf(COFFEE, TEA), placesOf(message))
        }

    @Test
    fun `a row's places are its one places block's, and none without one`() {
        val held = TimelineRow.Message(message(3uL).copy(blocks = listOf(skipped("event"), PLACES)))
        assertEquals(listOf(skipped("event"), PLACES), blocksOf(held))
        assertEquals(listOf(COFFEE, TEA), placesOf(held))
        assertEquals(emptyList<Place>(), placesOf(TimelineRow.Message(message(4uL))))
        val unknown = TimelineRow.Message(message(5uL).copy(blocks = listOf(skipped("x"))))
        assertEquals(emptyList<Place>(), placesOf(unknown))
        assertEquals(emptyList<ReplyBlock>(), blocksOf(TimelineRow.Reply(6uL, "turn-6", "Hi", false, null)))
    }

    @Test
    fun `a block of a type this app does not know stays on its row, its type noted once for the event`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val blocks = listOf(skipped("event"), PLACES, skipped("event"), skipped("choice\u0007"))
            connection.send(row(1uL).copy(blocks = blocks))
            connection.expect<ClientEvent.Ack>()
            assertEquals(blocks, blocksOf(harness.announcer.rows.single()))
            val notes = harness.diagnostics().filter { it.kind == DiagnosticKind.UNKNOWN_BLOCK }
            assertEquals(
                listOf("skipped 3 block(s) of a type this app does not know: 'event', 'choice?'"),
                notes.map { it.detail },
            )
        }

    @Test
    fun `a page's skipped blocks are noted in one line, and a row with none notes nothing`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(row(1uL).copy(blocks = listOf(PLACES)))
            connection.expect<ClientEvent.Ack>()
            connection.send(row(3uL))
            connection.expect<ClientEvent.HistoryPull>()
            val first = message(2uL).copy(blocks = listOf(skipped("event")))
            val second = message(3uL).copy(blocks = listOf(skipped("event"), PLACES))
            connection.send(page(listOf(first, second), nextAfterSeq = 3uL, head = 3uL))
            connection.acksThrough(3uL)
            val notes = harness.diagnostics().filter { it.kind == DiagnosticKind.UNKNOWN_BLOCK }.map { it.detail }
            assertEquals(listOf("skipped 2 block(s) of a type this app does not know: 'event'"), notes)
        }
}
