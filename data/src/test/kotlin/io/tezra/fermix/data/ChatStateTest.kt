package io.tezra.fermix.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A chat's own state in its profile database (design sections 9.2, 9.4, 13.6 and 13.7): the draft the Chats
 * list shows, the host-owned agent's name, and whether notifications show previews, kept across a restart.
 */
class ChatStateTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `a new chat has no draft and no agent name, and shows previews`() =
        runTest {
            val chat = inMemoryDatabase().chat()
            assertEquals(ChatState(draft = null, agentName = null, previews = true), chat.state().first())
        }

    @Test
    fun `the draft, the agent name and previews are kept, and a blank draft clears it`() =
        runTest {
            val chat = inMemoryDatabase().chat()
            chat.setDraft("restart the ingest worker after the backup")
            chat.setAgentName("Jarvis")
            chat.setPreviews(false)
            assertEquals(ChatState("restart the ingest worker after the backup", "Jarvis", false), chat.state().first())
            chat.setDraft("   ")
            chat.setAgentName(null)
            assertEquals(ChatState(draft = null, agentName = null, previews = false), chat.state().first())
        }

    @Test
    fun `a restart finds the chat's state as it was`() =
        runTest {
            val id = idOf(key(1))
            val first = ProfileDatabases(TestContext, directory)
            first.open(id, MAIN_PROFILE).chat().setDraft("half a thought")
            first.delete(idOf(key(3)))
            val again = ProfileDatabases(TestContext, directory)
            assertEquals(
                "half a thought",
                again
                    .open(id, MAIN_PROFILE)
                    .chat()
                    .state()
                    .first()
                    .draft,
            )
        }

    @Test
    fun `a blank draft or agent name is no state`() {
        assertThrows<IllegalArgumentException> { ChatState(draft = " ", agentName = null, previews = true) }
        assertThrows<IllegalArgumentException> { ChatState(draft = null, agentName = "", previews = true) }
    }
}
