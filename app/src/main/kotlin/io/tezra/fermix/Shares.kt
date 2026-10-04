package io.tezra.fermix

import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.tezra.fermix.chat.ShareInput
import io.tezra.fermix.chat.Shared
import io.tezra.fermix.chat.SharedUri
import io.tezra.fermix.chat.sharedOf
import io.tezra.fermix.chats.ChatRow
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.instance.needsTrust
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "FermixShare"

/** Why a share went nowhere once the app was open. */
private const val NO_TARGET = "no Fermix the phone still trusts is paired"

/** The actions of a share: one item or words, and several items. */
private val SHARE_ACTIONS = setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)

/**
 * The share entry (ShareTarget): the one exported way into the app that takes another app's share, and nothing but a
 * share; the conversation shortcuts name it as their share target.
 */
const val SHARE_ENTRY = "io.tezra.fermix.ShareTarget"

/** A share's line in the log: one lost, dropped or put down. */
internal fun logShare(line: String) {
    Log.w(TAG, line)
}

/** A share the entry took: what lands of it, and the conversation shortcut a Direct Share named, if any. */
data class Share(
    val shared: Shared,
    val shortcutId: String?,
)

/**
 * What lands of [intent], read as the share entry's (design section 13.6, "Share into Fermix"): its action, its type,
 * its `EXTRA_STREAM` (a URI for `ACTION_SEND`, a list of them for `ACTION_SEND_MULTIPLE`), its `EXTRA_TEXT` as plain
 * words and its `EXTRA_SHORTCUT_ID`, each read as the type it must be, and nothing else of it; weighed by sharedOf
 * with [own] naming the app's own providers, each refusal logged. None for no share, one whose extras do not
 * unparcel, one that carries nothing the app reads, one of which nothing lands, every item refused, or one the system
 * replays as the owner opens its task from Recents (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), which was taken once
 * already: each is logged, but an intent of another action, which is no share.
 */
internal fun shareOf(
    intent: Intent,
    own: (String) -> Boolean,
): Share? {
    val replayed = fromHistory(intent)
    if (replayed) Log.w(TAG, "a share replayed from Recents was not taken again")
    val input = if (replayed) null else shareInputOf(intent)
    val shared = input?.let { sharedOf(it, own) }
    val empty = shared == null && input?.action in SHARE_ACTIONS
    if (empty) Log.w(TAG, "a share carried nothing the app reads; it was dropped")
    shared ?: return null
    if (shared.refused.isNotEmpty()) Log.w(TAG, "a share's ${shared.refused.joinToString()} refused")
    if (shared.past > 0) Log.w(TAG, "${shared.past} shared items past the ten were left out")
    val lands = shared.uris.isNotEmpty() || shared.words != null
    if (!lands) Log.w(TAG, "a share of which nothing lands was dropped")
    return Share(shared, intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)).takeIf { lands }
}

/**
 * [intent]'s share as sharedOf reads it, each extra read as its type, none of another type; none when they do not
 * unparcel, which is logged.
 */
private fun shareInputOf(intent: Intent): ShareInput? =
    try {
        streamsAndWords(intent)
    } catch (fault: BadParcelableException) {
        Log.w(TAG, "a share's extras did not unparcel; it was dropped", fault)
        null
    }

/** [intent]'s action, type, streams and words, each extra read as its type and none of another type. */
private fun streamsAndWords(intent: Intent): ShareInput {
    val streams =
        when (intent.action) {
            Intent.ACTION_SEND -> {
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
            }

            else -> {
                emptyList()
            }
        }
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    return ShareInput(
        intent.action,
        intent.type,
        streams.map { SharedUri(it.toString(), it.scheme, it.authority) },
        text,
    )
}

/** Whether the system replays [intent] as the owner opens the task it started from Recents. */
internal fun fromHistory(intent: Intent): Boolean = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

/**
 * The Chats list's [rows] a share may go to (design sections 9.4 and 13.9), which "Send to which Fermix?" lists: each
 * in no trust state, as a Fermix that unpaired this phone or whose identity changed shows its trust screen in place of
 * its chat.
 */
fun shareRowsOf(rows: List<ChatRow>): List<ChatRow> = rows.filterNot { it.link.needsTrust }

/**
 * The paired Fermix a share may go to, those of [rows] it may go to (shareRowsOf), the rule the sheet's rows follow
 * too; none until the rows are read.
 */
fun shareTargetsOf(rows: List<ChatRow>?): List<Instance>? =
    rows
        ?.let(::shareRowsOf)
        ?.map { it.record }
        ?.distinctBy { it.id }

/** Where a share goes: into a paired chat, to "Send to which Fermix?", or nowhere with no Fermix paired. */
sealed interface ShareRoute {
    data class Into(
        val chat: ChatKey,
    ) : ShareRoute

    data object Ask : ShareRoute

    data object NonePaired : ShareRoute
}

/**
 * Where a share goes (design sections 13.6 and 13.9): a Direct Share's [shortcutId] is looked up among the paired
 * [records]' conversations, and goes into the chat it names; a shortcut that names none of them asks, and is never
 * made into a chat of its own. A generic share goes into the one paired Fermix's chat, or asks among several; with
 * none paired it goes nowhere.
 */
fun shareRouteOf(
    shortcutId: String?,
    records: List<Instance>,
): ShareRoute {
    if (records.isEmpty()) return ShareRoute.NonePaired
    val named = shortcutId?.let { id -> records.find { conversationId(it.id, MAIN_PROFILE) == id } }
    val into = named ?: records.singleOrNull()?.takeIf { shortcutId == null }
    return into?.let { ShareRoute.Into(ChatKey(it.id, MAIN_PROFILE)) } ?: ShareRoute.Ask
}

/** Where a share the app took stands. */
sealed interface ShareState {
    /** No share waits. */
    data object None : ShareState

    /**
     * [share] waits: behind the app lock ([behindLock]) until it is passed, or, [asked], on "Send to which Fermix?"
     * until a chat is picked.
     */
    data class Pending(
        val share: Share,
        val asked: Boolean,
        val behindLock: Boolean,
    ) : ShareState

    /** [shared] lands in [chat]'s tray and draft, and nothing is sent. */
    data class Landing(
        val chat: ChatKey,
        val shared: Shared,
    ) : ShareState
}

/**
 * A waiting share once the app is seen as [sight], with the paired [records], none until they are read (design
 * sections 13.6 and 13.7): the lock comes first, and the share waits behind it; the app leaving with the lock not
 * passed loses it; once the app is open, it goes where shareRouteOf says. A sheet already asking stays as it is
 * while the app is open or away unlocked. A share landing, or none, is left as it is.
 */
fun shareAfter(
    state: ShareState,
    sight: Sight,
    records: List<Instance>?,
): ShareState {
    if (state !is ShareState.Pending) return state
    return when (sight) {
        Sight.UNKNOWN -> state
        Sight.LOCKED -> state.copy(behindLock = true)
        Sight.AWAY -> if (state.behindLock) ShareState.None else state
        Sight.OPEN -> routed(state.copy(behindLock = false), records)
    }
}

/** [state] once the app is open: where its share goes, unless it asks already or the records are not read. */
private fun routed(
    state: ShareState.Pending,
    records: List<Instance>?,
): ShareState {
    if (state.asked || records == null) return state
    return when (val route = shareRouteOf(state.share.shortcutId, records)) {
        is ShareRoute.Into -> ShareState.Landing(route.chat, state.share.shared)
        ShareRoute.Ask -> state.copy(asked = true)
        ShareRoute.NonePaired -> ShareState.None
    }
}

/**
 * The share the activity took from the share entry's hand ([handed], taken once), for as long as the activity is kept,
 * through a rotation: it waits behind the app lock ([sight]) and on "Send to which Fermix?", then lands in the chat it
 * goes to among the paired [records] that take a share (shareTargetsOf). The share's URIs are read only as they land,
 * under the grant the entry's forward gave the activity, which holds it until it is destroyed. A share lost, dropped
 * or put down is logged ([log]); none is kept past the process.
 */
class ShareModel(
    private val sight: StateFlow<Sight>,
    records: Flow<List<Instance>?>,
    handed: MutableStateFlow<Share?>,
    private val log: (String) -> Unit,
) : ViewModel() {
    private val stateFlow = MutableStateFlow<ShareState>(ShareState.None)
    val state: StateFlow<ShareState> = stateFlow.asStateFlow()

    /** The paired records that take a share, none until they are read. */
    private var paired: List<Instance>? = null

    init {
        viewModelScope.launch { sight.collect { step() } }
        viewModelScope.launch {
            records.collect {
                paired = it
                step()
            }
        }
        viewModelScope.launch {
            handed.collect { share -> if (share != null && handed.compareAndSet(share, null)) take(share) }
        }
    }

    /** Another app's [share]: it takes the place of one still waiting, which is logged. */
    fun take(share: Share) {
        if (stateFlow.value !is ShareState.None) log("A share came while another waited; the newer one is kept")
        stateFlow.value = ShareState.Pending(share, asked = false, behindLock = false)
        step()
    }

    /** "Send to which Fermix?" picked [chat]: the share lands in it, when it is a paired Fermix's. */
    fun picked(chat: ChatKey) {
        val pending = stateFlow.value as? ShareState.Pending ?: return
        if (!pending.asked || paired?.any { it.id == chat.instanceId } != true) return
        stateFlow.value = ShareState.Landing(chat, pending.share.shared)
    }

    /** "Send to which Fermix?" was put down: the share is let go. */
    fun dismissed() {
        val pending = stateFlow.value as? ShareState.Pending ?: return
        if (!pending.asked) return
        stateFlow.value = ShareState.None
        log("A share was put down on Send to which Fermix?")
    }

    /** [landing] is taken to its chat, once: false when it was taken already, or is no longer the share. */
    fun landed(landing: ShareState.Landing): Boolean = stateFlow.compareAndSet(landing, ShareState.None)

    private fun step() {
        val before = stateFlow.value
        val seen = sight.value
        val after = shareAfter(before, seen, paired)
        stateFlow.value = after
        if (before !is ShareState.Pending || after != ShareState.None) return
        // Open, no record takes it: none is paired, or each is revoked or changed in identity (shareTargetsOf).
        val why = if (seen == Sight.AWAY) "the app left with its lock not passed" else NO_TARGET
        log("A share was dropped: $why")
    }
}
