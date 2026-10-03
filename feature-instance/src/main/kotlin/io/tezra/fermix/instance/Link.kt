package io.tezra.fermix.instance

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.DiagnosticKind
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.transport.Candidate

/**
 * How a Fermix's link reads on its Chats row, its chat's app bar and its Instance screen (design sections
 * 9.4, 13.5 and 13.7): the session's state, and a protocol error (`1002`), which the session backs off from
 * like any failed connection and never ends on, read from its diagnostics.
 */
sealed interface Link {
    /** Connected over [scope], [latencyMs] the last round trip; until [caughtUp], "Updating…". */
    data class Up(
        val scope: Candidate.Scope,
        val latencyMs: Long,
        val caughtUp: Boolean,
    ) : Link

    /** No session: the app is out of sight, or the instance's key could not be read. */
    data object NotOpen : Link

    data object Connecting : Link

    data object WaitingForNetwork : Link

    data object CannotReach : Link

    /** The last connection ended on `1002`, and none has completed since; shown as such, never as revoked. */
    data object ProtocolError : Link

    data object Revoked : Link

    data object IdentityChanged : Link

    data object Replaced : Link

    data object OlderDaemon : Link

    data object NewerDaemon : Link

    data object Closed : Link

    data object Failed : Link
}

/** The connection dot's colour (the visual canon's `.dot`): the tertiary ink, ok, warn or err. */
enum class Dot { OFF, OK, WARN, ERR }

/** The diagnostics that say how a connection ended, the newest of which tells a protocol error. */
private val ENDINGS = setOf(DiagnosticKind.PROTOCOL_ERROR, DiagnosticKind.CLOSED, DiagnosticKind.RACE_FAILED)

/**
 * [state]'s link, none when the instance has no session. A session not connected whose newest ending in
 * [diagnostics] is a protocol error reads as [Link.ProtocolError], whatever it is doing about it.
 */
fun linkOf(
    state: SessionState?,
    diagnostics: List<Diagnostic>,
): Link =
    when (state) {
        null -> Link.NotOpen
        is SessionState.Connected -> Link.Up(state.scope, state.latencyMs, state.caughtUp)
        is SessionState.Ended -> endedLink(state)
        else -> if (endedOnProtocolError(diagnostics)) Link.ProtocolError else trying(state, diagnostics)
    }

private fun endedOnProtocolError(diagnostics: List<Diagnostic>): Boolean =
    diagnostics.lastOrNull { it.kind in ENDINGS }?.kind == DiagnosticKind.PROTOCOL_ERROR

/**
 * A session trying to connect. A suspended one was put aside by the app, which resumes it as the app comes
 * back into sight, so it is connecting; or it ran out of races in one run (SessionState), which the bound
 * logged last, and that is "Can't reach" until the app's resume races again (onboarding gotcha 20: a day of
 * failed races). A bound a live connection logged, then put aside, reads so too until the resume publishes.
 */
private fun trying(
    state: SessionState,
    diagnostics: List<Diagnostic>,
): Link =
    when (state) {
        SessionState.WaitingForNetwork -> Link.WaitingForNetwork
        SessionState.CannotReach -> Link.CannotReach
        SessionState.Suspended -> if (ranOutOfRaces(diagnostics)) Link.CannotReach else Link.Connecting
        else -> Link.Connecting
    }

private fun ranOutOfRaces(diagnostics: List<Diagnostic>): Boolean =
    diagnostics.lastOrNull()?.kind == DiagnosticKind.BOUND

private fun endedLink(state: SessionState.Ended): Link =
    when (state) {
        SessionState.Revoked -> Link.Revoked
        SessionState.IdentityChanged -> Link.IdentityChanged
        SessionState.Replaced -> Link.Replaced
        SessionState.OlderDaemon -> Link.OlderDaemon
        SessionState.NewerDaemon -> Link.NewerDaemon
        SessionState.Closed -> Link.Closed
        is SessionState.Failed -> Link.Failed
    }

/**
 * The dot, never optimistic (design section 13.5): ok only when a handshake completed, warn while
 * connecting and for a changed identity (the canon's trust-state row), err for a revoked phone.
 */
val Link.dot: Dot
    get() =
        when (this) {
            is Link.Up -> Dot.OK
            Link.Connecting, Link.IdentityChanged -> Dot.WARN
            Link.Revoked -> Dot.ERR
            else -> Dot.OFF
        }

/**
 * Whether the Chats row says [this] in place of its last message (the canon's trust-state rows): a link
 * that ended or needs the owner. A row offline or connecting keeps its last message (section 9.4).
 */
val Link.speaksOnRow: Boolean
    get() =
        when (this) {
            is Link.Up, Link.NotOpen, Link.Connecting, Link.WaitingForNetwork, Link.CannotReach, Link.Closed -> false
            else -> true
        }

/** Whether [this] is one of the two trust states whose full screen replaces the chat (design section 9.4). */
val Link.needsTrust: Boolean get() = this == Link.Revoked || this == Link.IdentityChanged

/**
 * [link]'s words with the computer [host] where the copy names it (design section 13.9), none for a link
 * that has nothing to say: a closed session, or none.
 */
@Composable
fun linkWords(
    link: Link,
    host: String,
): String? =
    when (link) {
        is Link.Up -> {
            if (link.caughtUp) {
                pathWords(
                    link.scope,
                    link.latencyMs,
                )
            } else {
                stringResource(R.string.instance_link_updating)
            }
        }

        Link.NotOpen, Link.Closed -> {
            null
        }

        Link.Connecting -> {
            stringResource(R.string.instance_link_connecting)
        }

        Link.WaitingForNetwork -> {
            stringResource(R.string.instance_link_waiting)
        }

        Link.CannotReach -> {
            stringResource(R.string.instance_link_cannot_reach, host)
        }

        Link.ProtocolError -> {
            stringResource(R.string.instance_link_protocol_error)
        }

        Link.Revoked -> {
            stringResource(R.string.instance_link_revoked, host)
        }

        Link.IdentityChanged -> {
            stringResource(R.string.instance_link_identity_changed, host)
        }

        Link.Replaced -> {
            stringResource(R.string.instance_link_replaced)
        }

        Link.OlderDaemon -> {
            stringResource(R.string.instance_link_older, host)
        }

        Link.NewerDaemon -> {
            stringResource(R.string.instance_link_newer)
        }

        Link.Failed -> {
            stringResource(R.string.instance_link_failed)
        }
    }

/** The live path (design section 13.5): "Tailscale · 38 ms" or "Wi-Fi · 9 ms", from the candidate's scope. */
@Composable
fun pathWords(
    scope: Candidate.Scope,
    latencyMs: Long,
): String =
    when (scope) {
        Candidate.Scope.TAILNET -> stringResource(R.string.instance_path_tailnet, latencyMs)
        Candidate.Scope.LAN -> stringResource(R.string.instance_path_lan, latencyMs)
    }
