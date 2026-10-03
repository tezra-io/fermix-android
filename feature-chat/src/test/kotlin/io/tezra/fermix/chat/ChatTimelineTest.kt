package io.tezra.fermix.chat

import io.tezra.fermix.design.GroupPosition
import io.tezra.fermix.design.Sender
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.TurnOutcome
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The chat's list as chatItems builds it (design sections 13.5 and 13.6), read here oldest first. */
class ChatTimelineTest {
    private fun inputs(
        rows: List<TimelineRow> = emptyList(),
        outbox: List<OutboxItem> = emptyList(),
        bridged: List<OutboxItem> = emptyList(),
        live: ChatLive = ChatLive(),
        connected: Boolean = true,
    ) = ChatInputs(
        rows = rows.sortedByDescending { it.serverSeq },
        outbox = outbox,
        bridged = bridged,
        live = live,
        connected = connected,
        unreadAt = null,
        requests = emptyMap(),
        profileId = PROFILE,
        nowWall = wallAt(30),
        zone = UTC,
    )

    private fun oldestFirst(inputs: ChatInputs): List<ChatItem> = chatItems(inputs).asReversed()

    private fun messages(items: List<ChatItem>): List<ShownMessage> =
        items.filterIsInstance<ChatItem.Message>().map {
            it.message
        }

    private fun liveTurn(
        id: String,
        ending: TurnEnding? = null,
        bubbles: List<LiveBubble> = emptyList(),
        card: LiveCard? = null,
    ) = LiveTurn(id, startedMono = 0L, startedWall = wallAt(0), card = card, bubbles = bubbles, ending = ending)

    @Test
    fun `a day header comes first, and one sender's messages within two minutes group`() {
        val items =
            oldestFirst(
                inputs(
                    rows =
                        listOf(
                            userRow(1, "Why did the job fail?", minutes = 0),
                            agentRow(2, "It stopped at export.", minutes = 1),
                            agentRow(3, "I raised the timeout.", minutes = 2),
                            agentRow(4, "Done.", minutes = 10),
                        ),
                ),
            )
        assertEquals(ChatItem.Day("day:2026-09-27", LocalDate.of(2026, 9, 27)), items.first())
        val shown = messages(items)
        assertEquals(Delivery.DELIVERED, shown[0].delivery)
        assertEquals(Sender.User, shown[0].sender)
        assertEquals(
            listOf(GroupPosition.Single, GroupPosition.First, GroupPosition.Last, GroupPosition.Single),
            shown.map { it.position },
        )
    }

    @Test
    fun `the unread divider stands before the agent's first row past the frontier as it was on open`() {
        val rows = listOf(agentRow(1, "One"), userRow(2, "Two"), agentRow(3, "Three"), agentRow(4, "Four"))
        val newestFirst = rows.sortedByDescending { it.serverSeq }
        assertEquals(3uL, unreadAnchor(newestFirst, frontier = 2uL))
        val items = oldestFirst(inputs(rows = rows).copy(unreadAt = unreadAnchor(newestFirst, frontier = 2uL)))
        val divider = items.indexOf(ChatItem.Unread)
        assertEquals("Three", (items[divider + 1] as ChatItem.Message).message.text)
        assertNull(unreadAnchor(newestFirst, frontier = 4uL))
        assertFalse(ChatItem.Unread in oldestFirst(inputs(rows = rows)))
    }

    @Test
    fun `the divider's place is chosen once, so an agent row that lands later moves nothing`() {
        val opened = listOf(agentRow(1, "One"), userRow(2, "Two")).sortedByDescending { it.serverSeq }
        val anchor = unreadAnchor(opened, frontier = 2uL)
        assertNull(anchor)
        val later = opened + agentRow(3, "Three")
        assertFalse(ChatItem.Unread in oldestFirst(inputs(rows = later).copy(unreadAt = anchor)))
        // The owner's own rows and blank ones are not what the divider stands before.
        val mixed =
            listOf(
                userRow(3, "Mine"),
                agentRow(4, " "),
                agentRow(5, "Theirs"),
            ).sortedByDescending { it.serverSeq }
        assertEquals(5uL, unreadAnchor(mixed, frontier = 2uL))
    }

    @Test
    fun `a message with no time groups as if it came now, so a live bubble long after the last one stands alone`() {
        val streaming = LiveBubble(1, "Working on it", 0, fromCard = false)
        val live = ChatLive(turns = listOf(liveTurn("turn-m9", bubbles = listOf(streaming))))
        val shown = messages(oldestFirst(inputs(rows = listOf(agentRow(1, "Done.", minutes = 20)), live = live)))
        assertEquals(listOf(GroupPosition.Single, GroupPosition.Single), shown.map { it.position })
        val near = messages(oldestFirst(inputs(rows = listOf(agentRow(1, "Done.", minutes = 29)), live = live)))
        assertEquals(listOf(GroupPosition.First, GroupPosition.Last), near.map { it.position })
    }

    @Test
    fun `an outbox item is on its way, queued behind a turn, pending a connection, or refused with its card`() {
        val written = OutboxItem(msg("a", "written"), written = true)
        val waiting = OutboxItem(msg("b", "waiting"))
        val refused = OutboxItem(msg("c", "refused"), failure = RequestFailure("unsupported", "no"))
        val thinking = LiveCard(0L, emptyList(), emptyList(), speaking = false)
        val running = ChatLive(turns = listOf(liveTurn("turn-x", card = thinking)))

        val queued = messages(oldestFirst(inputs(outbox = listOf(written, waiting), live = running)))
        assertEquals(listOf(Delivery.SENDING, Delivery.QUEUED), queued.map { it.delivery })
        assertEquals(listOf(false, true), queued.map { it.editable })

        val offline = messages(oldestFirst(inputs(outbox = listOf(waiting), connected = false)))
        assertEquals(Delivery.PENDING, offline.single().delivery)

        val failed = oldestFirst(inputs(outbox = listOf(refused)))
        assertEquals(Delivery.FAILED, messages(failed).single().delivery)
        assertFalse(messages(failed).single().editable)
        val card = failed.filterIsInstance<ChatItem.Error>().single().error
        assertEquals(
            ShownError(ErrorLine.NOT_SENT, ErrorAction.RETRY_SENDING, Sender.User, "unsupported", refused.request),
            card,
        )
    }

    @Test
    fun `the owner's message keeps its key from the outbox through accepted to its row`() {
        val item = OutboxItem(msg("m9", "Restart it"), written = true)
        val outboxKey = chatItems(inputs(outbox = listOf(item))).filterIsInstance<ChatItem.Message>().single().key
        val bridged = chatItems(inputs(bridged = listOf(item))).filterIsInstance<ChatItem.Message>().single()
        val landed =
            chatItems(inputs(rows = listOf(userRow(9, "Restart it", clientMsgId = "m9")), bridged = listOf(item)))
        assertEquals("out:m9", outboxKey)
        assertEquals(outboxKey, bridged.key)
        assertEquals(Delivery.DELIVERED, bridged.message.delivery)
        assertEquals(listOf("out:m9"), landed.filterIsInstance<ChatItem.Message>().map { it.key })
    }

    @Test
    fun `a running turn shows its streaming bubble and its card, and a sealed bubble keeps its key as its row lands`() {
        val turn =
            liveTurn(
                "turn-m1",
                bubbles = listOf(LiveBubble(1, "The worker", 0, true, sealedSeq = 2uL, sealedWall = wallAt(1))),
                card = LiveCard(0L, listOf("Reading the logs"), emptyList(), speaking = true),
            )
        val live = ChatLive(turns = listOf(turn))
        val before = oldestFirst(inputs(rows = listOf(userRow(1, "Fix it")), live = live))
        // The bubble the card became keeps the card's key; the card shown again below it is the next bubble's.
        val keys = listOf("day:2026-09-27", "out:m1", "card:turn-m1:1", "card:turn-m1:2")
        assertEquals(keys, before.map { it.key })
        val after =
            oldestFirst(inputs(rows = listOf(userRow(1, "Fix it"), agentRow(2, "The worker restarts")), live = live))
        assertEquals(keys, after.map { it.key })
        assertEquals("The worker restarts", messages(after).last().text)
        assertFalse(messages(after).last().streaming)
    }

    @Test
    fun `a failed turn leaves its error card after its last bubble, and a cancelled one says Stopped`() {
        val failed =
            liveTurn(
                "turn-m1",
                ending = TurnEnding(TurnOutcome.Failed("timeout", "slow"), 5L, wallAt(3), 2uL),
                bubbles = listOf(LiveBubble(1, "Partial", 0, false, sealedSeq = 2uL, sealedWall = wallAt(2))),
            )
        val stopped = liveTurn("turn-m3", ending = TurnEnding(TurnOutcome.Stopped, 9L, wallAt(5), 3uL))
        val rows =
            listOf(
                userRow(1, "Fix it", minutes = 1),
                agentRow(2, "Partial", minutes = 2),
                userRow(3, "And the other", minutes = 4),
                agentRow(4, "Later", minutes = 6),
            )
        val items = oldestFirst(inputs(rows = rows, live = ChatLive(turns = listOf(failed, stopped))))
        val keys = items.map { it.key }
        assertEquals(keys.indexOf("turn:turn-m1:1") + 1, keys.indexOf("end:turn-m1"))
        assertEquals(keys.indexOf("out:m3") + 1, keys.indexOf("end:turn-m3"))
        val error = (items[keys.indexOf("end:turn-m1")] as ChatItem.Error).error
        assertEquals(ErrorLine.TIMEOUT, error.line)
        assertEquals(ErrorAction.RUN_AGAIN, error.action)
        assertEquals(msg("m1", "Fix it"), error.request)
        assertEquals(PillText.Stopped, (items[keys.indexOf("end:turn-m3")] as ChatItem.Pill).text)
    }

    @Test
    fun `a request refused before accepted shows on its outbox item, never as an agent's error card`() {
        val refused = OutboxItem(msg("m1", "Fix it"), failure = RequestFailure("turn_failed", "no"))
        val turn = liveTurn("turn-m1", ending = TurnEnding(TurnOutcome.Failed("turn_failed", "no"), 1L, wallAt(1), 0uL))
        val items =
            oldestFirst(inputs(outbox = listOf(refused), live = ChatLive(turns = listOf(turn), refused = setOf("m1"))))
        assertEquals(listOf(Sender.User), items.filterIsInstance<ChatItem.Error>().map { it.error.side })
    }

    @Test
    fun `each turn_error code has its line and its one action, and none says Nothing ran`() {
        val asked = msg("m1", "Fix it")
        assertEquals(ErrorAction.RESET_TO_DEFAULT, errorOf("model_unavailable", asked).action)
        assertEquals(ErrorAction.NONE, errorOf("unsupported", asked).action)
        assertEquals(ErrorLine.GENERIC, errorOf("interrupted", asked).line)
        assertEquals(ErrorLine.GENERIC, errorOf("turn_failed", asked).line)
        assertEquals(ErrorAction.RUN_AGAIN, errorOf("anything_else", asked).action)
        assertEquals(ErrorAction.RUN_AGAIN, errorOf("timeout", asked).action)
    }

    @Test
    fun `a failed turn whose request the chat does not hold offers no Run again, which would send nothing`() {
        assertEquals(ErrorAction.NONE, errorOf("anything_else", null).action)
        assertEquals(ErrorLine.TIMEOUT, errorOf("timeout", null).line)
        assertEquals(ErrorAction.NONE, errorOf("timeout", null).action)
        assertEquals(ErrorAction.RESET_TO_DEFAULT, errorOf("model_unavailable", null).action)
    }

    @Test
    fun `a notice and a model change stand after the row that was newest as they came`() {
        val live =
            ChatLive(
                pills =
                    listOf(
                        LivePill.Notice("Compacted", wallAt(3), 1uL),
                        LivePill.ModelChanged(
                            "Claude Opus 5.5",
                            toDefault = false,
                            note = "History is off",
                            wallMs = wallAt(4),
                            afterSeq = 2uL,
                        ),
                    ),
            )
        val items = oldestFirst(inputs(rows = listOf(agentRow(1, "One", 1), agentRow(2, "Two", 3)), live = live))
        val texts = items.map { (it as? ChatItem.Pill)?.text ?: (it as? ChatItem.Message)?.message?.text }
        assertEquals(
            listOf(
                null,
                "One",
                PillText.Notice("Compacted"),
                "Two",
                PillText.Switched("Claude Opus 5.5"),
                PillText.Note("History is off"),
            ),
            texts,
        )
    }

    @Test
    fun `a job's delivery wears its job, and a reply's row its turn and its model`() {
        val job = buildJsonObject { put("job", JsonPrimitive("nightly-report")) }
        val shown = messages(oldestFirst(inputs(rows = listOf(agentRow(1, "The report is ready.", metadata = job)))))
        assertEquals("nightly-report", shown.single().job)
        assertNull(shown.single().turnId)
        assertTrue(chatItems(inputs()).isEmpty())
    }
}
