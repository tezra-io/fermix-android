package io.tezra.fermix

import io.tezra.fermix.chat.ChatPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the announcer waits for the chat on screen to list a row it has just kept: the cache's flow, the
 * screen's state and a frame, with room to spare. Past it the row is not shown, and is announced as any other.
 */
const val LISTED_WAIT_MILLIS = 3_000L

/** A chat by its instance and profile. */
private data class ChatOf(
    val instanceId: String,
    val profileId: String,
)

/**
 * The chats on screen, as each Chat screen reports itself (ChatPresence: resumed with its window focused, the
 * newest row its list holds; none once it is not), and the announcer's answer from them (design section 10):
 * a row is ON_SCREEN only once the chat's list holds it, so it is persisted, shown, then acked (PUSH-2). A chat
 * on screen whose list has not taken the row yet is waited for, at most [wait] milliseconds; one that leaves
 * the screen meanwhile does not show it.
 */
class OnScreenChats(
    private val wait: Long = LISTED_WAIT_MILLIS,
) : ChatOnScreen,
    ChatShowing {
    private val listed = MutableStateFlow<Map<ChatOf, ULong>>(emptyMap())

    init {
        require(wait > 0) { "the wait for a row to be listed is bounded and positive" }
    }

    /** Where [instanceId]'s [profileId] chat reports the newest row its list holds while on screen. */
    fun presence(
        instanceId: String,
        profileId: String,
    ): ChatPresence {
        require(instanceId.isNotBlank() && profileId.isNotBlank()) { "a chat on screen names itself" }
        val chat = ChatOf(instanceId, profileId)
        return ChatPresence { upTo -> listed.update { if (upTo == null) it - chat else it + (chat to upTo) } }
    }

    override fun isOnScreen(
        instanceId: String,
        profileId: String,
    ): Boolean = ChatOf(instanceId, profileId) in listed.value

    override suspend fun shows(
        instanceId: String,
        profileId: String,
        serverSeq: ULong,
    ): Boolean {
        val chat = ChatOf(instanceId, profileId)
        if (chat !in listed.value) return false
        // The list takes the row, or the chat leaves the screen, whichever comes first, within the wait.
        val settled = withTimeoutOrNull(wait) { listed.first { all -> all[chat]?.let { it >= serverSeq } ?: true } }
        return settled?.get(chat)?.let { it >= serverSeq } == true
    }
}
