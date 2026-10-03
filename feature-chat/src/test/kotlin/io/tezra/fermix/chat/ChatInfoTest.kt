package io.tezra.fermix.chat

import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.Route
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.transport.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Info on a message (design section 13.7) and Copy as transcript. */
class ChatInfoTest {
    private val turn =
        LiveTurn(
            turnId = "turn-m1",
            startedMono = 1_000L,
            startedWall = wallAt(0),
            tools = listOf(LiveChip("shell", running = false, status = "ok"), LiveChip("file_read", false, null)),
            thoughtMs = 12_000L,
            ending = TurnEnding(TurnOutcome.Completed, 42_000L, wallAt(1), 2uL),
            path = Candidate.Scope.LAN,
        )
    private val live = ChatLive(turns = listOf(turn), accepted = mapOf("m1" to wallAt(0)))

    @Test
    fun `the owner's message has its delivery and the turn that answered it`() {
        val mine = ShownMessage(Sender.User, "Fix it", wallAt(0), Delivery.DELIVERED, seq = 1uL, clientMsgId = "m1")
        val info = infoOf(mine, live)
        assertEquals(wallAt(0), info.deliveredWall)
        assertEquals(TurnInfo(41_000L, 12_000L, turn.tools, Candidate.Scope.LAN), info.turn)
    }

    @Test
    fun `a reply has its model and its turn`() {
        val route = Route("openai", "gpt-6-astra")
        val reply =
            ShownMessage(Sender.Agent, "Done", wallAt(1), Delivery.NONE, seq = 2uL, turnId = "turn-m1", route = route)
        val info = infoOf(reply, live)
        assertEquals(route, info.route)
        assertEquals(2, info.turn?.tools?.size)
    }

    @Test
    fun `a transcript is each message's name, time and words`() {
        val messages =
            listOf(
                ShownMessage(Sender.User, "Why?", wallAt(0), Delivery.DELIVERED),
                ShownMessage(Sender.Agent, "Because.", null, Delivery.NONE),
            )
        val text = transcript(messages, { if (it == Sender.User) "You" else HOST }, { "09:00" })
        assertEquals("You · 09:00\nWhy?\n\nsuj-mbp\nBecause.", text)
    }
}

/** The long-press menu and the outbox item's tap menu (design sections 13.6 and 13.7). */
class MenusTest {
    private val answer = ShownMessage(Sender.Agent, "Run:\n\n```sh\nls\n```", wallAt(0), Delivery.NONE)

    @Test
    fun `the long-press menu is Copy, Select text, Copy code, Share, Info, Retry, in that order`() {
        assertEquals(
            listOf(MenuEntry.COPY, MenuEntry.SELECT_TEXT, MenuEntry.COPY_CODE, MenuEntry.SHARE, MenuEntry.INFO),
            menuOf(answer),
        )
        val refused = ShownMessage(Sender.User, "Fix it", null, Delivery.FAILED, clientMsgId = "m1")
        assertEquals(
            listOf(MenuEntry.COPY, MenuEntry.SELECT_TEXT, MenuEntry.SHARE, MenuEntry.INFO, MenuEntry.RETRY),
            menuOf(refused),
        )
        assertEquals("ls", codeOf(answer))
    }

    @Test
    fun `a tap offers Edit and Remove until the frame was written, and Try again once refused`() {
        val queued = ShownMessage(Sender.User, "Fix it", null, Delivery.QUEUED, clientMsgId = "m1", editable = true)
        val sending = queued.copy(delivery = Delivery.SENDING, editable = false)
        val refused = queued.copy(delivery = Delivery.FAILED, editable = false)
        assertEquals(listOf(OutboxEntry.EDIT, OutboxEntry.REMOVE), outboxMenuOf(queued))
        assertEquals(emptyList<OutboxEntry>(), outboxMenuOf(sending))
        assertEquals(listOf(OutboxEntry.TRY_AGAIN, OutboxEntry.REMOVE_FROM_OUTBOX), outboxMenuOf(refused))
    }
}
