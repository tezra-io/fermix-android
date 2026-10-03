package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.ModelSource
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.TurnEffect.CardRemoved
import io.tezra.fermix.session.TurnEffect.CardShown
import io.tezra.fermix.session.TurnEffect.TurnEnded
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the daemon puts on rows it sent already, over the vendored `reaction` and `link_preview` lines: kept on
 * the cached row through the store as a mutation would be, and told to the app as typed events; and the model
 * change of ProvisionalV2Test's MODEL_CHANGED_V2, which only design section 7 defines.
 */
class RowEventsTest {
    private val reaction = vendoredServer("reaction") as ServerEvent.Reaction
    private val example = vendoredServer("link_preview") as ServerEvent.LinkPreview
    private val post = vendoredServer("link_preview", nth = 2) as ServerEvent.LinkPreview

    private fun ServerEvent.LinkPreview.card() = LinkPreviewCard(url, site, title, description, imageRef)

    @Test
    fun `a reaction lands in the owner's row's metadata, its other keys kept`() =
        runTest {
            val harness = Harness(this)
            val turn = JsonObject(mapOf("turn_id" to JsonPrimitive("turn-client-1")))
            val asked = message(12uL, role = "user", clientMsgId = "client-1").copy(metadata = turn)
            harness.store.cached[12uL] = TimelineRow.Message(asked)
            val connection = harness.connect()
            connection.send(reaction)
            harness.settle()
            val metadata = (harness.store.cached.getValue(12uL) as TimelineRow.Message).message.metadata
            val kept = JsonObject(turn + (REACTION_KEY to JsonObject(mapOf("emoji" to JsonPrimitive("👍")))))
            assertEquals(kept, metadata)
            assertTrue(SessionEvent.Reaction("client-1", "👍", stored = true) in harness.events)
        }

    @Test
    fun `a reaction on a row the cache does not hold is told, and its history brings it`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(reaction)
            harness.settle()
            assertTrue(SessionEvent.Reaction("client-1", "👍", stored = false) in harness.events)
        }

    @Test
    fun `link previews are kept on their row by server_seq, once per url`() =
        runTest {
            val harness = Harness(this)
            harness.store.cached[13uL] = TimelineRow.Message(message(13uL))
            val connection = harness.connect()
            connection.send(example)
            connection.send(post)
            connection.send(example)
            harness.settle()
            val row = harness.store.cached.getValue(13uL)
            assertEquals(listOf(example.card(), post.card()), linkPreviewsOf(row))
            assertTrue(SessionEvent.LinkPreview(13uL, post.card(), stored = true) in harness.events)
        }

    @Test
    fun `a reply's row keeps its previews too, and a row holds four at most`() =
        runTest {
            val harness = Harness(this)
            harness.store.cached[13uL] =
                TimelineRow.Reply(13uL, "turn-client-1", "See", truncated = false, route = null)
            val connection = harness.connect()
            (1..MAX_LINK_PREVIEWS + 1).forEach { connection.send(example.copy(url = "https://example.com/$it")) }
            harness.settle()
            val urls = linkPreviewsOf(harness.store.cached.getValue(13uL)).map { it.url }
            assertEquals((1..MAX_LINK_PREVIEWS).map { "https://example.com/$it" }, urls)
        }

    @Test
    fun `a model change is a typed event`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val note = "Computer History is off for this model"
            connection.send(
                ServerEvent.ModelChanged(PROFILE, "openai", "gpt-6.1", "GPT-6.1", ModelSource.OVERRIDE, note),
            )
            harness.settle()
            assertTrue(
                SessionEvent.ModelChanged("openai", "gpt-6.1", "GPT-6.1", ModelSource.OVERRIDE, note) in harness.events,
            )
        }

    @Test
    fun `a reaction-only turn opens no bubble, and its card goes as it ends`() =
        runTest {
            val harness = Harness(this)
            harness.store.cached[12uL] = TimelineRow.Message(message(12uL, role = "user", clientMsgId = "client-1"))
            val connection = harness.connect()
            harness.session.send(msg("client-1"))
            connection.expect<ClientEvent.Msg>()
            connection.send(ServerEvent.Accepted("client-1", duplicate = false))
            connection.send(reaction)
            connection.send(ServerEvent.TurnDone("turn-client-1"))
            harness.settle()
            val effects = harness.turnEffects()
            assertEquals(listOf(CardShown::class, CardRemoved::class, TurnEnded::class), effects.map { it::class })
            assertEquals(TurnOutcome.Completed, (effects.last() as TurnEnded).outcome)
        }
}
