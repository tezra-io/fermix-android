package io.tezra.fermix

import io.tezra.fermix.data.MAIN_PROFILE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val CHAT = "a".repeat(64)

/**
 * The announcer's ON_SCREEN on a fake clock (design section 10; tla/specs/mobile_push, PUSH-2): a row is shown
 * only once the chat on screen lists it, so it is persisted, shown, then acked; never before, never for a chat
 * that is not on screen or leaves it, and not past the bounded wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnScreenChatsTest {
    @Test
    fun `a row is shown only once the chat on screen lists it, never before`() =
        runTest {
            val chats = OnScreenChats()
            val presence = chats.presence(CHAT, MAIN_PROFILE)
            presence.report(4uL)
            val shown = async { chats.shows(CHAT, MAIN_PROFILE, 5uL) }
            advanceTimeBy(LISTED_WAIT_MILLIS - 1)
            runCurrent()
            assertFalse(shown.isCompleted, "answered before the list held the row")
            presence.report(5uL)
            runCurrent()
            assertTrue(shown.await())
            assertEquals(LISTED_WAIT_MILLIS - 1, testScheduler.currentTime)
        }

    @Test
    fun `a chat not on screen, one that leaves it, and one that never lists the row show nothing`() =
        runTest {
            val chats = OnScreenChats()
            assertFalse(chats.shows(CHAT, MAIN_PROFILE, 5uL))
            val presence = chats.presence(CHAT, MAIN_PROFILE)
            presence.report(4uL)
            val leaving = async { chats.shows(CHAT, MAIN_PROFILE, 5uL) }
            runCurrent()
            presence.report(null)
            runCurrent()
            assertFalse(leaving.await())
            presence.report(4uL)
            val never = async { chats.shows(CHAT, MAIN_PROFILE, 5uL) }
            advanceTimeBy(LISTED_WAIT_MILLIS - 1)
            runCurrent()
            assertFalse(never.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(never.await())
            assertFalse(chats.shows(CHAT, "other", 1uL))
        }

    @Test
    fun `a row the list already holds is shown at once`() =
        runTest {
            val chats = OnScreenChats()
            chats.presence(CHAT, MAIN_PROFILE).report(9uL)
            assertTrue(chats.shows(CHAT, MAIN_PROFILE, 7uL))
            assertEquals(0L, testScheduler.currentTime)
        }
}
