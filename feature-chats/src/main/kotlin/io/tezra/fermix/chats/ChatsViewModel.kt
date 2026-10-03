package io.tezra.fermix.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.tezra.fermix.chat.ChatHeader
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.instance.Link
import io.tezra.fermix.instance.linkOf
import io.tezra.fermix.session.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The newest rows a Chats row reads: enough to find a message with a time behind a few sealed replies. */
private const val NEWEST_ROWS = 8

/**
 * What the Chats list runs on, all of it the app's: the records, the sessions it keeps by instance id and
 * the instances with a turn running, the profiles' databases, each row reading its instance's main profile
 * through [ProfileDatabases.observe], which ends the reading before a removal deletes the files, the removal
 * of an instance with `unpair` sent first ([unpair]) or not ([remove]), and the clock, zone and locale a
 * row's time is read in.
 */
data class ChatsParts(
    val instances: InstanceStore,
    val sessions: StateFlow<Map<String, Session>>,
    val thinking: StateFlow<Set<String>>,
    val profiles: ProfileDatabases,
    val unpair: suspend (String) -> Unit,
    val remove: suspend (String) -> Unit,
    val clock: () -> Instant = Instant::now,
    val zone: () -> ZoneId = ZoneId::systemDefault,
    val locale: () -> Locale = Locale::getDefault,
)

/**
 * The Chats list (design sections 9.4 and 13.4): [ui] is none until the records are read. A repair notice
 * goes when a pairing brings its own daemon back, which data's store does in the pairing's write, or when
 * the owner removes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModel(
    private val parts: ChatsParts,
) : ViewModel() {
    val ui: StateFlow<ChatsUi?> =
        combine(parts.instances.instances, parts.instances.repairNotices, ::Pair)
            .flatMapLatest { (records, repairs) -> rowsOf(records).map { rows -> ChatsUi(rows, repairs) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** [instanceId]'s chat bar, none once its record is gone. */
    fun header(instanceId: String): Flow<ChatHeader?> =
        combine(parts.instances.instances, linkFor(instanceId), thinkingOf(instanceId)) { records, link, thinking ->
            records.find { it.id == instanceId }?.let { ChatHeader(it, link, thinking) }
        }

    /** "Move to top": the row first, the rest in their order. */
    fun moveToTop(instanceId: String) {
        viewModelScope.launch { parts.instances.reorder(instanceId, 0) }
    }

    /** "Rename" with a nickname the dialog held to data's rule. */
    fun rename(
        instanceId: String,
        nickname: String,
    ) {
        viewModelScope.launch {
            val refusal = parts.instances.rename(instanceId, nickname)
            check(refusal == null) { "the rename dialog offered a nickname the rule refuses: $refusal" }
        }
    }

    /** "Unpair" in the dialog: `unpair` to the daemon if it is reachable, then the instance goes. */
    fun unpair(instanceId: String) {
        viewModelScope.launch { parts.unpair(instanceId) }
    }

    /** "Remove" on a trust state's screen: the instance goes from this phone, the daemon already forgot it. */
    fun remove(instanceId: String) {
        viewModelScope.launch { parts.remove(instanceId) }
    }

    /** "Remove" on the "Re-pair this Fermix" row of the dropped instance [instanceId]. */
    fun dismissRepair(instanceId: String) {
        viewModelScope.launch { parts.instances.dismissRepairNotice(instanceId) }
    }

    private fun rowsOf(records: List<Instance>): Flow<List<ChatRow>> {
        if (records.isEmpty()) return flowOf(emptyList())
        val rows =
            records.map { record ->
                factsOf(record.id).map { facts ->
                    rowOf(record, MAIN_PROFILE, facts, parts.clock().atZone(parts.zone()), parts.locale())
                }
            }
        return combine(rows) { it.toList() }
    }

    private fun factsOf(instanceId: String): Flow<RowFacts> {
        val stored =
            parts.profiles.observe(instanceId, MAIN_PROFILE) { database ->
                combine(
                    database.chat().state(),
                    database.timeline().newest(NEWEST_ROWS),
                    database.notified().serverSeqs(),
                ) { chat, newest, notified -> Triple(chat, newest, notified.size) }
            }
        return combine(linkFor(instanceId), thinkingOf(instanceId), stored) { link, thinking, (chat, newest, unread) ->
            RowFacts(link, thinking, chat, newest, unread)
        }
    }

    private fun linkFor(instanceId: String): Flow<Link> =
        parts.sessions
            .map { it[instanceId] }
            .distinctUntilChanged()
            .flatMapLatest { session ->
                if (session == null) flowOf(Link.NotOpen) else combine(session.state, session.diagnostics, ::linkOf)
            }

    private fun thinkingOf(instanceId: String): Flow<Boolean> =
        parts.thinking
            .map {
                instanceId in it
            }.distinctUntilChanged()
}
