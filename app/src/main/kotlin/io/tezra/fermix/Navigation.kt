package io.tezra.fermix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import io.tezra.fermix.data.AppSettingsStore
import io.tezra.fermix.data.ChatRef
import io.tezra.fermix.data.Instance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Chats list (design section 13.4), the root once a Fermix is paired. */
data object ChatsKey : NavKey

/** One (instance, profile)'s chat (section 13.5). */
data class ChatKey(
    val instanceId: String,
    val profileId: String,
) : NavKey {
    init {
        require(instanceId.isNotBlank() && profileId.isNotBlank()) { "a chat names its instance and profile" }
    }
}

/** An instance's Instance screen (section 13.7). */
data class InstanceKey(
    val instanceId: String,
) : NavKey

/** "This phone was unpaired from {host}" (section 9.4), in place of the chat. */
data class RevokedKey(
    val instanceId: String,
) : NavKey

/** "{host}'s identity changed (reinstalled?)" (section 9.2), in place of the chat. */
data class IdentityChangedKey(
    val instanceId: String,
) : NavKey

/** The App lock setting, the Chats list's overflow's one entry (section 13.7). */
data object AppLockKey : NavKey

/** The static shortcut's action: "Add Fermix" (section 9.4). */
const val ACTION_ADD_FERMIX = "io.tezra.fermix.action.ADD_FERMIX"

/** An instance's id: `sha256(gateway_pk)` in lowercase hex (design section 9.1). */
internal val INSTANCE_ID = Regex("[0-9a-f]{64}")

/** A profile's id as a chat's link carries it. */
private val PROFILE_ID = Regex("[A-Za-z0-9_-]{1,64}")

/** A chat's deep link: an instance's id and a profile's id. */
private val CHAT_LINK = Regex("fermix://chat/(${INSTANCE_ID.pattern})/(${PROFILE_ID.pattern})")

/**
 * A chat's deep link, which a notification or a conversation shortcut opens: `fermix://chat/{id}/{profile}`;
 * an id or a profile that [chatOfLink] would refuse is refused here, so no link is made that the app ignores.
 */
fun chatLink(
    instanceId: String,
    profileId: String,
): String {
    require(INSTANCE_ID.matches(instanceId)) { "$instanceId is not an instance id" }
    require(PROFILE_ID.matches(profileId)) { "$profileId is not a profile id a link carries" }
    return "fermix://chat/$instanceId/$profileId"
}

/** The chat [link] names, or none for anything else, which the app ignores. */
fun chatOfLink(link: String?): ChatKey? {
    val match = link?.let(CHAT_LINK::matchEntire) ?: return null
    return ChatKey(match.groupValues[1], match.groupValues[2])
}

/** The instance a key of the app's is about, none for the Chats list and the App lock setting. */
private val NavKey.instanceId: String?
    get() =
        when (this) {
            is ChatKey -> instanceId
            is InstanceKey -> instanceId
            is RevokedKey -> instanceId
            is IdentityChangedKey -> instanceId
            else -> null
        }

/**
 * The app's screens above the Chats list (design section 13.2), for the activity's back stack: a screen
 * whose instance is gone leaves, and the chat on top is kept as the one to restore when the app comes back
 * (section 13.4). Onboarding's screens are onboarding's own.
 */
class AppNavigator(
    private val settings: AppSettingsStore,
    private val records: Flow<List<Instance>>,
) : ViewModel() {
    private val aboveState = MutableStateFlow<List<NavKey>>(emptyList())
    val above: StateFlow<List<NavKey>> = aboveState.asStateFlow()
    private var restored = false

    init {
        viewModelScope.launch {
            records.collect { list ->
                val ids = list.map { it.id }.toSet()
                aboveState.update { stack -> stack.filter { key -> key.instanceId?.let(ids::contains) ?: true } }
            }
        }
    }

    /**
     * Once per ViewModel: the chat [link] names, or else the chat open when the owner last left, if its
     * instance is still paired; then the chat on top is kept from here on.
     */
    suspend fun restore(link: ChatKey?) {
        if (restored) return
        restored = true
        val ids = records.first().map { it.id }.toSet()
        val last =
            settings.settings
                .first()
                .lastChat
                ?.let { ChatKey(it.instanceId, it.profileId) }
        val chat = (link ?: last)?.takeIf { it.instanceId in ids }
        if (chat != null) aboveState.value = listOf(chat)
        viewModelScope.launch {
            aboveState
                .map { stack -> stack.filterIsInstance<ChatKey>().lastOrNull() }
                .distinctUntilChanged()
                .collect { top -> settings.setLastChat(top?.let { ChatRef(it.instanceId, it.profileId) }) }
        }
    }

    fun open(key: NavKey) {
        require(key != ChatsKey) { "the Chats list is the root" }
        aboveState.update { it + key }
    }

    /** A chat from a link: on the Chats list, in place of whatever was above it; one no record has is ignored. */
    suspend fun showChat(chat: ChatKey) {
        if (records.first().any { it.id == chat.instanceId }) aboveState.value = listOf(chat)
    }

    /** [from], on top, gives way to [to]: a chat to its trust state's screen. */
    fun replace(
        from: NavKey,
        to: NavKey,
    ) {
        aboveState.update { stack -> if (stack.lastOrNull() == from) stack.dropLast(1) + to else stack }
    }

    /** Back from the top screen of the app's own; the Chats list's back is the system's. */
    fun back() {
        aboveState.update { it.dropLast(1) }
    }

    /** Back to the Chats list, as a pairing starts from it. */
    fun toRoot() {
        aboveState.value = emptyList()
    }
}
