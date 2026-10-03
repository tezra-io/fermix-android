package io.tezra.fermix.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The diagnostics a session keeps, newest last; past this the oldest goes. */
internal const val MAX_DIAGNOSTICS = 200

enum class DiagnosticKind {
    /** A server `t` this app does not know: ignored, never a failure (design section 7). */
    UNKNOWN_EVENT,

    /** A frame or an event this side refused, and closed `1002` on; or the daemon's own `1002`. */
    PROTOCOL_ERROR,

    /** How a connection ended: its close code and who sent it, or how it failed. */
    CLOSED,

    /** A race in which every candidate failed. */
    RACE_FAILED,

    /** An `error` from the daemon. */
    REFUSED,

    /**
     * An `ack` sent: its row, and how long after the announcement or the read that made it due. Every one
     * is kept in Session.acks; one that waited 500 ms or more is noted in the diagnostics too.
     */
    ACK,

    /** A bound of the session's own was reached. */
    BOUND,

    /** An event for a profile this session does not serve: left for that profile's session. */
    OTHER_PROFILE,

    /** The session failed on a fault of the phone's own, such as its store. */
    FAILED,
}

/**
 * One notable thing, [atMs] after the session opened, for the Instance screen's Diagnostics. [detail]
 * never quotes a header, which may carry an approval's token: an event's name, a code and a reason.
 */
data class Diagnostic(
    val atMs: Long,
    val kind: DiagnosticKind,
    val detail: String,
)

/** The bounded log behind [Session.diagnostics] and [Session.acks]. */
internal class DiagnosticsLog {
    private val log = MutableStateFlow<List<Diagnostic>>(emptyList())
    val entries: StateFlow<List<Diagnostic>> = log.asStateFlow()

    fun add(entry: Diagnostic) {
        log.value = (log.value + entry).takeLast(MAX_DIAGNOSTICS)
    }
}
