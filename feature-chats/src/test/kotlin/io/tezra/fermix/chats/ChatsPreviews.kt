package io.tezra.fermix.chats

import androidx.compose.runtime.Composable
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.RepairNotice
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews
import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.Candidate

// The Chats list, its trust states and the app lock at the twelve windows of @FermixPreviews.
// The list holds three Fermixes: production on suj-mbp over Tailscale with two unread, the dev daemon on
// the same Mac connecting with a draft and its DEV tag, and a Linux box that unpaired this phone. The rows'
// other states are a list of their own, as the canon's trust-state frame: the dev daemon thinking, a
// daemon reinstalled, a connection taken over, a protocol error, and a Fermix a restore dropped.

private val NO_ACTIONS =
    ChatsActions(
        onAdd = {},
        onAppLock = {},
        onOpen = {},
        onMoveToTop = {},
        onRename = { _, _ -> },
        onDetails = {},
        onUnpair = {},
        onRepair = {},
        onDismissRepair = {},
    )

private val THREE =
    ChatsUi(
        rows =
            listOf(
                ChatRow(
                    record = sample(1, tint = "Slate"),
                    profileId = "main",
                    agentName = null,
                    dev = false,
                    link = Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true),
                    line = RowLine.Message("Raised the export timeout and re-ran the job."),
                    time = "09:41",
                    unread = 2,
                ),
                ChatRow(
                    record = sample(2, profile = "fermix-dev", tint = "Ocean"),
                    profileId = "main",
                    agentName = null,
                    dev = true,
                    link = Link.Connecting,
                    line = RowLine.Draft("restart the ingest worker after the nightly export finishes"),
                    time = "09:40",
                    unread = 0,
                ),
                ChatRow(
                    record = sample(3, host = LINUX_HOST, tint = "Sage"),
                    profileId = "main",
                    agentName = null,
                    dev = false,
                    link = Link.Revoked,
                    line = RowLine.Speaks(Link.Revoked),
                    time = "Tue",
                    unread = 0,
                ),
            ),
        repairs = emptyList(),
    )

/** A row of [record] whose link and second line are [link] and [line], at [time], nothing unread. */
private fun stateRow(
    record: Instance,
    link: Link,
    line: RowLine,
    time: String,
): ChatRow =
    ChatRow(
        record = record,
        profileId = "main",
        agentName = null,
        dev = record.profile == "fermix-dev",
        link = link,
        line = line,
        time = time,
        unread = 0,
    )

private val STATES =
    ChatsUi(
        rows =
            listOf(
                stateRow(
                    sample(2, profile = "fermix-dev", tint = "Ocean"),
                    Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true),
                    RowLine.Thinking,
                    "09:40",
                ),
                stateRow(sample(4, tint = "Clay"), Link.IdentityChanged, RowLine.Speaks(Link.IdentityChanged), "Mon"),
                stateRow(
                    sample(3, host = LINUX_HOST, tint = "Sage"),
                    Link.Replaced,
                    RowLine.Speaks(Link.Replaced),
                    "Tue",
                ),
                stateRow(
                    sample(5, nickname = "Studio", tint = "Plum"),
                    Link.ProtocolError,
                    RowLine.Speaks(Link.ProtocolError),
                    "Wed",
                ),
            ),
        repairs = listOf(RepairNotice(sample(6).id, "Lab")),
    )

@FermixPreviews
@Composable
fun ChatsListPreview() {
    FermixPreviewTheme { ChatsScreen(ui = THREE, actions = NO_ACTIONS) }
}

@FermixPreviews
@Composable
fun ChatsRowStatesPreview() {
    FermixPreviewTheme { ChatsScreen(ui = STATES, actions = NO_ACTIONS) }
}

@FermixPreviews
@Composable
fun ChatsEmptyPreview() {
    FermixPreviewTheme { ChatsScreen(ui = ChatsUi(rows = emptyList(), repairs = emptyList()), actions = NO_ACTIONS) }
}

@FermixPreviews
@Composable
fun RevokedPreview() {
    FermixPreviewTheme { TrustScreen(Trust.REVOKED, HOST, onBack = {}, onPairAgain = {}, onRemove = {}) }
}

@FermixPreviews
@Composable
fun IdentityChangedPreview() {
    FermixPreviewTheme { TrustScreen(Trust.IDENTITY_CHANGED, HOST, onBack = {}, onPairAgain = {}, onRemove = {}) }
}

@FermixPreviews
@Composable
fun AppLockPreview() {
    FermixPreviewTheme { LockScreen(onUnlock = {}) }
}

@FermixPreviews
@Composable
fun AppLockSettingPreview() {
    FermixPreviewTheme { AppLockScreen(on = true, available = true, onBack = {}, onChange = {}) }
}
