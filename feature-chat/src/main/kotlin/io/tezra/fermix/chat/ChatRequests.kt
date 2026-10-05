package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.CommandDescriptor
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.encodeClientEvent
import io.tezra.fermix.session.OutboxAttachment
import io.tezra.fermix.session.OutboxItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the chat asks of its session, each from the owner's hand and never on its own (design section 13.5:
 * no request is ever run again automatically): a message or a command, Stop, Edit and Remove of an item never
 * written, "Retry sending" and "Run again", "Reset to default". It remembers what it sent, at most
 * [MAX_REMEMBERED_REQUESTS], so "Run again" sends a request as it went, and what the owner took back
 * ([withdrawn]), which the outbox's bridge to the rows leaves out. What the owner asked and could not have is
 * told to [log].
 */
class ChatRequests(
    private val session: StateFlow<ChatSession?>,
    private val scope: CoroutineScope,
    private val profileId: String,
    private val newId: () -> String,
    private val log: (String, Throwable?) -> Unit,
) {
    private val sentRequests = MutableStateFlow<Map<String, ClientEvent>>(emptyMap())
    private val withdrawnIds = MutableStateFlow<Set<String>>(emptySet())

    val sent: StateFlow<Map<String, ClientEvent>> = sentRequests.asStateFlow()
    val withdrawn: StateFlow<Set<String>> = withdrawnIds.asStateFlow()

    /**
     * Sends [request], with the [attachments] its `msg` uploads first: whether the session took it, none taking it
     * while the chat has no session, nor one the session's codec would refuse ([carried]).
     */
    suspend fun send(
        request: ClientEvent,
        attachments: List<OutboxAttachment> = emptyList(),
    ): Boolean {
        val chat = session.value
        if (chat == null || !carried(request, attachments, log)) return false
        val taken = chat.send(request, attachments)
        if (taken) sentRequests.remember(request)
        return taken
    }

    /** Whether the chat has a session to take a request now; [send] refuses one while it has none. */
    fun hasSession(): Boolean = session.value != null

    /**
     * Whether [words], trimmed as a request takes them, fit one `msg` of the chat's profile (fitsOneMsg): the one bound
     * the owner's field is held to, a `command` by its words as typed, "/name arguments", whose header is smaller than
     * the one fitsOneMsg weighs, with ten attachments' ids and a `retry_of`. What goes is weighed again as the session
     * encodes it ([send], [retry]).
     */
    fun carries(words: String): Boolean = fitsOneMsg(words.trim(), profileId)

    /** The request the composer's [words] make, by the daemon's [commands] (requestOf); none for blank words. */
    fun of(
        words: String,
        commands: List<CommandDescriptor>,
    ): ClientEvent? = requestOf(words, commands, newId(), profileId)

    /** The composer's Stop, under a new id (stopAs). */
    fun stop() {
        scope.launch { stopAs(newId()) }
    }

    /**
     * Stop (design section 8.1, `command{name:"stop"}`) as [clientMsgId], from the composer's control or typed in
     * its field: sent at once over the connection that is up, never kept for a later one (Session.stop), where
     * it would stop whatever runs then. Whether it went; one that did not is told to [log].
     */
    suspend fun stopAs(clientMsgId: String): Boolean {
        require(clientMsgId.isNotEmpty()) { "a stop names itself" }
        val went = session.value?.stop(clientMsgId) == true
        if (!went) log("Stop came with no connection up; nothing was stopped", null)
        return went
    }

    /**
     * "Reset to default" on a `model_unavailable` card: `command{name:"model", args:"reset"}`, drawn as a pick is
     * ([MODEL_PICK_PREFIX]).
     */
    fun resetModel() {
        scope.launch { send(resetModel("$MODEL_PICK_PREFIX${newId()}", profileId)) }
    }

    /**
     * Takes [clientMsgId]'s request out of the outbox, while it was never written or the daemon refused it
     * (Session.remove): whether it left.
     */
    suspend fun withdraw(clientMsgId: String): Boolean {
        require(clientMsgId.isNotEmpty()) { "a request to withdraw names itself" }
        val removed = whileWithdrawn(clientMsgId) { session.value?.remove(clientMsgId) == true }
        return removed
    }

    /**
     * "Retry sending" a request the daemon refused before `accepted`, and the failed bubble's "Try again": the
     * same request under a new client_msg_id, its refused item out of the outbox. Design section 13.5 (review R4)
     * keeps the id, for the daemon to deduplicate; PROTOCOL.md ("Delivery and failure behavior") answers a resend
     * of a failed request as a duplicate that never runs, so the request goes under a new id, for the owner to
     * settle (README). Nothing ran, so it names nothing in `retry_of`, which says a request deliberately runs
     * again ("Run again", [retry]). A `msg` with [attachments] takes them with it, each under a new `attach_id`
     * from `attach_begin`, whose `present` skips the bytes the daemon still holds: an id it let go of, which
     * refused the item as `attachment_unavailable` (PROTOCOL.md "Attachments"), is never named again.
     */
    fun resend(
        request: ClientEvent,
        attachments: List<OutboxAttachment> = emptyList(),
    ) {
        val refused = OutboxItem(request).clientMsgId
        val fresh = attachments.map { it.copy(attachId = newId(), uploaded = false) }
        val again = renamed(request, idLike(refused, newId()), fresh)
        scope.launch {
            val taken = whileWithdrawn(refused) { send(again, fresh) }
            if (!taken) return@launch
            val removed = session.value?.remove(refused) == true
            if (!removed) {
                log(
                    "$refused was sent again as ${OutboxItem(again).clientMsgId} and stays in the outbox",
                    null,
                )
            }
        }
    }

    /**
     * "Run again" on a turn that ran and failed (design section 13.5; Session.retry): [request] again as a new
     * request, which names it in `retry_of`; never one the session's codec would refuse as Session.retry makes it, its
     * words whole and its `retry_of` the failed request's own id, a row's from the wire among them ([carried]).
     */
    fun retry(request: ClientEvent) {
        val failed = OutboxItem(request).clientMsgId
        val again = idLike(failed, newId())
        if (!carried(retried(request, again, failed), emptyList(), log)) return
        scope.launch {
            val taken = whileWithdrawn(failed) { session.value?.retry(request, again) == true }
            if (taken) sentRequests.remember(retried(request, again, failed))
        }
    }

    /**
     * [call] with [clientMsgId] counted as withdrawn, as it stays when [call] returns true: the outbox may let
     * the item go before the call returns, and it is not bridged to a row then.
     */
    private suspend fun whileWithdrawn(
        clientMsgId: String,
        call: suspend () -> Boolean,
    ): Boolean {
        withdrawnIds.update { (it + clientMsgId).toList().takeLast(MAX_REMEMBERED_REQUESTS).toSet() }
        val done = call()
        if (!done) withdrawnIds.update { it - clientMsgId }
        return done
    }
}

/** [request] among what was sent, by its id, the newest [MAX_REMEMBERED_REQUESTS] kept. */
private fun MutableStateFlow<Map<String, ClientEvent>>.remember(request: ClientEvent) {
    val id = OutboxItem(request).clientMsgId
    update { sent ->
        val kept = sent - id + (id to request)
        if (kept.size <= MAX_REMEMBERED_REQUESTS) kept else kept.entries.drop(1).associate { it.toPair() }
    }
}

/**
 * Whether [request], with each of [attachments]' `attach_begin`, is one the session's codec takes, weighed as it
 * encodes them, at the largest seq: its words whole, its ids as they are, a command's name by the wire's rule. The
 * codec throws on a header past 4,096 bytes (PROTOCOL.md; `event_part` is the daemon's alone) and on a field its
 * rules refuse, so a request it would refuse never reaches the session: the owner's field stays as it was
 * (ChatComposer.tooLong says why), and one the owner did not type, Run again's of a row's words or a model the daemon
 * named, is told to [log], by the refusal's class alone, as its words may hold the wire's.
 */
private fun carried(
    request: ClientEvent,
    attachments: List<OutboxAttachment>,
    log: (String, Throwable?) -> Unit,
): Boolean {
    require(request is ClientEvent.Msg || request is ClientEvent.Command) { "only a msg or a command is sent" }
    val refused = (listOf(request) + attachments.map(::attachBeginOf)).firstNotNullOfOrNull(::refusalOf)
    if (refused != null) {
        val id = OutboxItem(request).clientMsgId
        log("A request the session's codec refuses was not sent: $id (${refused.javaClass.simpleName})", null)
    }
    return refused == null
}

/** What the session's codec refuses [event] with, encoding it at the largest seq; none when it takes it. */
private fun refusalOf(event: ClientEvent): ProtocolException? =
    try {
        encodeClientEvent(MSG_PROTOCOL, ULong.MAX_VALUE, event)
        null
    } catch (refused: ProtocolException) {
        refused
    }

/** [fresh] as the id of a request sent again in place of [old]'s: a model pick's stays one, drawn as it was. */
private fun idLike(
    old: String,
    fresh: String,
): String = if (isModelPick(old)) "$MODEL_PICK_PREFIX$fresh" else fresh

/** [request] as it was, under [id], a `msg` naming [attachments] when it carries any. */
private fun renamed(
    request: ClientEvent,
    id: String,
    attachments: List<OutboxAttachment>,
): ClientEvent {
    val named = attachments.map { it.attachId }
    return when (request) {
        is ClientEvent.Msg -> request.copy(clientMsgId = id, attachIds = named.ifEmpty { request.attachIds })
        is ClientEvent.Command -> request.copy(clientMsgId = id)
        else -> error("only a msg or a command is sent again")
    }
}

/** [request] as Session.retry sends it again: under [id], a message naming [failed] in `retry_of`. */
private fun retried(
    request: ClientEvent,
    id: String,
    failed: String,
): ClientEvent =
    when (request) {
        is ClientEvent.Msg -> request.copy(clientMsgId = id, retryOf = failed)
        is ClientEvent.Command -> request.copy(clientMsgId = id)
        else -> error("only a msg or a command is sent again")
    }

/** [attachment]'s `attach_begin`, as the session sends it ahead of its bytes (Uploads). */
internal fun attachBeginOf(attachment: OutboxAttachment): ClientEvent.AttachBegin =
    ClientEvent.AttachBegin(
        attachment.attachId,
        attachment.kind,
        attachment.mime,
        attachment.sizeBytes,
        attachment.name,
        attachment.sha256,
    )
