package io.tezra.fermix.chat

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.CommandDescriptor
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

    /** Sends [request]: whether the session took it, none taking it while the chat has no session. */
    suspend fun send(request: ClientEvent): Boolean {
        val chat = session.value ?: return false
        val taken = chat.send(request)
        if (taken) remember(request)
        return taken
    }

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

    /** "Reset to default" on a `model_unavailable` card: `command{name:"model", args:"reset"}`. */
    fun resetModel() {
        scope.launch { send(resetModel(newId(), profileId)) }
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
     * again ("Run again", [retry]).
     */
    fun resend(request: ClientEvent) {
        val refused = OutboxItem(request).clientMsgId
        val again = renamed(request, newId())
        scope.launch {
            val taken = whileWithdrawn(refused) { send(again) }
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
     * request, which names it in `retry_of`.
     */
    fun retry(request: ClientEvent) {
        val failed = OutboxItem(request).clientMsgId
        val again = newId()
        scope.launch {
            val taken = whileWithdrawn(failed) { session.value?.retry(request, again) == true }
            if (taken) remember(retried(request, again, failed))
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

    private fun remember(request: ClientEvent) {
        val id = OutboxItem(request).clientMsgId
        sentRequests.update { sent ->
            val kept = sent - id + (id to request)
            if (kept.size <= MAX_REMEMBERED_REQUESTS) kept else kept.entries.drop(1).associate { it.toPair() }
        }
    }
}

/** [request] as it was, under [id]. */
private fun renamed(
    request: ClientEvent,
    id: String,
): ClientEvent =
    when (request) {
        is ClientEvent.Msg -> request.copy(clientMsgId = id)
        is ClientEvent.Command -> request.copy(clientMsgId = id)
        else -> error("only a msg or a command is sent again")
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
