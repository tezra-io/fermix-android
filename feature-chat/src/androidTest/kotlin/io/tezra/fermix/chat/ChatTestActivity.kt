package io.tezra.fermix.chat

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.session.IndicatorPools
import io.tezra.fermix.session.OneShot
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Rows in the host's chat: enough that the list scrolls on every window. */
internal const val THREAD_ROWS = 40

/** The row whose answer holds a fence, so its long-press menu offers "Copy code". */
internal const val FENCED_ROW = 38

/** The turn the host's thinking card belongs to, and when it started on the fake monotonic clock. */
private const val THINKING_TURN = "turn-m41"
internal const val CARD_STARTED_MS = 1_000L

/** The daemon's approval card the rig shows: a sandbox poll on a folder, with a minute to answer. */
internal val APPROVAL =
    SessionEvent.Approval("ap-1", "sandbox", "Allow reading ~/Documents?", "~/Documents/**", ttlS = 60)

/** Row [seq]'s words: the owner's on odd rows, the agent's on even ones, the fenced row's with its code. */
internal fun threadWords(seq: Int): String =
    if (seq == FENCED_ROW) "row $seq\n\n```kotlin\nprintln(\"row $seq\")\n```" else "row $seq"

/** The host's thread, a row a minute from [MORNING], newest last. */
internal val THREAD =
    (1..THREAD_ROWS).map { seq ->
        if (seq % 2 ==
            1
        ) {
            userRow(seq, threadWords(seq), seq.toLong())
        } else {
            agentRow(seq, threadWords(seq), seq.toLong())
        }
    }

/**
 * What the tests set and count, kept across the activity's recreation as the app's session and cache are: the
 * fake cache and session, the app's fold of the session's events, the monotonic clock, what the chat logged
 * ([log]), and how many times the activity was made. The record's daemon says its model, and lists [ENTRIES] when
 * asked. Beyond the screen, the system's pickers and prompts answer as [results] says, every activity the chat
 * starts is kept in [started], the app holds every permission, the microphone while [micAllowed], the camera is
 * [FakeCamera] and the attach sheet's grid the Photo Picker's tile.
 */
internal class ChatTestRig : ViewModel() {
    val creations = AtomicInteger()
    val results = PickerRegistry()
    val started: MutableList<Intent> = Collections.synchronizedList(mutableListOf())

    @Volatile var micAllowed = true

    val outside =
        ChatOutside(
            start = { _, intent -> started += intent },
            allowed = { _, permission -> permission != Manifest.permission.RECORD_AUDIO || micAllowed },
            camera = { taken, close -> FakeCamera(taken, close) },
            photos = { attach, actions, modifier -> PhotosTile(attach, actions, modifier) },
        )
    val recorder: FakeRecorder get() = parts.recorder as FakeRecorder
    val clip: FakeClip get() = parts.clip as FakeClip
    val store = FakeChatStore(rows = THREAD, frontier = THREAD_ROWS.toULong())
    val session = FakeChatSession(store).apply { models = OneShot.Answered(ENTRIES) }
    val live = MutableStateFlow(ChatLive())
    val clock = FakeChatClock(mono = CARD_STARTED_MS)
    val log = FakeLog()
    val parts = fakeParts(withModel(), session, store, viewModelScope).copy(live = live, clock = clock, log = log.log)

    /** [APPROVAL] shown now on the rig's clock with [ttlS] seconds left: again, it is replayed in place. */
    fun approval(ttlS: Int) {
        val moment = Moment(clock.mono, clock.wall, Candidate.Scope.TAILNET, THREAD_ROWS.toULong())
        live.value = live.value.after(APPROVAL.copy(ttlS = ttlS), moment)
    }

    /** A turn the daemon has said nothing of yet, its card on screen [elapsedMs] after it showed. */
    fun thinking(elapsedMs: Long) {
        val moment = Moment(CARD_STARTED_MS, wallAt(THREAD_ROWS + 1L), Candidate.Scope.TAILNET, THREAD_ROWS.toULong())
        clock.mono = CARD_STARTED_MS + elapsedMs
        live.value =
            ChatLive().after(SessionEvent.Turn(TurnEffect.CardShown(THINKING_TURN), daemonSpeaking = false), moment)
    }

    /** The thinking turn's answer, [markdown], streamed, sealed and completed: it arrives whole. */
    fun answered(markdown: String) {
        val moment = Moment(CARD_STARTED_MS, wallAt(THREAD_ROWS + 1L), Candidate.Scope.TAILNET, THREAD_ROWS.toULong())
        live.value =
            listOf(
                TurnEffect.BubbleOpened(THINKING_TURN, 1, fromCard = false),
                TurnEffect.BubbleText(THINKING_TURN, 1, markdown),
                TurnEffect.BubbleSealed(THINKING_TURN, 1, (THREAD_ROWS + 1).toULong()),
                TurnEffect.TurnEnded(THINKING_TURN, TurnOutcome.Completed),
            ).fold(
                live.value,
            ) { folded, effect -> folded.after(SessionEvent.Turn(effect, daemonSpeaking = false), moment) }
    }
}

/** The card's line [elapsedMs] after it showed, as cardLine words it from the module's pools; none without a card. */
internal fun ChatTestActivity.cardPhrase(elapsedMs: Long): String? {
    val card =
        model.state.value
            ?.items
            ?.filterIsInstance<ChatItem.Thinking>()
            ?.singleOrNull() ?: return null
    val pools =
        IndicatorPools(
            getString(R.string.chat_indicator_opening),
            resources.getStringArray(R.array.chat_indicator_first).toList(),
            resources.getStringArray(R.array.chat_indicator_second).toList(),
        )
    return cardLine(card.card, card.startedMono, card.seed, card.startedMono + elapsedMs, pools)
}

/** The Chat screen over the rig's fakes, in the design's theme, as the app shows it. */
open class ChatTestActivity : ComponentActivity() {
    internal val rig: ChatTestRig by viewModels()
    internal val model: ChatViewModel by viewModels {
        viewModelFactory { initializer { ChatViewModel(chatParts(), createSavedStateHandle()) } }
    }

    /** What the chat runs on: the rig's fakes. */
    internal open fun chatParts(): ChatParts = rig.parts

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        rig.creations.incrementAndGet()
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = rig.results
            }
        setContent {
            FermixTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    ChatRoute(
                        model,
                        ChatNavigation(onBack = {}, onInstance = {}, showing = { true }),
                        rig.outside,
                    )
                }
            }
        }
    }
}

/**
 * The Chat screen as [ChatTestActivity] shows it, but on the phone's own clipboard and media, PhoneClip and
 * PhoneMedia, as the app runs it: what the clipboard, another app or the camera hands the chat is weighed as it is
 * in the app, and what they refuse is told to the rig's log.
 */
class PhoneChatTestActivity : ChatTestActivity() {
    override fun chatParts(): ChatParts =
        rig.parts.copy(
            media = PhoneMedia(applicationContext, rig.log.log),
            clip = PhoneClip(applicationContext, rig.log.log),
        )
}
