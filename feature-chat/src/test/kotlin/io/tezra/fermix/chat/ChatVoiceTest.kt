package io.tezra.fermix.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException

/**
 * The voice note and its player over fakes (design sections 8.5, 13.5 and 13.6): hold records and samples the
 * level into bars; release sends one `msg` with its `audio` attachment; a lost audio focus, a call among them,
 * keeps the take as a draft and sends nothing; slide-left and the draft's trash let it go; the lock pauses and
 * resumes; the ten-minute cap stops into a draft; the one player plays a note, pauses, steps its speed and plays
 * another in its place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatVoiceTest {
    private val main = StandardTestDispatcher()

    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    private inner class Rig(
        scope: TestScope,
    ) {
        val store = FakeChatStore()
        val session = FakeChatSession(store)
        val recorder = FakeRecorder()
        val player = FakePlayer()
        val clock = FakeChatClock(mono = 1_000L)
        val files = FakeChatFiles(store)
        val log = FakeLog()
        val scratches = mutableListOf<File>()
        val sessions = MutableStateFlow<ChatSession?>(session)
        private val parts =
            fakeParts(sample(), session, store, scope.backgroundScope).copy(
                session = sessions,
                recorder = recorder,
                player = player,
                clock = clock,
                files = files,
                log = log.log,
                scratch = { File.createTempFile("scratch", null, dir).also { scratches += it } },
                io = main,
            )
        val viewModels = ViewModelStore()
        val model: ChatViewModel = modelIn(viewModels)
        val voice: VoiceUi get() = model.voice.ui.value

        /** The chat opened again over the same profile, its files included, as the next visit opens it. */
        fun reopened(): ChatViewModel = modelIn(ViewModelStore())

        private fun modelIn(store: ViewModelStore): ChatViewModel =
            ViewModelProvider
                .create(store, viewModelFactory { initializer { ChatViewModel(parts, SavedStateHandle()) } })
                .get(ChatViewModel::class)
    }

    /** Records for [ms] of the test's time, the clock moving with it. */
    private fun TestScope.record(
        rig: Rig,
        ms: Long,
    ) {
        repeat((ms / SAMPLE_MS).toInt()) {
            rig.clock.mono += SAMPLE_MS
            advanceTimeBy(SAMPLE_MS)
            runCurrent()
        }
    }

    @Test
    fun `hold records and samples bars, and release sends one msg with its audio note`() =
        runTest(main) {
            val rig = Rig(this)
            rig.recorder.amplitudes = ArrayDeque(listOf(MAX_AMPLITUDE, MAX_AMPLITUDE / 4, 0))
            runCurrent()
            rig.model.voice.start()
            record(rig, 1_200L)
            val recording = rig.voice as VoiceUi.Recording
            assertEquals(1_200L, recording.elapsedMs)
            assertEquals(WAVE_BARS, recording.bars.size)
            val quarter = levelOf(MAX_AMPLITUDE / 4)
            assertEquals(listOf(1f, quarter, QUIET_LEVEL), recording.bars.subList(WAVE_BARS - 12, WAVE_BARS - 9))
            rig.model.voice.send {}
            runCurrent()
            assertEquals(VoiceUi.Idle, rig.voice)
            val msg =
                rig.session.sent.value
                    .single() as ClientEvent.Msg
            assertEquals("", msg.text)
            val note =
                rig.store.outbox.value
                    .single()
                    .attachments
                    .single()
            assertEquals(AttachKind.AUDIO, note.kind)
            assertEquals(VOICE_MIME, note.mime)
            assertEquals(VOICE_NAME, note.name)
            assertEquals(listOf(note.attachId), msg.attachIds)
            val sampled = listOf(1f, quarter) + List(10) { QUIET_LEVEL }
            assertEquals(barsOf(sampled), rig.model.voice.bars.value[note.sha256])
        }

    @Test
    fun `a sent note keeps its length by its digest, and a note's length is read once from its file`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 1_200L)
            rig.model.notes.send {}
            runCurrent()
            val note =
                rig.store.outbox.value
                    .single()
                    .attachments
                    .single()
            assertEquals(mapOf(note.sha256 to 1_200L), rig.model.voice.lengths.value)
            val playback = rig.model.playback
            assertEquals(11_000L, playback.length("n1") { it.writeText("one").let { true } })
            assertEquals(11_000L, playback.length("n1") { error("read twice") })
            assertEquals(null, playback.length("gone") { false })
            assertEquals(mapOf("n1" to 11_000L), playback.lengths.value)
            assertEquals(mapOf("n1" to barsOf(rig.player.levels)), playback.bars.value)
            assertTrue(rig.scratches.none(File::exists), "a length's copy is deleted: ${rig.scratches}")
        }

    @Test
    fun `a note recorded elsewhere draws the bars read from its file, and one recorded here its own`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            assertEquals(11_000L, rig.model.playback.length("n1") { it.writeText("one").let { true } })
            rig.model.voice.start()
            record(rig, 300L)
            rig.model.voice.send {}
            runCurrent()
            val note =
                rig.store.outbox.value
                    .single()
                    .attachments
                    .single()
            rig.model.playback.length(note.sha256) { it.writeText("two").let { true } }
            runCurrent()
            val bars = rig.model.media.value.bars
            assertEquals(barsOf(rig.player.levels), bars["n1"])
            assertEquals(rig.model.voice.bars.value[note.sha256], bars[note.sha256])
        }

    @Test
    fun `a note whose file cannot be read or copied has no length, is logged, and leaves no copy`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.player.unreadable = true
            assertEquals(null, rig.model.playback.length("n1") { it.writeText("one").let { true } })
            rig.player.unreadable = false
            assertEquals(null, rig.model.playback.length("n2") { throw FileNotFoundException("released") })
            rig.model.playback.toggle("n3") { throw FileNotFoundException("released") }
            runCurrent()
            assertEquals(null, rig.model.playback.playing.value)
            val said =
                listOf(
                    "Voice note n1 could not be read",
                    "Voice note n2 could not be copied",
                    "Voice note n3 could not be copied",
                    "Voice note n3 could not be had to play",
                )
            assertEquals(
                said,
                rig.log.lines.value
                    .filter { it.startsWith("Voice note") },
            )
            assertTrue(rig.scratches.none(File::exists), "a copy was left: ${rig.scratches}")
        }

    @Test
    fun `a note the player cannot open is logged and never shows as playing`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.player.unplayable = true
            rig.model.playback.toggle("n1") { it.writeText("one").let { true } }
            runCurrent()
            assertEquals(Playing("n1", 0L, 11_000L, 1f, running = false), rig.model.playback.playing.value)
            assertEquals(
                "Voice note n1 could not be played",
                rig.log.lines.value
                    .last(),
            )
        }

    @Test
    fun `sending or discarding a draft that plays lets the player go first`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 1_000L)
            rig.model.voice.stop()
            rig.model.notes.playDraft()
            runCurrent()
            assertEquals(
                DRAFT_KEY,
                rig.model.playback.playing.value
                    ?.key,
            )
            rig.model.notes.discard()
            assertEquals(null, rig.model.playback.playing.value)
            assertEquals(VoiceUi.Idle, rig.voice)
            rig.model.voice.start()
            record(rig, 1_000L)
            rig.model.voice.stop()
            rig.model.notes.playDraft()
            runCurrent()
            rig.model.notes.send {}
            runCurrent()
            assertEquals(null, rig.model.playback.playing.value)
            assertEquals(1, rig.session.sent.value.size)
        }

    @Test
    fun `a lost audio focus keeps the take as a draft and sends nothing until the owner taps send`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 2_000L)
            rig.recorder.loseFocus()
            runCurrent()
            val draft = assertInstanceOf(VoiceUi.Draft::class.java, rig.voice, "a lost audio focus keeps a draft")
            assertEquals(2_000L, draft.durationMs)
            record(rig, 1_000L)
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
                "a recording was sent on a lost audio focus",
            )
            assertTrue(checkNotNull(rig.model.voice.draftFile).exists())
            rig.model.voice.send {}
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
            assertFalse(rig.files.draft.exists(), "the draft outlived its send")
        }

    @Test
    fun `a draft outlives the chat's close and comes back as the chat opens again`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 2_000L)
            rig.recorder.loseFocus()
            runCurrent()
            rig.viewModels.clear()
            runCurrent()
            assertTrue(rig.files.draft.isFile, "closing the chat deleted the draft")
            val again = rig.reopened()
            runCurrent()
            assertEquals(VoiceUi.Draft(rig.player.lengthMs, barsOf(rig.player.levels)), again.voice.ui.value)
            again.voice.send {}
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
            assertEquals(
                barsOf(rig.player.levels),
                again.voice.bars.value.values
                    .single(),
            )
        }

    @Test
    fun `a recording the chat closes on is kept as its draft, never sent`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 1_000L)
            rig.viewModels.clear()
            runCurrent()
            assertEquals(1, rig.recorder.stops.value)
            assertTrue(rig.files.draft.isFile)
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
            val again = rig.reopened()
            runCurrent()
            assertInstanceOf(VoiceUi.Draft::class.java, again.voice.ui.value)
        }

    @Test
    fun `a kept draft that cannot be read is let go as the chat opens, and the log says so`() =
        runTest(main) {
            val rig = Rig(this)
            rig.files.draft.writeBytes(byteArrayOf(1))
            rig.player.unreadable = true
            runCurrent()
            assertEquals(VoiceUi.Idle, rig.voice)
            assertFalse(rig.files.draft.exists())
            assertTrue("The kept voice draft could not be read; it was let go" in rig.log.lines.value)
        }

    @Test
    fun `a voice note its session does not take comes back as the draft, and its staged copy goes`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 1_500L)
            rig.model.voice.stop()
            val draft = rig.voice
            rig.session.takes = false
            rig.model.voice.send { error("nothing was taken") }
            runCurrent()
            assertEquals(draft, rig.voice)
            assertTrue(rig.files.draft.isFile, "the draft was lost")
            assertEquals(1, rig.files.staged.value.size)
            assertEquals(rig.files.staged.value, rig.files.released.value)
            assertTrue(
                rig.files.staged.value
                    .none { File(it).exists() },
                "a staged copy was left",
            )
            rig.session.takes = true
            rig.model.voice.send {}
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
        }

    @Test
    fun `a release sends the unlocked recording the hold made, and says so once the session took it`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            var taken = 0
            rig.model.voice.start()
            record(rig, 1_000L)
            rig.model.notes.release { taken++ }
            runCurrent()
            assertEquals(1, rig.session.sent.value.size)
            assertEquals(1, taken)
            rig.model.voice.start()
            record(rig, 1_000L)
            rig.model.voice.lock()
            rig.model.notes.release { taken++ }
            runCurrent()
            assertTrue((rig.voice as VoiceUi.Recording).locked, "a release while locked sends nothing")
            assertEquals(1, rig.session.sent.value.size)
        }

    @Test
    fun `a release after the phone stopped the take keeps the draft and sends nothing`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 2_000L)
            rig.recorder.loseFocus()
            rig.model.notes.release { error("nothing was taken") }
            runCurrent()
            assertInstanceOf(VoiceUi.Draft::class.java, rig.voice, "the release sent the interrupted take")
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
                "a recording was sent on a lost audio focus",
            )
        }

    @Test
    fun `with no session to take it, a send keeps the recording as a draft`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 1_000L)
            rig.sessions.value = null
            runCurrent()
            rig.model.notes.release { error("nothing was taken") }
            runCurrent()
            assertInstanceOf(VoiceUi.Draft::class.java, rig.voice)
            assertTrue(
                rig.files.staged.value
                    .isEmpty(),
                "nothing was staged",
            )
        }

    @Test
    fun `stop keeps the take as a draft and sends nothing`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 500L)
            rig.model.voice.stop()
            runCurrent()
            assertTrue(rig.voice is VoiceUi.Draft)
            assertEquals(1, rig.recorder.stops.value)
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
        }

    @Test
    fun `slide to cancel and the draft's trash let the take go with its file`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 500L)
            rig.model.voice.discard()
            runCurrent()
            assertEquals(VoiceUi.Idle, rig.voice)
            rig.model.voice.start()
            record(rig, 500L)
            rig.model.voice.stop()
            rig.model.voice.discard()
            runCurrent()
            assertEquals(VoiceUi.Idle, rig.voice)
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
            assertFalse(rig.files.draft.exists(), "a take's file was left")
        }

    @Test
    fun `locked, pause holds the timer and the bars, and resume goes on`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, 1_000L)
            rig.model.voice.pause()
            assertFalse((rig.voice as VoiceUi.Recording).paused, "pause came before the lock")
            rig.model.voice.lock()
            rig.model.voice.pause()
            record(rig, 3_000L)
            val paused = rig.voice as VoiceUi.Recording
            assertTrue(paused.locked && paused.paused)
            assertEquals(1_000L, paused.elapsedMs)
            rig.model.voice.resume()
            record(rig, 500L)
            assertEquals(1_500L, (rig.voice as VoiceUi.Recording).elapsedMs)
            assertEquals(1, rig.recorder.pauses.value)
            assertEquals(1, rig.recorder.resumes.value)
        }

    @Test
    fun `ten minutes stops the take into a draft`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.model.voice.start()
            record(rig, MAX_VOICE_MS)
            assertTrue(rig.voice is VoiceUi.Draft)
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
        }

    @Test
    fun `a busy microphone or a take that kept nothing leaves the row idle, and the log says so`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            rig.recorder.busy = true
            rig.model.voice.start()
            assertEquals(VoiceUi.Idle, rig.voice)
            rig.recorder.busy = false
            rig.recorder.keeps = false
            rig.model.voice.start()
            record(rig, 100L)
            rig.model.voice.send {}
            runCurrent()
            assertEquals(VoiceUi.Idle, rig.voice)
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
            )
            assertEquals(
                "The microphone could not record",
                rig.log.lines.value
                    .first(),
            )
        }

    @Test
    fun `the player plays a note, pauses, steps its speed, plays to its end and gives way to another`() =
        runTest(main) {
            val rig = Rig(this)
            runCurrent()
            val playback = rig.model.playback
            playback.toggle("n1") { it.writeText("one").let { true } }
            runCurrent()
            assertEquals(Playing("n1", 0L, 11_000L, 1f, running = true), playback.playing.value)
            rig.player.position = 4_000L
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(4_000L, playback.playing.value?.positionMs)
            playback.toggle("n1") { error("copied twice") }
            assertFalse(checkNotNull(playback.playing.value).running)
            playback.faster()
            playback.toggle("n1") { error("copied twice") }
            assertEquals(
                Triple("one", 4_000L, 1.5f),
                rig.player.plays.value
                    .last(),
            )
            rig.player.end()
            assertEquals(Playing("n1", 0L, 11_000L, 1.5f, running = false), playback.playing.value)
            playback.toggle("n2") { it.writeText("two").let { true } }
            runCurrent()
            assertEquals("n2", playback.playing.value?.key)
            assertEquals(
                "two",
                rig.player.plays.value
                    .last()
                    .first,
            )
            assertEquals(1, rig.player.stopped.value)
            playback.toggle("gone") { false }
            runCurrent()
            assertEquals(null, playback.playing.value)
            assertEquals(
                "Voice note gone could not be had to play",
                rig.log.lines.value
                    .last(),
            )
        }
}
