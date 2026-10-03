package io.tezra.fermix

import io.tezra.fermix.chat.LivePill
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The app's fold of each session's events into what its chat shows besides its rows: a turn's card while it
 * runs and gone once the session ends, a notice placed after the newest row the chat kept, and nothing kept
 * of a removed Fermix.
 */
class ChatFoldsTest {
    private val id = record(1).id

    @Test
    fun `a turn shows until its session ends, and then leaves no line`() =
        runTest {
            val folds = ChatFolds(TestClock) { error("a card is placed after no row") }
            val session = idleSession(backgroundScope)
            folds.take(id, session, SessionEvent.Turn(TurnEffect.CardShown("turn-m1"), daemonSpeaking = false))
            val shown =
                folds.chats.value
                    .getValue(id)
                    .turns
                    .single()
            assertTrue(shown.live)
            folds.ended(id)
            val over =
                folds.chats.value
                    .getValue(id)
                    .turns
                    .single()
            assertFalse(over.live)
            assertNull(over.card)
            assertEquals(TurnOutcome.Over, over.ending?.outcome)
            session.close()
        }

    @Test
    fun `a notice is placed after the newest row the chat kept, and a removed Fermix keeps nothing`() =
        runTest {
            val folds = ChatFolds(TestClock) { 7uL }
            val session = idleSession(backgroundScope)
            folds.take(id, session, SessionEvent.Server(ServerEvent.Notice("info", "Compacted.")))
            val pill =
                folds.chats.value
                    .getValue(id)
                    .pills
                    .single() as LivePill.Notice
            assertEquals(7uL, pill.afterSeq)
            folds.forget(id)
            assertTrue(folds.chats.value.isEmpty())
            folds.ended(id)
            assertTrue(folds.chats.value.isEmpty())
            session.close()
        }
}
