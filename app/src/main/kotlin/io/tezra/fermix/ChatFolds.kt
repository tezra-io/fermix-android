package io.tezra.fermix

import io.tezra.fermix.chat.ChatClock
import io.tezra.fermix.chat.ChatLive
import io.tezra.fermix.chat.Moment
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Each instance's session events folded into what its chat shows besides its rows (feature-chat's ChatLive):
 * the card, the live bubbles, the endings, `accepted` and the centred lines, kept for the process's life, so a
 * chat opened mid-turn shows the turn as it stands. Each event is read at its [Moment] on [clock], over the
 * scope its session was connected over then; a line with no row of its own, a notice or a turn's ending, is
 * placed after the newest row the chat had kept, which [newestSeq] reads from the profile's database.
 */
class ChatFolds(
    private val clock: ChatClock,
    private val newestSeq: suspend (String) -> ULong,
) {
    private val folded = MutableStateFlow<Map<String, ChatLive>>(emptyMap())

    /** Each instance's fold, none for one whose session has said nothing in this process. */
    val chats: StateFlow<Map<String, ChatLive>> = folded.asStateFlow()

    /** [event] of [instanceId]'s [session], folded in. */
    suspend fun take(
        instanceId: String,
        session: Session,
        event: SessionEvent,
    ) {
        require(instanceId.isNotBlank()) { "an event names its instance" }
        val placed =
            event is SessionEvent.Server || (event is SessionEvent.Turn && event.effect is TurnEffect.TurnEnded)
        val newest = if (placed) newestSeq(instanceId) else 0uL
        val path = (session.state.value as? SessionState.Connected)?.scope
        val at = Moment(clock.monoMs(), clock.wallMs(), path, newest)
        folded.update { all -> all + (instanceId to (all[instanceId] ?: ChatLive()).after(event, at)) }
    }

    /**
     * [instanceId]'s session ended or was put away: each turn it showed is over, as far as the phone can see,
     * and its card goes; a reconnect's reconciliation shows the turns the daemon still runs again.
     */
    fun ended(instanceId: String) {
        val at = Moment(clock.monoMs(), clock.wallMs(), path = null, newestSeq = 0uL)
        folded.update { all -> all[instanceId]?.let { all + (instanceId to over(it, at)) } ?: all }
    }

    /** [instanceId] was removed: nothing of its chat is kept. */
    fun forget(instanceId: String) {
        folded.update { it - instanceId }
    }
}

/** [live] with each turn that shows ended, the phone's view of it over (TurnOutcome.Over, which leaves no line). */
internal fun over(
    live: ChatLive,
    at: Moment,
): ChatLive =
    live.turns
        .filter { it.live }
        .fold(live) { folded, turn ->
            val ended = TurnEffect.TurnEnded(turn.turnId, TurnOutcome.Over)
            folded.after(SessionEvent.Turn(ended, daemonSpeaking = false), at)
        }
