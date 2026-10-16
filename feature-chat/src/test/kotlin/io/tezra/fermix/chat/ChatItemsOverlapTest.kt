package io.tezra.fermix.chat

import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The id of the message CI's fold drew twice (run 38837787555), as the phone makes one. */
private const val ID = "6e8ad615-4cb9-460b-968a-bb57dad547ad"

/** What the owner asked the demo there. */
private const val ASKED = "How did the export go?"

/**
 * The owner's message as chatItems draws it while its inputs overlap: the outbox, what `accepted` took out of it
 * and the rows are each read on its own, so for a frame one message can stand in two or three of them, and the
 * list draws it once, from the source furthest along (its row, else its bridge from `accepted`, else its outbox
 * item), as a LazyColumn refuses a key it was given twice.
 */
class ChatItemsOverlapTest {
    private val hello = agentRow(1, "Hello from suj-mbp.")
    private val typed = OutboxItem(msg(ID, ASKED))
    private val sent = typed.copy(written = true)
    private val row = userRow(2, ASKED, minutes = 1, clientMsgId = ID)
    private val answer = agentRow(3, "It finished in 84 s.", minutes = 2)
    private val acceptedAt = wallAt(1)

    /** One step of the message's way, and what each of the list's inputs reads at it. */
    private data class Step(
        val name: String,
        val rows: List<TimelineRow>,
        val outbox: List<OutboxItem>,
        val bridged: List<OutboxItem>,
        val live: ChatLive,
    )

    private fun inputs(
        rows: List<TimelineRow> = listOf(hello),
        outbox: List<OutboxItem> = emptyList(),
        bridged: List<OutboxItem> = emptyList(),
        live: ChatLive = ChatLive(),
    ) = ChatInputs(
        rows = rows.sortedByDescending { it.serverSeq },
        outbox = outbox,
        bridged = bridged,
        live = live,
        connected = true,
        unreadAt = null,
        requests = emptyMap(),
        profileId = PROFILE,
        nowWall = wallAt(30),
        zone = UTC,
    )

    /** The session's events folded as the app folds them, each a second after the one before. */
    private fun folded(vararg events: SessionEvent): ChatLive =
        events.withIndex().fold(ChatLive()) { live, (second, event) ->
            live.after(event, Moment(second * 1_000L, acceptedAt, null, 1uL))
        }

    private fun turn(effect: TurnEffect) = SessionEvent.Turn(effect, daemonSpeaking = false)

    /**
     * The demo's own answer to a message (DemoRequests, DemoTurns): typed, its frame written, `accepted` with the
     * card shown, the owner's row, and the turn's words sealed as the agent's row once the turn is done.
     */
    private fun demoSteps(): List<Step> {
        val turnId = "turn-$ID"
        val carded = folded(accepted(), turn(TurnEffect.CardShown(turnId)))
        val answered =
            folded(
                accepted(),
                turn(TurnEffect.CardShown(turnId)),
                turn(TurnEffect.BubbleOpened(turnId, 1, fromCard = true)),
                turn(TurnEffect.BubbleText(turnId, 1, "It finished in 84 s.")),
                turn(TurnEffect.BubbleSealed(turnId, 1, 3uL)),
                turn(TurnEffect.TurnEnded(turnId, TurnOutcome.Completed)),
            )
        return listOf(
            Step("typed", listOf(hello), listOf(typed), emptyList(), ChatLive()),
            Step("written", listOf(hello), listOf(sent), emptyList(), ChatLive()),
            Step("accepted", listOf(hello), emptyList(), listOf(sent), carded),
            Step("row", listOf(hello, row), emptyList(), emptyList(), carded),
            Step("answered", listOf(hello, row, answer), emptyList(), emptyList(), answered),
        )
    }

    /**
     * Every mix of [steps] the list can be given: each input is read on its own, so a frame can find each of the four
     * at a step of its own, two or more steps overlapping in it.
     */
    private fun mixes(steps: List<Step>): List<Step> {
        val byRows = steps.map { it.copy(name = "rows ${it.name}") }
        val byOutbox =
            byRows.flatMap { mix ->
                steps.map { mix.copy(name = "${mix.name}, outbox ${it.name}", outbox = it.outbox) }
            }
        val byBridged =
            byOutbox.flatMap { mix ->
                steps.map { mix.copy(name = "${mix.name}, bridged ${it.name}", bridged = it.bridged) }
            }
        return byBridged.flatMap { mix ->
            steps.map { mix.copy(name = "${mix.name}, live ${it.name}", live = it.live) }
        }
    }

    /** The one item the list draws for the owner's message. */
    private fun drawn(inputs: ChatInputs): ShownMessage {
        val items = chatItems(inputs)
        assertEquals(items.map { it.key }.distinct(), items.map { it.key }, "a key the list draws twice")
        return items.filterIsInstance<ChatItem.Message>().single { it.key == outboxKey(ID) }.message
    }

    @Test
    fun `a message in the outbox and bridged from accepted at once is drawn once, delivered with one tick`() {
        val shown = drawn(inputs(outbox = listOf(sent), bridged = listOf(sent), live = folded(accepted())))
        assertEquals(Delivery.DELIVERED, shown.delivery)
        assertEquals(acceptedAt, shown.wallMs)
        assertNull(shown.seq)
    }

    @Test
    fun `a message still in the outbox whose row has landed is drawn once, as its row`() {
        val shown = drawn(inputs(rows = listOf(hello, row), outbox = listOf(sent)))
        assertEquals(Delivery.DELIVERED, shown.delivery)
        assertEquals(2uL, shown.seq)
    }

    @Test
    fun `a message in the outbox, bridged and landed at once is drawn once, as its row`() {
        val all = inputs(listOf(hello, row), outbox = listOf(sent), bridged = listOf(sent), live = folded(accepted()))
        val shown = drawn(all)
        assertEquals(2uL, shown.seq)
    }

    @Test
    fun `a refused item whose row has landed is drawn as its row, and its card still says it was refused`() {
        // A reconnect's request_status tells a failure the lost `accepted` never did: the item stays, marked failed.
        val refused = sent.copy(failure = RequestFailure("timeout", "the turn timed out"))
        val both = inputs(rows = listOf(hello, row), outbox = listOf(refused))
        assertEquals(2uL, drawn(both).seq)
        val card = chatItems(both).filterIsInstance<ChatItem.Error>().single()
        assertEquals("refused:$ID", card.key)
        assertEquals(notSentError(refused), card.error)
    }

    @Test
    fun `every key is drawn once at each step of the demo's answer, and with each input at any step`() {
        val mixes = mixes(demoSteps())
        assertEquals(625, mixes.size)
        for (mix in mixes) {
            val keys = chatItems(inputs(mix.rows, mix.outbox, mix.bridged, mix.live)).map { it.key }
            assertEquals(keys.distinct(), keys, "a key drawn twice with ${mix.name}")
        }
    }

    @Test
    fun `alone, an outbox item, a bridged item and a row each draw as they did`() {
        val queued = drawn(inputs(outbox = listOf(sent)))
        assertEquals(Delivery.SENDING, queued.delivery)
        assertNull(queued.wallMs)
        val bridged = drawn(inputs(bridged = listOf(sent), live = folded(accepted())))
        assertEquals(Delivery.DELIVERED, bridged.delivery)
        assertEquals(acceptedAt, bridged.wallMs)
        val landed = drawn(inputs(rows = listOf(hello, row)))
        assertEquals(Delivery.DELIVERED, landed.delivery)
        assertEquals(2uL, landed.seq)
    }

    private fun accepted() = SessionEvent.Accepted(ID, duplicate = false)
}
