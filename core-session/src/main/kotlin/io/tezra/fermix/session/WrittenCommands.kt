package io.tezra.fermix.session

/** The commands a session remembers writing, the newest kept: as many as the turns a book shows. */
private const val MAX_COMMANDS = MAX_LIVE_TURNS

/**
 * The `command`s this session wrote to a socket, by client_msg_id: Stop, or one from the outbox. Their
 * `accepted` opens no card (Requests.accepted), and an answer the daemon writes inline ends its turn
 * (SessionCore.answersCommandInline).
 */
internal class WrittenCommands {
    private val ids = LinkedHashSet<String>()

    /** [clientMsgId] went to the socket; past the bound the oldest is forgotten. */
    fun written(clientMsgId: String) {
        require(clientMsgId.isNotEmpty()) { "a written command names itself" }
        ids.remove(clientMsgId)
        ids.add(clientMsgId)
        if (ids.size > MAX_COMMANDS) ids.remove(ids.first())
    }

    operator fun contains(clientMsgId: String): Boolean = clientMsgId in ids

    /** Whether [turnId] is the turn the daemon runs for one of these commands (turnIdOf). */
    fun ownsTurn(turnId: String): Boolean = ids.any { turnIdOf(it) == turnId }
}
