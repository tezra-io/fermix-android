package io.tezra.fermix.data

import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.protocol.OpeningHours
import io.tezra.fermix.protocol.Place
import io.tezra.fermix.protocol.PlaceDistance
import io.tezra.fermix.protocol.PlaceLocation
import io.tezra.fermix.protocol.PlaceRating
import io.tezra.fermix.protocol.ReplyBlock
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.blocksOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

private const val TS = "2026-10-16T09:00:00Z"

private val COFFEE =
    Place(
        name = "Example Coffee",
        canonicalUrl = "https://example.coffee/",
        sourceUrl = "https://provider.example/place/1",
        location = PlaceLocation(40.7233, -74.003),
        address = "12 Example St, New York",
        rating = PlaceRating(4.6, 5.0, 321.0),
        priceRange = "$$",
        categories = listOf("coffee_shop"),
        openingHours = OpeningHours("Thursday 07:00-19:00"),
        distance = PlaceDistance(0.4, "km"),
    )

private val TEA =
    Place("Corner Tea", sourceUrl = "https://provider.example/place/2", location = PlaceLocation(1.0, 2.0))

/** A block of a type this app does not know, as it came. */
private val EVENT =
    ReplyBlock.Skipped(JsonObject(mapOf("type" to JsonPrimitive("event"), "title" to JsonPrimitive("Standup"))))

private val BLOCKS = listOf(ReplyBlock.Places(listOf(COFFEE, TEA)), EVENT)

private val PREVIEW = LinkPreviewCard("https://hexdocs.pm/elixir/Task.html", "hexdocs.pm", "Task — Elixir")

/**
 * A reply's blocks in the timeline cache (the companion PROTOCOL.md "Blocks under a reply", M72 section 14): a
 * sealed reply keeps them in its `blocks` column beside its link previews, a whole row in its JSON as it keeps
 * every field, each read back as it came, a block of a type this app does not know among them; and what changes a
 * cached row later, a mutation or a link preview, never carries blocks, so the row keeps its own.
 */
class TimelineBlocksTest {
    private val database = inMemoryDatabase()
    private val timeline = database.timeline()
    private val store = RoomSessionStore(database, StagedUploads(Files.createTempDirectory("staged").toFile()))

    @AfterEach
    fun close() = database.close()

    @Test
    fun `a sealed reply keeps its blocks in its own column, and reads them back as they came`() =
        runTest {
            val reply = TimelineRow.Reply(5uL, "turn-1", "Try these", false, Route("openai", "gpt"), blocks = BLOCKS)
            timeline.persist(reply)
            assertEquals(reply, timeline.row(5uL))
            val entity = checkNotNull(timeline.entity(5L))
            assertNotNull(entity.blocks)
            assertNull(entity.rowJson)
        }

    @Test
    fun `a row with blocks keeps them in its JSON, as it keeps every field`() =
        runTest {
            val row =
                TimelineRow.Message(
                    HistoryMessage(6uL, "assistant", "Try these", TS, emptyList(), blocks = BLOCKS),
                )
            timeline.persist(row)
            assertEquals(row, timeline.row(6uL))
            assertNull(checkNotNull(timeline.entity(6L)).blocks, "a whole row's blocks are in its JSON alone")
        }

    @Test
    fun `a reply with no blocks keeps none`() =
        runTest {
            val reply = TimelineRow.Reply(7uL, "turn-7", "Hi", false, null)
            timeline.persist(reply)
            assertEquals(reply, timeline.row(7uL))
            assertNull(checkNotNull(timeline.entity(7L)).blocks)
        }

    @Test
    fun `a mutation and a link preview keep the stored row's blocks`() =
        runTest {
            timeline.persist(TimelineRow.Reply(5uL, "turn-1", "Try these", false, null, blocks = BLOCKS))
            val message = HistoryMessage(6uL, "assistant", "Try these", TS, emptyList(), blocks = BLOCKS)
            timeline.persist(TimelineRow.Message(message))
            store.applyMutations(
                listOf(MutationRow(5uL, 1uL, content = "Try"), MutationRow(6uL, 2uL, content = "Go")),
                2uL,
            )
            store.addLinkPreview(5uL, PREVIEW)
            store.addLinkPreview(6uL, PREVIEW)
            val reply = checkNotNull(timeline.row(5uL)) as TimelineRow.Reply
            assertEquals("Try" to listOf(PREVIEW), reply.text to reply.linkPreviews)
            assertEquals(BLOCKS, blocksOf(reply))
            val row = checkNotNull(timeline.row(6uL)) as TimelineRow.Message
            assertEquals("Go" to listOf(PREVIEW), row.message.content to row.message.linkPreviews)
            assertEquals(BLOCKS, blocksOf(row))
        }
}
