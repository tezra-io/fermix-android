package io.tezra.fermix.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The push lines the app keeps, newest last; past this the oldest goes, as a session's diagnostics do. */
const val MAX_PUSH_LINES = 200

/** What the phone decided about a push or a notification (design section 10), as its diagnostics name it. */
enum class PushDecision(
    val text: String,
) {
    /** A message arrived at the messaging service. */
    RECEIVED("received"),

    /** Its envelope was outside the bounds, and no key was tried. */
    REFUSED("refused"),

    /** An instance's key opened it. */
    DECRYPTED("decrypted"),

    /** A notification was posted for it. */
    POSTED("posted"),

    /** Its id was in the notified set already: nothing posted, nobody alerted. */
    SUPPRESSED_SET("suppressed:set"),

    /** Its row was read already. */
    SUPPRESSED_READ("suppressed:read"),

    /** Its chat is on screen, which shows it. */
    SUPPRESSED_ON_SCREEN("suppressed:on_screen"),

    /** Its instance's notifications are off, or cannot show now. */
    SUPPRESSED_OFF("suppressed:off"),

    /** An approval that arrived at or after its expiry, shown as expired. */
    EXPIRED("expired"),

    /** No key opened it, or its plaintext was unreadable or of a kind this app does not know. */
    GENERIC("generic"),

    /** FCM dropped messages it held for the phone: each instance's next session pulls its history in full. */
    DELETED("deleted"),

    /** How long a push took from its arrival to its notification: the spike's budget (design section 15.1). */
    TIMED("timed"),
}

/**
 * One push line: when, in Unix milliseconds, what was decided, of which instance when one is known, and
 * [detail], which names a field, a reason or a duration and never a token, a key, a salt or a word of a
 * plaintext.
 */
data class PushLine(
    val atMs: Long,
    val decision: PushDecision,
    val instanceId: String? = null,
    val detail: String? = null,
) {
    /** The line as the diagnostics show it: `decrypted:{instance}`, `generic json`, `timed:{instance} 41 ms`. */
    override fun toString(): String =
        listOfNotNull(listOfNotNull(decision.text, instanceId).joinToString(":"), detail).joinToString(" ")
}

/**
 * The app's push diagnostics rings (design section 10): what each push came to, with no content. The lines of
 * a push no key opened, or one refused before any key was tried, go to [unopened], a ring of their own: anyone
 * who learns the token can send those, and a flood of them never pushes out [lines], what became of the pushes
 * a paired Fermix sent and of the app's own steps.
 */
class PushLog {
    private val log = MutableStateFlow<List<PushLine>>(emptyList())
    private val unopenedLog = MutableStateFlow<List<PushLine>>(emptyList())
    val lines: StateFlow<List<PushLine>> = log.asStateFlow()
    val unopened: StateFlow<List<PushLine>> = unopenedLog.asStateFlow()

    fun add(line: PushLine) {
        log.update { (it + line).takeLast(MAX_PUSH_LINES) }
    }

    /** One push's [pushLines], none of which names an instance, as no key opened it. */
    fun addUnopened(pushLines: List<PushLine>) {
        require(pushLines.all { it.instanceId == null }) { "an unopened push names no instance" }
        unopenedLog.update { (it + pushLines).takeLast(MAX_PUSH_LINES) }
    }
}
