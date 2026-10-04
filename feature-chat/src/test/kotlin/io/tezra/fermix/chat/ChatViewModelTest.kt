package io.tezra.fermix.chat

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.data.ChatState
import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.OutboxAttachment
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** A minute, as the samples' times count. */
private const val MINUTE_MS = 60_000L

/**
 * The chat's ViewModel over a fake session and cache (design sections 13.5 and 13.6): the draft restored,
 * kept after the debounce and as the chat leaves; the read frontier marked once and never backwards; the
 * unread divider where the chat opened; the banner after 2 s; Edit only while the frame was never written;
 * and a failed turn never run again unless the owner asks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val main = StandardTestDispatcher()

    @BeforeEach
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    /**
     * The chat over the fakes: its session is there as it opens unless a test takes it out of [sessions] first
     * (the supervisor may open it later); [cache] is the store the ViewModel reads, the fake itself unless a
     * test slows it as a slow database is (SlowStore).
     */
    private class Rig(
        scope: TestScope,
        rows: List<TimelineRow> = emptyList(),
        draft: String? = null,
        frontier: ULong = 0uL,
        state: SessionState = UP,
        cache: (FakeChatStore) -> ChatStore = { it },
    ) {
        val store = FakeChatStore(rows, draft, frontier)
        val session = FakeChatSession(store, state)
        val sessions = MutableStateFlow<ChatSession?>(session)
        val live = MutableStateFlow(ChatLive())
        val network = MutableStateFlow(ONLINE)
        val presence = FakePresence()
        val log = FakeLog()

        /** The activity's store of ViewModels, whose clear is the chat's end. */
        val viewModels = ViewModelStore()
        private val parts =
            fakeParts(sample(), session, store, scope.backgroundScope)
                .copy(
                    session = sessions,
                    live = live,
                    network = network,
                    presence = presence,
                    store = cache(store),
                    log = log.log,
                )
        val model: ChatViewModel =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { ChatViewModel(parts) } })
                .get(ChatViewModel::class)

        val shown: ChatScreenState get() = checkNotNull(model.state.value) { "the chat shows nothing yet" }

        fun messages(): Map<String?, ShownMessage> =
            shown.items.filterIsInstance<ChatItem.Message>().associate { it.message.clientMsgId to it.message }
    }

    @Test
    fun `the draft comes back as the chat opens, and is kept 400 ms after the owner stops typing`() =
        runTest(main) {
            val rig = Rig(this, draft = "restart the worker")
            runCurrent()
            assertEquals("restart the worker", rig.model.composer.field.value.text)
            advanceTimeBy(DRAFT_DEBOUNCE_MS + 1)
            rig.store.drafts.value = emptyList()
            rig.model.composer.edit(TextFieldValue("restart the w"))
            advanceTimeBy(DRAFT_DEBOUNCE_MS - 100)
            rig.model.composer.edit(TextFieldValue("restart the web tier"))
            advanceTimeBy(DRAFT_DEBOUNCE_MS - 1)
            runCurrent()
            assertEquals(emptyList<String?>(), rig.store.drafts.value)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("restart the web tier"), rig.store.drafts.value)
        }

    @Test
    fun `leaving keeps the draft at once, and so does the chat's end, which reports it off screen`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.composer.edit(TextFieldValue("half a thought"))
            rig.model.composer.keep()
            runCurrent()
            assertEquals(listOf("half a thought"), rig.store.drafts.value)
            rig.model.listed(4uL)
            rig.model.composer.edit(TextFieldValue("half a thought, and more"))
            rig.viewModels.clear()
            runCurrent()
            assertEquals(
                "half a thought, and more",
                rig.store.drafts.value
                    .last(),
            )
            assertEquals(listOf(4uL, null), rig.presence.reports.value)
        }

    @Test
    fun `reaching the bottom marks the newest row read once, never backwards and never below the frontier`() =
        runTest(main) {
            val rows = listOf(userRow(1, "a"), agentRow(2, "b"), agentRow(3, "c"))
            val rig = Rig(this, rows = rows, frontier = 1uL)
            runCurrent()
            rig.model.reachedBottom()
            runCurrent()
            rig.model.reachedBottom()
            runCurrent()
            assertEquals(listOf(3uL), rig.session.read.value)
            // Another device read up to 5 meanwhile: nothing to mark.
            rig.store.frontier.value = 5uL
            rig.store.rows.update { listOf(agentRow(5, "e"), agentRow(4, "d")) + it }
            runCurrent()
            rig.model.reachedBottom()
            runCurrent()
            assertEquals(listOf(3uL), rig.session.read.value)
            rig.store.rows.update { listOf(agentRow(6, "f")) + it }
            runCurrent()
            rig.model.reachedBottom()
            runCurrent()
            assertEquals(listOf(3uL, 6uL), rig.session.read.value)
        }

    @Test
    fun `the unread divider stays at the frontier the chat opened on`() =
        runTest(main) {
            val rows = (1..4).map { agentRow(it, "row $it", minutes = it.toLong()) }
            val rig = Rig(this, rows = rows, frontier = 2uL)
            runCurrent()

            fun belowDivider(): String =
                rig.shown.items
                    .map { it.key }
                    .let { keys -> keys[keys.indexOf("unread") + 1] }
            assertEquals("row:2", belowDivider())
            rig.model.reachedBottom()
            rig.store.frontier.value = 4uL
            runCurrent()
            assertEquals("row:2", belowDivider())
        }

    @Test
    fun `the banner shows once its condition has held 2 s, and goes at once`() =
        runTest(main) {
            val rig = Rig(this, state = SessionState.CannotReach)
            runCurrent()
            assertNull(rig.shown.banner)
            advanceTimeBy(BANNER_DELAY_MS - 1)
            runCurrent()
            assertNull(rig.shown.banner)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(Banner.UNREACHABLE, rig.shown.banner)
            rig.session.state.value = UP
            runCurrent()
            assertNull(rig.shown.banner)
            rig.network.value = NetworkFacts.NONE
            advanceTimeBy(BANNER_DELAY_MS)
            runCurrent()
            assertEquals(Banner.OFFLINE, rig.shown.banner)
        }

    @Test
    fun `Edit takes a queued item's words back only while its frame was never written`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.store.outbox.value =
                listOf(
                    OutboxItem(msg("q1", "check the disk"), written = true),
                    OutboxItem(msg("q2", "empty the trash")),
                )
            runCurrent()
            val shown = rig.messages()
            rig.model.edit(shown.getValue("q1"))
            runCurrent()
            assertEquals("", rig.model.composer.field.value.text)
            assertEquals(
                listOf("q1", "q2"),
                rig.store.outbox.value
                    .map { it.clientMsgId },
            )
            rig.model.edit(shown.getValue("q2"))
            runCurrent()
            assertEquals("empty the trash", rig.model.composer.field.value.text)
            assertEquals(
                listOf("q1"),
                rig.store.outbox.value
                    .map { it.clientMsgId },
            )
            assertTrue("q2" !in rig.messages())
        }

    @Test
    fun `a failed turn is never run again on its own, only by Run again, under a new id`() =
        runTest(main) {
            val rig = Rig(this, rows = listOf(userRow(1, "export it", clientMsgId = "m9")))
            runCurrent()
            val turn = "turn-m9"
            val moment = Moment(0L, wallAt(1), Candidate.Scope.LAN, 1uL)
            rig.live.value =
                listOf(
                    SessionEvent.Accepted("m9", duplicate = false),
                    SessionEvent.Turn(TurnEffect.CardShown(turn), daemonSpeaking = false),
                    SessionEvent.Turn(
                        TurnEffect.TurnEnded(turn, TurnOutcome.Failed("turn_failed", "it broke")),
                        daemonSpeaking = false,
                    ),
                ).fold(ChatLive()) { live, event -> live.after(event, moment) }
            advanceTimeBy(10 * MINUTE_MS)
            runCurrent()
            assertEquals(emptyList<Any>(), rig.session.sent.value)
            assertEquals(emptyList<Any>(), rig.session.retried.value)
            val card =
                rig.shown.items
                    .filterIsInstance<ChatItem.Error>()
                    .single()
                    .error
            assertEquals(ErrorAction.RUN_AGAIN, card.action)
            rig.model.requests.retry(checkNotNull(card.request) { "the card holds its request" })
            runCurrent()
            assertEquals(listOf(msg("m9", "export it") to "m1"), rig.session.retried.value)
            assertEquals(emptyList<Any>(), rig.session.sent.value)
        }

    @Test
    fun `an agent row that lands after a chat opened fully read brings no divider`() =
        runTest(main) {
            val rig = Rig(this, rows = listOf(userRow(1, "a"), agentRow(2, "b")), frontier = 2uL)
            runCurrent()
            rig.store.rows.update { listOf(agentRow(3, "c")) + it }
            runCurrent()
            assertEquals(listOf("c", "b", "a"), rig.shown.items.mapNotNull { (it as? ChatItem.Message)?.message?.text })
            assertTrue(ChatItem.Unread !in rig.shown.items)
        }

    @Test
    fun `a chat that opened with no rows puts no divider above the first that lands while it is open`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.store.rows.value = listOf(agentRow(1, "Hello from suj-mbp."))
            runCurrent()
            val texts = rig.shown.items.mapNotNull { (it as? ChatItem.Message)?.message?.text }
            assertEquals(listOf("Hello from suj-mbp."), texts)
            assertTrue(ChatItem.Unread !in rig.shown.items)
        }

    @Test
    fun `nothing shows before the cache's first page, and the first state holds the answers that came before`() =
        runTest(main) {
            val rows = listOf(agentRow(2, "Done.", minutes = 1), userRow(1, "export it", clientMsgId = "m9"))
            val rig = Rig(this, rows = rows, cache = { SlowStore(it, chatAfterMs = 0L, rowsAfterMs = 20L) })
            // An answer this process saw sealed by a bare text_done: its words are its row's.
            val turn = "turn-m9"
            val moment = Moment(0L, wallAt(1), Candidate.Scope.LAN, 2uL)
            rig.live.value =
                listOf(
                    TurnEffect.BubbleOpened(turn, 1, fromCard = true),
                    TurnEffect.BubbleSealed(turn, 1, 2uL),
                    TurnEffect.TurnEnded(turn, TurnOutcome.Completed),
                ).fold(ChatLive()) { live, effect ->
                    live.after(SessionEvent.Turn(effect, daemonSpeaking = false), moment)
                }
            advanceTimeBy(10L)
            runCurrent()
            assertNull(rig.model.state.value)
            advanceTimeBy(20L)
            runCurrent()
            assertEquals(2uL, rig.shown.newestSeq)
            assertEquals(listOf(Arrival(turn, "Done.")), rig.shown.arrived)
        }

    @Test
    fun `the bottom reached before a session comes is read once one does`() =
        runTest(main) {
            val rows = listOf(userRow(1, "a"), agentRow(2, "b"), agentRow(3, "c"))
            val rig = Rig(this, rows = rows, frontier = 1uL)
            rig.sessions.value = null
            runCurrent()
            rig.model.reachedBottom()
            runCurrent()
            assertEquals(emptyList<ULong>(), rig.session.read.value)
            rig.sessions.value = rig.session
            runCurrent()
            // The screen asks again as the link changes (ListEffects).
            rig.model.reachedBottom()
            runCurrent()
            assertEquals(listOf(3uL), rig.session.read.value)
        }

    @Test
    fun `a chat that leaves before its draft came back keeps that draft`() =
        runTest(main) {
            val slow = { store: FakeChatStore -> SlowStore(store, chatAfterMs = 100L, rowsAfterMs = 0L) }
            val rig = Rig(this, draft = "restart the worker", cache = slow)
            advanceTimeBy(50L)
            runCurrent()
            assertEquals("", rig.model.composer.field.value.text)
            rig.viewModels.clear()
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(emptyList<String?>(), rig.store.drafts.value)
            assertEquals("restart the worker", rig.store.state.value.draft)
        }

    @Test
    fun `the top asks for the page before where the last one said, once the session can take it, until it lands`() =
        runTest(main) {
            val rig = Rig(this, rows = (5..8).map { agentRow(it, "row $it", minutes = it.toLong()) })
            rig.live.value = ChatLive(older = Older.Before(3uL))
            runCurrent()
            rig.session.connected.value = false
            rig.model.reachedTop()
            runCurrent()
            assertEquals(emptyList<ULong>(), rig.session.older.value)
            assertTrue(ChatItem.Older !in rig.shown.items)
            rig.session.connected.value = true
            rig.model.reachedTop()
            runCurrent()
            assertEquals(listOf(3uL), rig.session.older.value)
            assertEquals(ChatItem.Older, rig.shown.items.last())
            rig.store.rows.update { it + agentRow(2, "row 2", minutes = 2) }
            runCurrent()
            assertTrue(ChatItem.Older !in rig.shown.items)
        }

    @Test
    fun `Retry sending names each attachment afresh, so an id the daemon let go is never named again`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            val photo =
                OutboxAttachment("a1", AttachKind.IMAGE, "image/jpeg", 5, "ab".repeat(32), "p.jpg", "/s/a1", true)
            val request = ClientEvent.Msg("q1", PROFILE, "look", listOf("a1"))
            val failure = RequestFailure("attachment_unavailable", "a1 is past its 48 hours")
            rig.store.outbox.value = listOf(OutboxItem(request, failure = failure, attachments = listOf(photo)))
            runCurrent()
            rig.model.requests.resend(request, listOf(photo))
            runCurrent()
            val item =
                rig.store.outbox.value
                    .single()
            val again = item.request as ClientEvent.Msg
            val fresh = item.attachments.single()
            assertEquals(photo.copy(attachId = fresh.attachId, uploaded = false), fresh)
            assertTrue(fresh.attachId != "a1")
            assertEquals(listOf(fresh.attachId), again.attachIds)
        }

    @Test
    fun `Retry sending sends the refused request under a new id, naming nothing in retry_of, and takes it out`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            val refused = OutboxItem(msg("q1", "check the disk"), failure = RequestFailure("rate_limited", "slow"))
            rig.store.outbox.value = listOf(refused)
            runCurrent()
            rig.model.requests.resend(refused.request)
            runCurrent()
            val again =
                rig.session.sent.value
                    .single() as ClientEvent.Msg
            assertEquals("check the disk", again.text)
            assertEquals("m1", again.clientMsgId)
            assertNull(again.retryOf)
            assertEquals(listOf("q1"), rig.session.removed.value)
            assertEquals(
                emptyList<ShownMessage>(),
                rig
                    .messages()
                    .filterKeys { it == "q1" }
                    .values
                    .toList(),
            )
        }

    @Test
    fun `a typed stop goes at once as Stop does, never into the outbox, and empties the field once it went`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.session.connected.value = false
            rig.model.composer.edit(TextFieldValue("/stop"))
            rig.model.composer.send {}
            runCurrent()
            assertEquals("/stop", rig.model.composer.field.value.text)
            rig.session.connected.value = true
            rig.model.composer.send {}
            runCurrent()
            assertEquals(listOf("m2"), rig.session.stopped.value)
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertEquals(emptyList<OutboxItem>(), rig.store.outbox.value)
            assertEquals("", rig.model.composer.field.value.text)
            assertEquals(1, rig.log.lines.value.size)
        }

    @Test
    fun `Remove of an item written meanwhile leaves it in the outbox and says so in the log`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.store.outbox.value = listOf(OutboxItem(msg("q1", "check the disk")))
            runCurrent()
            val shown = rig.messages().getValue("q1")
            rig.store.outbox.value = listOf(OutboxItem(msg("q1", "check the disk"), written = true))
            rig.model.remove(shown)
            runCurrent()
            assertEquals(
                listOf("q1"),
                rig.store.outbox.value
                    .map { it.clientMsgId },
            )
            assertEquals(1, rig.log.lines.value.size)
        }
}

/** [store] with its chat's row held back [chatAfterMs] and its rows [rowsAfterMs], as a slow database gives them. */
private class SlowStore(
    private val store: FakeChatStore,
    private val chatAfterMs: Long,
    private val rowsAfterMs: Long,
) : ChatStore by store {
    override fun chat(): Flow<ChatState> =
        flow {
            delay(chatAfterMs)
            emitAll(store.chat())
        }

    override fun newest(limit: Int): Flow<List<TimelineRow>> =
        flow {
            delay(rowsAfterMs)
            emitAll(store.newest(limit))
        }
}
