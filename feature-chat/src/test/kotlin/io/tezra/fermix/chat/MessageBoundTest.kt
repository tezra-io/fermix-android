package io.tezra.fermix.chat

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.data.Instance
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.CommandDescriptor
import io.tezra.fermix.protocol.MAX_HEADER_BYTES
import io.tezra.fermix.protocol.encodeClientEvent
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Words past what one frame's header carries (PROTOCOL.md: "The JSON header is at most 4,096 bytes"). */
private val TOO_LONG = "word ".repeat(1_000)

/** The most of [letter] one `msg` carries, by the one bound (fitsOneMsg). */
private fun mostThatFits(letter: Char): String {
    var words = ""
    repeat(MAX_HEADER_BYTES) {
        val longer = words + letter
        if (!fitsOneMsg(longer, PROFILE)) return words
        words = longer
    }
    error("$MAX_HEADER_BYTES of '$letter' fit one msg")
}

/**
 * A message the owner types, pastes, edits back or captions past what one frame carries (PROTOCOL.md: a header of at
 * most 4,096 bytes, and `event_part` the daemon's alone): Send sends nothing and the field keeps every word, the line
 * over the composer says so, and the app goes on; a request the owner did not type, Run again's or a model's pick, is
 * weighed as the session encodes it, its words whole and its `retry_of` the row's own id, and a command the daemon
 * names past the wire's rule for a name is never one. Over a fake session that refuses what Session's codec refuses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageBoundTest {
    private val main = StandardTestDispatcher()

    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    private inner class Rig(
        scope: TestScope,
        record: Instance = sample(),
    ) {
        val store = FakeChatStore()
        val session = FakeChatSession(store)
        val live = MutableStateFlow(ChatLive())
        val log = FakeLog()
        val pipeline = FakePipeline()
        private val parts =
            fakeParts(record, session, store, scope.backgroundScope).copy(
                live = live,
                media = pipeline,
                files = FakeChatFiles(store),
                log = log.log,
                scratch = { File.createTempFile("scratch", null, dir) },
                io = main,
            )
        val model: ChatViewModel =
            ViewModelProvider
                .create(ViewModelStore(), viewModelFactory { initializer { ChatViewModel(parts, SavedStateHandle()) } })
                .get(ChatViewModel::class)

        fun type(words: String) = model.composer.edit(TextFieldValue(words))

        val field: String get() = model.composer.field.value.text

        fun logged(part: String): Boolean = log.lines.value.any { part in it }
    }

    /** Run again on the failed turn of the owner's [row], as its card's button sends it. */
    private fun TestScope.runAgain(
        rig: Rig,
        row: TimelineRow.Message,
    ) {
        val id = checkNotNull(row.message.clientMsgId)
        rig.store.rows.value = listOf(row)
        runCurrent()
        val turn = "turn-$id"
        val moment = Moment(0L, wallAt(1), Candidate.Scope.LAN, 1uL)
        rig.live.value =
            listOf(
                SessionEvent.Accepted(id, duplicate = false),
                SessionEvent.Turn(TurnEffect.CardShown(turn), daemonSpeaking = false),
                SessionEvent.Turn(
                    TurnEffect.TurnEnded(turn, TurnOutcome.Failed("turn_failed", "it broke")),
                    daemonSpeaking = false,
                ),
            ).fold(ChatLive()) { live, event -> live.after(event, moment) }
        runCurrent()
        val card =
            checkNotNull(rig.model.state.value)
                .items
                .filterIsInstance<ChatItem.Error>()
                .single()
                .error
        rig.model.requests.retry(checkNotNull(card.request))
        runCurrent()
    }

    @Test
    fun `a message past one frame stays in the field whole when Send is tapped, nothing goes, and the line says so`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            assertTrue(TOO_LONG.encodeToByteArray().size > MAX_HEADER_BYTES)
            rig.type(TOO_LONG)
            var taken = false
            rig.model.composer.send { taken = true }
            runCurrent()
            assertEquals(TOO_LONG, rig.field)
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertTrue(!taken, "Send's haptic played for nothing sent")
            assertTrue(rig.model.composer.tooLong.value, "no line says the message is too long")
        }

    @Test
    fun `the most one msg carries goes, and the line shows only past it`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            val most = mostThatFits('a')
            rig.type(most + "a")
            runCurrent()
            assertTrue(rig.model.composer.tooLong.value)
            rig.type(most)
            runCurrent()
            assertTrue(!rig.model.composer.tooLong.value)
            rig.model.composer.send {}
            runCurrent()
            assertEquals(
                listOf(most),
                rig.session.sent.value
                    .map { (it as ClientEvent.Msg).text },
            )
            assertEquals("", rig.field)
        }

    @Test
    fun `words under the bound in characters but past it in the header's bytes stay in the field and go nowhere`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            // Four bytes a character, two as JSON escapes a quote, six as it escapes a control character.
            val heavy = listOf("\uD83D\uDE00".repeat(1_100), "\"".repeat(2_500), "\u0001".repeat(1_000))
            heavy.forEach { words ->
                assertTrue(words.length < MAX_HEADER_BYTES && !fitsOneMsg(words, PROFILE), "${words.length}")
                rig.type(words)
                rig.model.composer.send {}
                runCurrent()
                assertEquals(words, rig.field)
                assertTrue(rig.model.composer.tooLong.value)
            }
            rig.model.attach.add(listOf("content://photos/1.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.model.composer.caption(TextFieldValue(heavy.first()))
            rig.model.attach.send {}
            runCurrent()
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertEquals(1, rig.model.attach.ui.value.picked.size)
            assertEquals(heavy.first(), rig.field)
        }

    @Test
    fun `a caption past one frame sends nothing of the tray, makes nothing, and keeps the tray and the field`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.attach.add(listOf("content://photos/1.png"), PickedFrom.PHOTOS)
            runCurrent()
            rig.model.composer.caption(TextFieldValue(TOO_LONG))
            rig.model.attach.send {}
            runCurrent()
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertEquals(emptyList<Pair<String, Boolean>>(), rig.pipeline.prepared.value)
            assertEquals(1, rig.model.attach.ui.value.picked.size)
            assertEquals(TOO_LONG, rig.field)
            assertTrue(rig.model.composer.tooLong.value)
        }

    @Test
    fun `an outbox item edited back past one frame stays in the field, and Send sends nothing`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            // An item no session took this way, as a store from an older build may hold it.
            rig.store.outbox.value = listOf(OutboxItem(msg("q1", TOO_LONG)))
            runCurrent()
            val shown =
                checkNotNull(rig.model.state.value)
                    .items
                    .filterIsInstance<ChatItem.Message>()
                    .single { it.message.clientMsgId == "q1" }
                    .message
            rig.model.edit(shown)
            runCurrent()
            assertEquals(TOO_LONG, rig.field)
            rig.model.composer.send {}
            runCurrent()
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertEquals(TOO_LONG, rig.field)
        }

    @Test
    fun `a slash command whose arguments are past one frame sends nothing and keeps the field`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            val typed = "/compact $TOO_LONG"
            rig.type(typed)
            rig.model.composer.send {}
            runCurrent()
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertEquals(typed, rig.field)
            assertTrue(rig.model.composer.tooLong.value)
        }

    @Test
    fun `shared words land cut to what one msg carries, and the field they make goes`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.type("x".repeat(2_000))
            rig.model.share(Shared(emptyList(), "y".repeat(3_000), refused = emptyList(), past = 0))
            runCurrent()
            assertTrue(!rig.model.composer.tooLong.value)
            rig.model.composer.send {}
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
        }

    @Test
    fun `Run again on a row whose words are past one frame runs nothing, and says so in the log`() =
        runTest(main) {
            val rig = Rig(this)
            runAgain(rig, userRow(1, TOO_LONG, clientMsgId = "m9") as TimelineRow.Message)
            assertEquals(emptyList<Any>(), rig.session.retried.value)
            assertTrue(rig.logged("codec refuses"), "${rig.log.lines.value}")
        }

    @Test
    fun `Run again on a row whose words fit once trimmed, but not whole as the session sends them, runs nothing`() =
        runTest(main) {
            val rig = Rig(this)
            val words = mostThatFits('a') + "\n".repeat(300)
            assertTrue(fitsOneMsg(words.trim(), PROFILE) && !fitsOneMsg(words, PROFILE))
            runAgain(rig, userRow(1, words, clientMsgId = "m9") as TimelineRow.Message)
            assertEquals(emptyList<Any>(), rig.session.retried.value)
            assertTrue(rig.logged("codec refuses"), "${rig.log.lines.value}")
        }

    @Test
    fun `Run again on a row whose id from the wire is past what one retry_of carries runs nothing`() =
        runTest(main) {
            val rig = Rig(this)
            runAgain(rig, userRow(1, "export it", clientMsgId = "w".repeat(4_000)) as TimelineRow.Message)
            assertEquals(emptyList<Any>(), rig.session.retried.value)
            assertTrue(rig.logged("codec refuses"), "${rig.log.lines.value}")
        }

    @Test
    fun `a command the daemon names past the wire's rule for a name is never offered, and its words go as a msg`() =
        runTest(main) {
            val unsendable = CommandDescriptor("web-search", emptyList(), "Search the web.")
            val rig = Rig(this, sample().let { it.copy(caps = it.caps?.copy(commands = COMMANDS + unsendable)) })
            runCurrent()
            assertEquals(
                COMMANDS,
                rig.model.state.value
                    ?.commands,
            )
            rig.type("/web-search kotlin")
            rig.model.composer.send {}
            runCurrent()
            val sent =
                rig.session.sent.value
                    .single()
            assertEquals("/web-search kotlin", (sent as ClientEvent.Msg).text)
            assertEquals("", rig.field)
        }

    @Test
    fun `a model the daemon names past what one frame carries is never sent as the pick`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            val named = ModelRow.Choice("anthropic", "m".repeat(5_000), "Long", null, liveTyping = true, active = false)
            rig.model.models.pick(named, turnRuns = false)
            runCurrent()
            assertEquals(emptyList<ClientEvent>(), rig.session.sent.value)
            assertTrue(
                rig.log.lines.value
                    .any { "codec refuses" in it },
                "${rig.log.lines.value}",
            )
        }

    @Test
    fun `a command whose words fit one msg encodes, at the largest seq, under the one bound`() {
        val name = "c".repeat(128)
        val args = mostThatFits('a').drop(name.length + 2)
        val command = ClientEvent.Command("model-pick:" + "0".repeat(36), PROFILE, name, args)
        assertTrue(fitsOneMsg("/$name $args", PROFILE))
        encodeClientEvent(2, ULong.MAX_VALUE, command)
    }
}
