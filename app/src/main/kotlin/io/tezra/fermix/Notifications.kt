package io.tezra.fermix

import io.tezra.fermix.chat.rowWords
import io.tezra.fermix.chats.Conversation
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabase
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.Use
import io.tezra.fermix.data.removeExpired
import io.tezra.fermix.data.row
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

private const val MILLIS_PER_SECOND = 1_000L

/**
 * The one owner of the app's notifications (design section 10, "Lifecycle on the phone"), behind the seams a
 * session reaches it by, [RowNotifier] for a row and [ApprovalNotifier] for a live approval, and for a push
 * (PushInbox): it builds each notification, and [posted], which its callers reach too for a push's
 * notification or an approval's cancel, posts or cancels it; the notified set, which decides whether
 * anything is posted, is the callers'. A conversation's one notification is rebuilt from its notified
 * set, less the rows the read frontier covers, and the rows its cache holds, a row only a push brought showing
 * the preview that push carried while this process lives ([pushedWords]), and "New message" past that; while
 * the app lock is on ([locked], which a push's generic notification reads too) none is written with a word of
 * a message, and one showing as the lock comes on, or its chat's previews go off, is rebuilt ([restyled]). A
 * post that adds a row alerts; a rebuild after a read alerts nobody. The records are read from [records], the
 * words from [copy], the time from [now].
 */
class Notifications(
    val posted: PostedNotifications,
    private val records: StateFlow<List<Instance>>,
    private val databases: ProfileDatabases,
    val copy: NotificationCopy,
    val locked: suspend () -> Boolean,
    private val now: () -> Long,
) : RowNotifier,
    ApprovalNotifier {
    /** Each preview a push carried for a row the cache does not hold, by instance and row, until it is read. */
    private val pushedWords = ConcurrentHashMap<Pair<String, ULong>, String>()

    /** Whether [instanceId]'s notifications show: its switch on, and the phone letting its channel show. */
    override fun canNotify(
        instanceId: String,
        profileId: String,
    ): Boolean {
        val record = records.value.find { it.id == instanceId } ?: return false
        return canNotify(record, profileId)
    }

    override fun canNotify(instanceId: String): Boolean = canNotify(instanceId, MAIN_PROFILE)

    /** Whether [record]'s notifications show, read from the record in hand: a push's, whose key opened it. */
    fun canNotify(
        record: Instance,
        profileId: String,
    ): Boolean {
        require(profileId.isNotBlank()) { "a conversation names its profile" }
        return record.notificationsEnabled && posted.canShow(conversationId(record.id, profileId))
    }

    /**
     * A session's row, which the notified set has just taken: its conversation, rebuilt, alerting. An instance
     * removed meanwhile has no conversation left to post.
     */
    override suspend fun notify(
        instanceId: String,
        profileId: String,
        row: TimelineRow,
    ) {
        records.value.find { it.id == instanceId }?.let { postMessages(it, profileId) }
    }

    /** A session's live approval, which the notified set has just taken, timing out with its `ttl_s`. */
    override suspend fun notify(
        instanceId: String,
        approval: SessionEvent.Approval,
    ) {
        val record = records.value.find { it.id == instanceId } ?: return
        val nowMs = now()
        val expiresAtMs = nowMs + approval.ttlS * MILLIS_PER_SECOND
        posted.post(approvalNotification(facts(record), approval.approvalId, expiresAtMs, nowMs, copy))
    }

    /**
     * Posts [record]'s [profileId] conversation, rebuilt from its notified set: each row's words from the cache,
     * or the preview a push carried ([pushed], a row and its words), or "New message"; nothing, and the
     * notification cancelled, when the set holds no unread row. A post alerts, as it adds a row, unless it is
     * [quiet], a rebuild that adds none. False once the instance is removed, which keeps no set.
     */
    suspend fun postMessages(
        record: Instance,
        profileId: String,
        pushed: Pair<ULong, String>? = null,
        quiet: Boolean = false,
    ): Boolean {
        require(profileId.isNotBlank()) { "a conversation names its profile" }
        pushed?.let { (seq, words) -> pushedWords[record.id to seq] = words }
        val built = databases.withDatabase(record.id, profileId) { built(record, profileId, it, quiet) }
        if (built !is Use.Ran) return false
        val content = built.value
        val conversation = conversationId(record.id, profileId)
        if (content == null) posted.cancel(conversation, NotificationKey.Messages) else posted.post(content)
        return true
    }

    /**
     * The read frontier of [instanceId]'s [profileId] said [readUpToSeq], on every `hello_ack` and `read_state`
     * and on this phone's own read: the notified set loses the rows it covers, the conversation's notification
     * is cancelled once the set holds no row (design section 10), and one still showing is rebuilt with the rows
     * left, alerting nobody. False once the instance is removed.
     */
    suspend fun frontierMoved(
        instanceId: String,
        profileId: String,
        readUpToSeq: ULong,
    ): Boolean {
        require(instanceId.isNotBlank() && profileId.isNotBlank()) { "a frontier names its conversation" }
        val left =
            databases.withDatabase(instanceId, profileId) { profile ->
                profile.notified().removeReadUpTo(readUpToSeq)
                profile.notified().serverSeqs().first()
            }
        if (left !is Use.Ran) return false
        pushedWords.keys.removeIf { (instance, seq) -> instance == instanceId && seq !in left.value }
        val conversation = conversationId(instanceId, profileId)
        if (left.value.isEmpty()) posted.cancel(conversation, NotificationKey.Messages)
        val record = records.value.find { it.id == instanceId }
        val rebuild = left.value.isNotEmpty() && posted.isShowing(conversation, NotificationKey.Messages)
        return if (rebuild && record != null) postMessages(record, profileId, quiet = true) else true
    }

    /**
     * [instanceId]'s connection reconciled: the approvals and failed turns its notified set holds past their
     * retention go (NotifiedDao.removeExpired). False once the instance is removed.
     */
    suspend fun reconciled(instanceId: String): Boolean {
        require(instanceId.isNotBlank()) { "a connection names its instance" }
        return databases.withDatabase(instanceId, MAIN_PROFILE) { it.notified().removeExpired(now()) } is Use.Ran
    }

    /**
     * The app lock came on or went off, or a chat's previews switch moved: each conversation's notification still
     * showing is rebuilt with what they say now, alerting nobody, so none keeps a word they now keep out (design
     * section 10, "App lock"); one dismissed stays dismissed.
     */
    suspend fun restyled() {
        records.value
            .filter { posted.isShowing(conversationId(it.id, MAIN_PROFILE), NotificationKey.Messages) }
            .forEach { postMessages(it, MAIN_PROFILE, quiet = true) }
    }

    /** [record]'s conversation of [profileId], its sender the host-owned [agent], or "Fermix" when it has none. */
    fun facts(
        record: Instance,
        profileId: String = MAIN_PROFILE,
        agent: String? = null,
    ): ConversationFacts =
        ConversationFacts(
            record.id,
            profileId,
            conversationId(record.id, profileId),
            record.title,
            agent ?: copy.appName,
        )

    /**
     * The conversation's notification from [profile]'s set and cache, none when the set holds no row. The rows
     * at or below the stored read frontier leave the set first: this phone's own read is stored before the
     * session says it, so a row read here is never listed again while that word is on its way.
     */
    private suspend fun built(
        record: Instance,
        profileId: String,
        profile: ProfileDatabase,
        quiet: Boolean,
    ): NotificationContent? {
        profile.notified().removeReadUpTo(profile.readFrontier().first())
        val seqs = profile.notified().serverSeqs().first()
        if (seqs.isEmpty()) return null
        val chat = profile.chat().state().first()
        val nowMs = now()
        val lines =
            seqs.map { seq ->
                val words = profile.timeline().row(seq)?.let(::rowWords) ?: pushedWords[record.id to seq]
                NotifiedLine(seq, words ?: copy.newMessage, nowMs)
            }
        val facts = facts(record, profileId, chat.agentName)
        return messagesNotification(facts, lines, chat.previews, locked(), copy).copy(onlyAlertOnce = quiet)
    }
}

/** A conversation's channel and shortcut id, as ConversationSync publishes them. */
internal fun conversationId(
    instanceId: String,
    profileId: String,
): String = "$instanceId${Conversation.SEPARATOR}$profileId"
