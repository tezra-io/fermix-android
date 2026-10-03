package io.tezra.fermix.instance

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.DiagnosticKind
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.Reachability
import java.time.ZoneOffset

// The Instance screen at the twelve windows of @FermixPreviews: a Fermix the owner named, connected over
// the LAN with Tailscale likely up, after a test whose race its tailnet address won, so that both its
// candidates are lit as the canon draws them, each by its own handshake, with a few lines in its log;
// and the foot of the page of one that cannot be reached, without FCM, on a debug build. The pairing's day
// is read in UTC, so the images are the same in every zone.

private val LOG =
    listOf(
        Diagnostic(atMs = 2_000, kind = DiagnosticKind.CLOSED, detail = "1000 by daemon"),
        Diagnostic(atMs = 95_000, kind = DiagnosticKind.UNKNOWN_EVENT, detail = "t=call_offer"),
    )

private val NO_ACTIONS =
    InstanceActions(
        onBack = {},
        onRename = {},
        onTest = {},
        onNotifications = {},
        onPreviews = {},
        onClearCache = {},
        onUnpair = {},
    )

@FermixPreviews
@Composable
fun InstanceScreenPreview() {
    val record = sample(nickname = "Studio")
    val test = TestState.Done(TestOutcome.Reached(TAILNET, millis = 38))
    FermixPreviewTheme {
        InstanceScreen(
            ui =
                InstanceUi(
                    record = record,
                    others = emptyList(),
                    link = Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true),
                    diagnostics = LOG,
                    reachable = reachableCandidates(record.candidates, LAN, test, Reachability.TAILNET_LIKELY),
                    previews = true,
                    cacheBytes = 184_000_000,
                    test = test,
                    releaseBuild = true,
                ),
            actions = NO_ACTIONS,
            zone = ZoneOffset.UTC,
        )
    }
}

@FermixPreviews
@Composable
fun InstanceScreenFootPreview() {
    FermixPreviewTheme {
        InstanceScreen(
            ui =
                InstanceUi(
                    record = sample(push = emptyList()),
                    others = emptyList(),
                    link = Link.CannotReach,
                    diagnostics = LOG,
                    reachable = emptySet(),
                    previews = true,
                    cacheBytes = 0,
                    test = TestState.Done(TestOutcome.NotReached(failed = setOf(LAN, TAILNET))),
                    releaseBuild = false,
                ),
            actions = NO_ACTIONS,
            zone = ZoneOffset.UTC,
            // Past the end: the page settles at its foot.
            scroll = rememberScrollState(initial = Int.MAX_VALUE),
        )
    }
}
