package io.tezra.fermix.session

import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.ServerEvent

/** The cards one session remembers; the daemon holds at most 64 pending (design R21). */
private const val MAX_SHOWN_APPROVALS = 64

/** A command's name as the daemon's registry takes it (PROTOCOL.md, the `command` row). */
private val COMMAND_NAME = Regex("[a-z0-9_]+")

private const val SECOND_MS = 1_000L

/**
 * The words before the token of the routes the daemon sends, without their "/": PROTOCOL.md "Approvals" names
 * `/confirm` and `/soul apply`, and the engine's deny routes are `/deny` and `/soul deny`. A search hit of the
 * owner's that starts with one is an answer's row.
 */
private val DAEMON_ROUTES = listOf("confirm", "deny", "soul apply", "soul deny")

/** Routes a session learns from its cards at most, past the daemon's own; past it, none more is learned. */
private const val MAX_LEARNED_ROUTES = 16

/** An approval's token as the engine makes them: base64url (a sandbox card's) or base32 (a soul card's). */
private val TOKEN = Regex("[A-Za-z0-9_-]+")

/**
 * The client_msg_id of every approval's answer starts with this, then the card's id: the daemon writes the
 * answer as the owner's row, its words the route with the token in it, and sends that row under this id, by
 * which the session keeps it without its words (keptMessage) and the app never shows it (README, "core-session").
 */
const val APPROVAL_ANSWER_PREFIX = "approval-answer:"

/** Whether [clientMsgId] is an approval's answer (Session.answerApproval). */
fun isApprovalAnswer(clientMsgId: String): Boolean = clientMsgId.startsWith(APPROVAL_ANSWER_PREFIX)

/** Whether [row] is the owner's row the daemon wrote for an approval's answer, which the chat never shows. */
fun isAnswerRow(row: TimelineRow): Boolean =
    (row as? TimelineRow.Message)?.message?.clientMsgId?.let(::isApprovalAnswer) == true

/**
 * [message] as the phone keeps it: an approval's answer without its words, which are the route with the card's
 * token, submitted and never rendered (PROTOCOL.md "Approvals"). Every row the session takes, live, paged or
 * mutated, is kept so, and no store, index, announcer or screen ever holds the token; any other message is kept
 * as it came.
 */
fun keptMessage(message: HistoryMessage): HistoryMessage =
    if (message.clientMsgId?.let(::isApprovalAnswer) == true) message.copy(content = "") else message

/** What came of the owner's answer to a card (Session.answerApproval). */
sealed interface ApprovalAnswer {
    /** The card's route is in the outbox as [clientMsgId]: an outbox item, sent at least once. */
    data class Sent(
        val clientMsgId: String,
    ) : ApprovalAnswer

    /** The card is not one this session shows: never shown, resolved, or closed while the phone was away. */
    data object NotShown : ApprovalAnswer

    /** The card's `ttl_s` ran out on the session's clock: the daemon has expired it, or will (gotcha 18). */
    data object Expired : ApprovalAnswer

    /** The card was answered already, as [clientMsgId], and that answer has not been refused. */
    data class Answered(
        val clientMsgId: String,
    ) : ApprovalAnswer

    /**
     * The answer is past what one `command` carries (PROTOCOL.md: a header of at most 4,096 bytes; `event_part` is the
     * daemon's alone): a route the wire holds to 1,024 characters, not bytes, or an `approval_id` it holds to none.
     * Nothing went, and nothing was stored.
     */
    data object TooLong : ApprovalAnswer

    /** The outbox holds as many requests as it takes: nothing went, and nothing was stored. */
    data object OutboxFull : ApprovalAnswer
}

/** A route as the command the daemon expects (PROTOCOL.md "Approvals"): "/soul apply T" is `soul`, "apply T". */
internal class ApprovalRoute(
    val name: String,
    val args: String?,
)

/** One card: its routes, when it expires on the session's clock, and its answer while one is on its way. */
private class Shown(
    val approve: ApprovalRoute,
    val deny: ApprovalRoute,
    val expiresAtMs: Long,
    var answer: String? = null,
)

/**
 * The approval cards this session has handed the app and not seen resolved, by `approval_id`, with their
 * routes, so the session sends the owner's answer itself and the token never leaves it, and so a reconnect
 * can tell which of them the daemon no longer holds (design section 8.2, D23). It knows the routes' words
 * before their tokens, the daemon's and every card's since, by which a search hit on an answer's row is told.
 */
internal class ShownApprovals {
    private val cards = LinkedHashMap<String, Shown>()
    private val routes = LinkedHashSet(DAEMON_ROUTES)

    /**
     * A card was shown, or replayed in place with the seconds it has left, at [nowMs]; an answer on its way
     * stays with it. Past the bound the oldest is forgotten. A route that is not "/name args" is no answer
     * the phone can send, and is refused as a protocol error.
     */
    fun shown(
        event: ServerEvent.Approval,
        nowMs: Long,
    ) {
        val approve = routeOf(event.approveCommand)
        val deny = routeOf(event.denyCommand)
        val answer = cards.remove(event.approvalId)?.answer
        cards[event.approvalId] = Shown(approve, deny, nowMs + event.ttlS * SECOND_MS, answer)
        if (cards.size > MAX_SHOWN_APPROVALS) cards.remove(cards.keys.first())
        learn(event.approveCommand, event.token)
        learn(event.denyCommand, event.token)
    }

    /**
     * Whether [words], an excerpt of a row of the owner's, are an approval's answer: the whole excerpt a route
     * the daemon sends, then one token-shaped word, as the answer's row is no more than that. An excerpt may
     * lead or end with "…" and may leave out the route's "/". An owner's own words that go on past one word
     * ("confirm the dentist for Friday") are no answer; two words that look like one ("deny everything") are
     * taken for it.
     */
    fun answers(words: String): Boolean {
        val plain = words.trim { it == '…' || it.isWhitespace() }.removePrefix("/")
        return routes.any { route -> plain.startsWith("$route ") && TOKEN.matches(plain.substring(route.length + 1)) }
    }

    /** [route]'s words before [token], kept while fewer than the bound were learned. */
    private fun learn(
        route: String,
        token: String,
    ) {
        val stem =
            route
                .substringBefore(token)
                .trim()
                .removePrefix("/")
                .trim()
        val room = routes.size < DAEMON_ROUTES.size + MAX_LEARNED_ROUTES
        if (stem.isNotEmpty() && room) routes += stem
    }

    fun resolved(approvalId: String) {
        cards.remove(approvalId)
    }

    /** The cards [pending] does not list, which closed while this phone was away; they are forgotten. */
    fun closedExcept(pending: List<String>): List<String> {
        val closed = cards.keys.filter { it !in pending }
        closed.forEach(cards::remove)
        return closed
    }

    /** Why [approvalId] cannot be answered at [nowMs]: not shown, answered already, or expired; none when it can. */
    fun refusal(
        approvalId: String,
        nowMs: Long,
    ): ApprovalAnswer? {
        val card = cards[approvalId] ?: return ApprovalAnswer.NotShown
        val answer = card.answer
        return when {
            answer != null -> ApprovalAnswer.Answered(answer)
            nowMs >= card.expiresAtMs -> ApprovalAnswer.Expired
            else -> null
        }
    }

    /** The route that answers [approvalId], approving or denying. */
    fun route(
        approvalId: String,
        approve: Boolean,
    ): ApprovalRoute {
        val card = checkNotNull(cards[approvalId]) { "$approvalId is not shown" }
        return if (approve) card.approve else card.deny
    }

    /** [approvalId] was answered as [clientMsgId], which it waits on; a card gone meanwhile is no change. */
    fun answered(
        approvalId: String,
        clientMsgId: String,
    ) {
        cards[approvalId]?.answer = clientMsgId
    }

    /** The answer [clientMsgId] was refused: its card may be answered again. */
    fun answerRefused(clientMsgId: String) {
        cards.values.filter { it.answer == clientMsgId }.forEach { it.answer = null }
    }
}

/** [route] as PROTOCOL.md sends it: the leading "/" dropped, the first word the name, the rest the args. */
internal fun routeOf(route: String): ApprovalRoute {
    if (!route.startsWith("/")) throw SessionProtocolError("an approval route that is not a command")
    val body = route.drop(1)
    val end = body.indexOfFirst { it.isWhitespace() }
    val name = if (end < 0) body else body.substring(0, end)
    val args = if (end < 0) null else body.substring(end).trim().ifEmpty { null }
    if (!COMMAND_NAME.matches(name)) throw SessionProtocolError("an approval route whose name the daemon refuses")
    return ApprovalRoute(name, args)
}
