package io.tezra.fermix.instance

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.tezra.fermix.transport.Candidate

/**
 * The Instance header's one status line (the owner, 2026-10-10): "Connected" and the path once the session is up
 * and caught up, the link's own words while it is anything else that has words, and nothing while no session is
 * open.
 */
sealed interface StatusLine {
    data class Connected(
        val scope: Candidate.Scope,
        val latencyMs: Long,
    ) : StatusLine

    data class Words(
        val link: Link,
    ) : StatusLine

    data object None : StatusLine
}

/** [link]'s status line: linkWords has none for a link with no session, so neither has the header. */
fun statusLineOf(link: Link): StatusLine =
    when {
        link is Link.Up && link.caughtUp -> StatusLine.Connected(link.scope, link.latencyMs)
        link == Link.NotOpen || link == Link.Closed -> StatusLine.None
        else -> StatusLine.Words(link)
    }

/** [line] in words, "Connected · Wi-Fi · 9 ms", or the link's on [host]; none for [StatusLine.None]. */
@Composable
fun statusWords(
    line: StatusLine,
    host: String,
): String? =
    when (line) {
        is StatusLine.Connected -> {
            val connected = stringResource(R.string.instance_state_connected)
            stringResource(R.string.instance_status_line, connected, pathWords(line.scope, line.latencyMs))
        }

        is StatusLine.Words -> {
            linkWords(line.link, host)
        }

        StatusLine.None -> {
            null
        }
    }
