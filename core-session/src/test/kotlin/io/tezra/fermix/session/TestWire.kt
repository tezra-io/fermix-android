package io.tezra.fermix.session

import io.tezra.fermix.protocol.CandidateScope
import io.tezra.fermix.protocol.Caps
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.Profile
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.Candidate as WireCandidate

// What the tests' daemon says, built from core-protocol's models; the frames it writes and reads are the demo
// daemon's own (io.tezra.fermix.demo's DaemonFrames.kt), so the tests and the demo frame alike.

internal const val PROFILE = "main"
internal const val TS = "2026-10-01T09:00:00Z"

/** A paired session's acknowledgement at protocol v2: one profile, one tailnet candidate, empty cursors. */
internal val HELLO_ACK =
    ServerEvent.HelloAck(
        sessionId = "mobile-session-1",
        minVersion = 2,
        maxVersion = 2,
        profiles = listOf(Profile(PROFILE, "Fermix")),
        candidates = listOf(WireCandidate("100.101.102.103", "utun4", CandidateScope.TAILNET)),
        historyHeadSeq = 0uL,
        readUpToSeq = 0uL,
        caps = Caps(commands = emptyList(), maxMediaBytes = 20_971_520),
        mutationHeadSeq = 0uL,
    )

internal fun message(
    seq: ULong,
    role: String = "assistant",
    clientMsgId: String? = null,
): HistoryMessage = HistoryMessage(seq, role, "Row $seq", TS, emptyList(), clientMsgId = clientMsgId)

internal fun row(
    seq: ULong,
    role: String = "assistant",
    clientMsgId: String? = null,
): ServerEvent.Row = ServerEvent.Row(PROFILE, seq, role, "Row $seq", TS, emptyList(), clientMsgId = clientMsgId)

internal fun page(
    messages: List<HistoryMessage>,
    nextAfterSeq: ULong,
    head: ULong,
    prevBeforeSeq: ULong? = null,
): ServerEvent.HistoryPage = ServerEvent.HistoryPage(PROFILE, messages, nextAfterSeq, head, prevBeforeSeq)

internal fun msg(
    clientMsgId: String,
    text: String = "Hello",
    retryOf: String? = null,
): ClientEvent.Msg = ClientEvent.Msg(clientMsgId, PROFILE, text, emptyList(), retryOf)
