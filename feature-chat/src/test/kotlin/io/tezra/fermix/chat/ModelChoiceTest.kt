package io.tezra.fermix.chat

import androidx.compose.ui.text.font.FontStyle
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.ModelRef
import io.tezra.fermix.protocol.ModelSource
import io.tezra.fermix.protocol.ModelState
import io.tezra.fermix.session.OneShot
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

private val ASTRA = ModelRef("codex", "gpt-6-astra", "GPT-6 Astra")
private val OPUS = ModelRef("anthropic", "claude-opus-5.5", "Claude Opus 5.5")

/**
 * The model chip and its "Model" sheet (design section 8.6): the chip seeded by `hello_ack`'s
 * `caps.model_state`, then by each `model_changed`, at 70 % on the default and tonal with its dot on an
 * override, disabled only offline; the sheet's rows; a pick as `command{model}`, and one made mid-turn waiting
 * for `turn_done`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelChoiceTest {
    @Test
    fun `the chip is the config's default, then the chat's own, then what the daemon last said`() {
        val defaulted = ModelState(ASTRA)
        assertEquals(
            ModelChip("GPT-6 Astra", "C", overridden = false, enabled = true),
            modelChipOf(null, defaulted, true),
        )
        val own = ModelState(ASTRA, OPUS)
        assertEquals(ModelChip("Claude Opus 5.5", "A", overridden = true, enabled = true), modelChipOf(null, own, true))
        val reset = LiveModel("codex", "gpt-6-astra", "GPT-6 Astra", ModelSource.DEFAULT)
        assertFalse(checkNotNull(modelChipOf(reset, own, true)).overridden)
        assertFalse(checkNotNull(modelChipOf(null, own, connected = false)).enabled)
        assertNull(modelChipOf(null, null, true), "a daemon that says no model has no switcher")
    }

    @Test
    fun `the sheet lists the default, each provider's models under its name, and greys one it cannot list`() {
        val rows = modelRowsOf(ENTRIES, "GPT-6 Astra", overridden = true)
        val expected =
            listOf(
                ModelRow.Default("GPT-6 Astra", active = false),
                ModelRow.Group("Codex", unavailable = false),
                ModelRow.Choice("codex", "gpt-6-sol", "GPT-6 Sol", null, liveTyping = true, active = false),
                ModelRow.Choice(
                    "codex",
                    "gpt-6-luna",
                    "GPT-6 Luna",
                    "fast, cheaper",
                    liveTyping = true,
                    active = false,
                ),
                ModelRow.Group("Anthropic", unavailable = false),
                ModelRow.Choice(
                    "anthropic",
                    "claude-opus-5.5",
                    "Claude Opus 5.5",
                    "best quality",
                    false,
                    active = true,
                ),
                ModelRow.Choice("anthropic", "claude-haiku-4.5", "Claude Haiku 4.5", "fastest", false, active = false),
                ModelRow.Group("Ollama", unavailable = true),
                ModelRow.Unlisted,
            )
        assertEquals(expected, rows)
        val onDefault = modelRowsOf(ENTRIES, "GPT-6 Astra", overridden = false)
        assertEquals(ModelRow.Default("GPT-6 Astra", active = true), onDefault.first())
        assertTrue(onDefault.filterIsInstance<ModelRow.Choice>().none { it.active }, "the default carries the ✓")
    }

    @Test
    fun `the model the daemon marks default is the Default row's alone, unless the chat picked it as its own`() {
        val astra = ModelEntry("codex", "gpt-6-astra", "GPT-6 Astra", isDefault = true)
        val listed = listOf(astra) + ENTRIES.take(2)
        val codex = { rows: List<ModelRow> -> rows.filterIsInstance<ModelRow.Choice>().map { it.model } }
        assertEquals(listOf("gpt-6-sol", "gpt-6-luna"), codex(modelRowsOf(listed, "GPT-6 Astra", overridden = false)))
        val own = listOf(astra.copy(active = true)) + ENTRIES.take(2)
        assertEquals(
            listOf("gpt-6-astra", "gpt-6-sol", "gpt-6-luna"),
            codex(modelRowsOf(own, "GPT-6 Astra", overridden = true)),
        )
        val alone = modelRowsOf(listOf(astra), "GPT-6 Astra", overridden = false)
        assertEquals(listOf<ModelRow>(ModelRow.Default("GPT-6 Astra", active = true)), alone)
        assertEquals(listOf("gpt-6-astra"), codex(modelRowsOf(listOf(astra), null, overridden = false)))
    }

    @Test
    fun `a pick has no bubble on its way nor as the daemon's row, but one refused shows with its card`() {
        val pick = ClientEvent.Command("${MODEL_PICK_PREFIX}m7", PROFILE, "model", "codex/gpt-6-luna")
        val failure = RequestFailure("invalid_command", "no such model")
        val inputs = { outbox: List<OutboxItem> ->
            ChatInputs(
                rows = listOf(userRow(5, "/model codex/gpt-6-luna", clientMsgId = pick.clientMsgId), userRow(4, "hi")),
                outbox = outbox,
                bridged = emptyList(),
                live = ChatLive(),
                connected = true,
                unreadAt = null,
                requests = emptyMap(),
                profileId = PROFILE,
                nowWall = wallAt(10),
                zone = ZoneOffset.UTC,
            )
        }
        val texts = { items: List<ChatItem> -> items.filterIsInstance<ChatItem.Message>().map { it.message.text } }
        assertEquals(listOf("hi"), texts(chatItems(inputs(listOf(OutboxItem(pick))))))
        val refused = chatItems(inputs(listOf(OutboxItem(pick, failure, written = true))))
        assertEquals(listOf("/model codex/gpt-6-luna", "hi"), texts(refused), "newest first")
        assertEquals(1, refused.filterIsInstance<ChatItem.Error>().size)
    }

    @Test
    fun `a refused pick sent again is still a pick, and a reset from the card is one`() =
        runTest {
            val session = FakeChatSession(FakeChatStore())
            val ids = generateSequence(1) { it + 1 }.map { "m$it" }.iterator()
            val log = FakeLog().log
            val requests = ChatRequests(MutableStateFlow(session), backgroundScope, PROFILE, { ids.next() }, log)
            requests.resend(ClientEvent.Command("${MODEL_PICK_PREFIX}m0", PROFILE, "model", "codex/gpt-6-luna"))
            requests.resetModel()
            runCurrent()
            assertEquals(
                listOf("${MODEL_PICK_PREFIX}m1", "${MODEL_PICK_PREFIX}m2"),
                session.sent.value.map { OutboxItem(it).clientMsgId },
            )
        }

    @Test
    fun `the sheet's sentence sets the Fermix's name in italic, as the canon's i`() {
        val sentence = italicName("Applies to this chat on $NAME_MARK. Your config's default is unchanged.", "suj-mbp")
        assertEquals("Applies to this chat on suj-mbp. Your config's default is unchanged.", sentence.text)
        val italic = sentence.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("suj-mbp", sentence.text.substring(italic.start, italic.end))
    }

    @Test
    fun `a pull that came to nothing leaves the default and the one sentence`() {
        val rows = sheetRowsOf(ModelSheet.Unlisted, ModelState(ASTRA), overridden = false)
        assertEquals(listOf(ModelRow.Default("GPT-6 Astra", active = true), ModelRow.Unlisted), rows)
        assertEquals(emptyList<ModelRow>(), sheetRowsOf(ModelSheet.Loading, ModelState(ASTRA), overridden = false))
    }

    @Test
    fun `a pick is the model command, by provider and model, or reset for the default`() {
        val opus = ModelRow.Choice("anthropic", "claude-opus-5.5", "Claude Opus 5.5", null, false, false)
        assertEquals(
            ClientEvent.Command("m1", PROFILE, "model", "anthropic/claude-opus-5.5"),
            modelCommand(opus, "m1", PROFILE),
        )
        assertEquals(
            ClientEvent.Command("m2", PROFILE, "model", "reset"),
            modelCommand(ModelRow.Default("GPT-6 Astra", true), "m2", PROFILE),
        )
    }

    @Test
    fun `the sheet pulls as it opens, a pick sends and closes it, and a mid-turn pick waits for the turn's end`() =
        runTest {
            val store = FakeChatStore()
            val session = FakeChatSession(store).apply { models = OneShot.Answered(ENTRIES) }
            val chat = MutableStateFlow<ChatSession?>(session)
            val log = FakeLog()
            val requests = ChatRequests(chat, backgroundScope, PROFILE, { "m1" }, log.log)
            val models = ChatModels(requests, chat, backgroundScope, PROFILE, { "pick-1" }, log.log)
            models.open()
            runCurrent()
            assertEquals(ModelSheet.Listed(ENTRIES), models.sheet.value)
            assertEquals(1, session.pulls.value)
            val luna = ModelRow.Choice("codex", "gpt-6-luna", "GPT-6 Luna", "fast, cheaper", true, false)
            models.pick(luna, turnRuns = true)
            runCurrent()
            assertNull(models.sheet.value)
            assertEquals(
                listOf<ClientEvent>(
                    ClientEvent.Command("${MODEL_PICK_PREFIX}pick-1", PROFILE, "model", "codex/gpt-6-luna"),
                ),
                session.sent.value,
            )
            assertTrue(models.switchPending.value)
            models.turnsEnded()
            assertFalse(models.switchPending.value)
        }

    @Test
    fun `offline the pull is not had, and the sheet keeps the default alone`() =
        runTest {
            val session = FakeChatSession(FakeChatStore()).apply { connected.value = false }
            val chat = MutableStateFlow<ChatSession?>(session)
            val log = FakeLog()
            val models =
                ChatModels(
                    ChatRequests(chat, backgroundScope, PROFILE, {
                        "m1"
                    }, log.log),
                    chat,
                    backgroundScope,
                    PROFILE,
                    { "m2" },
                    log.log,
                )
            models.open()
            runCurrent()
            assertEquals(ModelSheet.Unlisted, models.sheet.value)
            assertEquals(listOf("The models were not listed: Offline"), log.lines.value)
        }

    @Test
    fun `model_changed says Switched to or Back to the default, with the daemon's note`() {
        val at = Moment(0L, wallAt(1), Candidate.Scope.LAN, 3uL)
        val switched =
            SessionEvent.ModelChanged(
                "anthropic",
                "claude-opus-5.5",
                "Claude Opus 5.5",
                ModelSource.OVERRIDE,
                "Computer History is off for this model",
            )
        val back = SessionEvent.ModelChanged("codex", "gpt-6-astra", "GPT-6 Astra", ModelSource.DEFAULT, null)
        val live = ChatLive().after(switched, at).after(back, at)
        assertEquals(
            listOf(
                LivePill.ModelChanged(
                    "Claude Opus 5.5",
                    false,
                    "Computer History is off for this model",
                    wallAt(1),
                    3uL,
                ),
                LivePill.ModelChanged("GPT-6 Astra", true, null, wallAt(1), 3uL),
            ),
            live.pills,
        )
        assertEquals(LiveModel("codex", "gpt-6-astra", "GPT-6 Astra", ModelSource.DEFAULT), live.model)
    }
}
