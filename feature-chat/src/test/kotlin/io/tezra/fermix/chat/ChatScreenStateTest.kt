package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.session.withReaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The outbox's bridge to the rows, a reaction-only turn as the screen shows it, and the scroll pill's count. */
class ChatScreenStateTest {
    private val sent = OutboxItem(msg("m1", "Fix it"), written = true)
    private val stop = OutboxItem(ClientEvent.Command("c1", PROFILE, STOP_COMMAND, null), written = true)

    @Test
    fun `a message that left the outbox is bridged until its row lands, unless the owner took it back`() {
        val bridged = bridgedAfter(emptyList(), listOf(sent, stop), emptyList(), emptyList(), emptySet())
        assertEquals(listOf(sent), bridged)
        assertEquals(
            emptyList<OutboxItem>(),
            bridgedAfter(
                bridged,
                emptyList(),
                emptyList(),
                listOf(userRow(4, "Fix it", clientMsgId = "m1")),
                emptySet(),
            ),
        )
        assertEquals(
            emptyList<OutboxItem>(),
            bridgedAfter(emptyList(), listOf(sent), emptyList(), emptyList(), setOf("m1")),
        )
    }

    @Test
    fun `a reaction-only turn shows no card and no empty bubble, only the chip on the owner's message`() {
        val turn = "turn-m1"
        val effects =
            listOf(
                TurnEffect.CardShown(turn),
                TurnEffect.CardRemoved(turn),
                TurnEffect.TurnEnded(turn, TurnOutcome.Completed),
            )
        val live =
            effects.foldIndexed(ChatLive()) { index, folded, effect ->
                folded.after(
                    SessionEvent.Turn(effect, daemonSpeaking = false),
                    Moment(index * 1_000L, wallAt(1), null, 1uL),
                )
            }
        val row = (userRow(1, "Thanks, that fixed it", clientMsgId = "m1") as TimelineRow.Message).withReaction("👍")
        val inputs =
            ChatInputs(
                rows = listOf(row),
                outbox = emptyList(),
                bridged = emptyList(),
                live = live,
                connected = true,
                unreadAt = null,
                requests = emptyMap(),
                profileId = PROFILE,
                nowWall = wallAt(2),
                zone = UTC,
            )
        val items = chatItems(inputs).filterNot { it is ChatItem.Day }
        val message = (items.single() as ChatItem.Message).message
        assertEquals(Sender.User, message.sender)
        assertEquals("👍", message.reaction)
        assertTrue(live.turns.none { it.live || it.card != null || it.bubbles.isNotEmpty() })
    }

    @Test
    fun `the scroll pill counts the agent's rows past what the owner has seen`() {
        val items =
            listOf(
                ChatItem.Message("a", ShownMessage(Sender.Agent, "b", null, Delivery.NONE, seq = 5uL)),
                ChatItem.Message("b", ShownMessage(Sender.User, "c", null, Delivery.DELIVERED, seq = 4uL)),
                ChatItem.Message("c", ShownMessage(Sender.Agent, "d", null, Delivery.NONE, seq = 3uL)),
                ChatItem.Message("d", ShownMessage(Sender.Agent, "live", null, Delivery.NONE, streaming = true)),
            )
        assertEquals(1, unseen(items, 3uL))
        assertEquals(2, unseen(items, null))
    }
}
