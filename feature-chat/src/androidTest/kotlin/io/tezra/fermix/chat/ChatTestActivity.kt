package io.tezra.fermix.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
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
 * fake cache and session, the app's fold of the session's events, the monotonic clock, and how many times the
 * activity was made. The record's daemon says its model, and lists [ENTRIES] when asked.
 */
internal class ChatTestRig : ViewModel() {
    val creations = AtomicInteger()
    val store = FakeChatStore(rows = THREAD, frontier = THREAD_ROWS.toULong())
    val session = FakeChatSession(store).apply { models = OneShot.Answered(ENTRIES) }
    val live = MutableStateFlow(ChatLive())
    val clock = FakeChatClock(mono = CARD_STARTED_MS)
    val parts = fakeParts(withModel(), session, store, viewModelScope).copy(live = live, clock = clock)

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
class ChatTestActivity : ComponentActivity() {
    internal val rig: ChatTestRig by viewModels()
    internal val model: ChatViewModel by viewModels {
        viewModelFactory { initializer { ChatViewModel(rig.parts) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        rig.creations.incrementAndGet()
        setContent {
            FermixTheme {
                ChatRoute(
                    model,
                    ChatNavigation(onBack = {}, onInstance = {}, showing = { true }),
                )
            }
        }
    }
}
