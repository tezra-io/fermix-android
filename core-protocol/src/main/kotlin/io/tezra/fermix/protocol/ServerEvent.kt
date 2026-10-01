package io.tezra.fermix.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * An event the daemon sends. A `t` the codec does not know, at the frame's version, is
 * [Unknown]: the client logs it and shows it in Diagnostics, and never fails on it (design section
 * 7, the policy above the table). Every other server event is a [Known] one.
 */
sealed interface ServerEvent {
    /** A server event this codec does not know: its name, its seq, and its JSON object as received. */
    data class Unknown(
        val t: String,
        val seq: ULong,
        val headerJson: String,
    ) : ServerEvent {
        init {
            require(t.isNotEmpty()) { "an unknown event has a name" }
            require(seq >= 1uL) { "an unknown event's seq counts from 1" }
        }
    }

    /**
     * A server event of the catalogue: the JSON header's fields after `v`, `t` and `seq`, declared in
     * the order the vendored fixtures carry them, with protocol v2's additions after them. What each
     * version requires and every bound are in Rules.kt.
     */
    @Serializable
    sealed interface Known : ServerEvent

    /**
     * Completes hello. Protocol v2 adds [instance], [activeTurns], [pendingApprovals] and
     * [mutationHeadSeq] (design section 7, the `hello_ack.instance`, `hello_ack.active_turns`,
     * `hello_ack.pending_approvals` and `mutation_seq` rows), and flags in [caps].
     */
    @Serializable
    @SerialName("hello_ack")
    data class HelloAck(
        @SerialName("session_id") val sessionId: String,
        @SerialName("min_version") val minVersion: Int,
        @SerialName("max_version") val maxVersion: Int,
        @SerialName("profiles") val profiles: List<Profile>,
        @SerialName("candidates") val candidates: List<Candidate>,
        @SerialName("history_head_seq") val historyHeadSeq: ULong,
        @SerialName("read_up_to_seq") val readUpToSeq: ULong,
        @SerialName("caps") val caps: Caps,
        @SerialName("instance") val instance: Instance? = null,
        @SerialName("active_turns") val activeTurns: List<ActiveTurn>? = null,
        @SerialName("pending_approvals") val pendingApprovals: List<String>? = null,
        @SerialName("mutation_head_seq") val mutationHeadSeq: ULong? = null,
    ) : Known

    /** The durable receipt of a `msg` or `command`. */
    @Serializable
    @SerialName("accepted")
    data class Accepted(
        @SerialName("client_msg_id") val clientMsgId: String,
        @SerialName("duplicate") val duplicate: Boolean,
        @SerialName("server_seq") val serverSeq: ULong? = null,
    ) : Known

    /** Whether an upload's bytes are wanted. */
    @Serializable
    @SerialName("attach_status")
    data class AttachStatusEvent(
        @SerialName("attach_id") val attachId: String,
        @SerialName("status") val status: AttachStatus,
    ) : Known

    /** Opens a streamed turn. */
    @Serializable
    @SerialName("turn_started")
    data class TurnStarted(
        @SerialName("profile_id") val profileId: String,
        @SerialName("turn_id") val turnId: String,
        @SerialName("in_reply_to") val inReplyTo: String,
    ) : Known

    /**
     * Streamed text. Protocol v2's [replace] marks [text] as a whole snapshot rather than a suffix
     * (design section 7, the `text_delta.replace` row).
     */
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(
        @SerialName("turn_id") val turnId: String,
        @SerialName("text") val text: String,
        @SerialName("replace") val replace: Boolean? = null,
    ) : Known

    /**
     * A tool's start or stop. [detail] is protocol v1's alone; protocol v2 drops it and adds
     * [status] (design section 7, the `tool_event` row).
     */
    @Serializable
    @SerialName("tool_event")
    data class ToolEvent(
        @SerialName("turn_id") val turnId: String,
        @SerialName("tool") val tool: String,
        @SerialName("phase") val phase: ToolPhase,
        @SerialName("detail") val detail: String? = null,
        @SerialName("status") val status: String? = null,
    ) : Known

    /**
     * Seals a bubble with its canonical text. Protocol v2's [route] names what produced it (design
     * section 7, the `text_done.route` row).
     */
    @Serializable
    @SerialName("text_done")
    data class TextDone(
        @SerialName("turn_id") val turnId: String,
        @SerialName("server_seq") val serverSeq: ULong,
        @SerialName("text") val text: String,
        @SerialName("truncated") val truncated: Boolean? = null,
        @SerialName("route") val route: Route? = null,
    ) : Known

    /** Starts an outbound blob. */
    @Serializable
    @SerialName("media_begin")
    data class MediaBegin(
        @SerialName("ref") val ref: String,
        @SerialName("server_seq") val serverSeq: ULong,
        @SerialName("kind") val kind: String,
        @SerialName("mime") val mime: String,
        @SerialName("size_bytes") val sizeBytes: Long,
        @SerialName("sha256") val sha256: String,
        @SerialName("filename") val filename: String? = null,
        @SerialName("caption") val caption: String? = null,
    ) : Known

    /** One chunk of an outbound blob; its bytes are the frame's raw tail. */
    @Serializable
    @SerialName("media_chunk")
    data class MediaChunk(
        @SerialName("ref") val ref: String,
        @SerialName("index") val index: Int,
    ) : Known

    /** Completes an outbound blob. */
    @Serializable
    @SerialName("media_end")
    data class MediaEnd(
        @SerialName("ref") val ref: String,
        @SerialName("sha256") val sha256: String,
    ) : Known

    /** A turn that failed or was cancelled. */
    @Serializable
    @SerialName("turn_error")
    data class TurnError(
        @SerialName("turn_id") val turnId: String,
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    ) : Known

    /** A timeline row this client did not stream: the history message with `text` for `content`. */
    @Serializable
    @SerialName("row")
    data class Row(
        @SerialName("profile_id") val profileId: String,
        @SerialName("server_seq") val serverSeq: ULong,
        @SerialName("role") val role: String,
        @SerialName("text") val text: String,
        @SerialName("ts") val ts: String,
        @SerialName("media_refs") val mediaRefs: List<MediaRef>,
        @SerialName("kind") val kind: MessageKind? = null,
        @SerialName("client_msg_id") val clientMsgId: String? = null,
        @SerialName("in_reply_to") val inReplyTo: String? = null,
        @SerialName("metadata") val metadata: JsonObject? = null,
        @SerialName("link_previews") val linkPreviews: List<LinkPreviewCard>? = null,
        @SerialName("truncated") val truncated: Boolean? = null,
    ) : Known

    /** An emoji acknowledgement of a client message. */
    @Serializable
    @SerialName("reaction")
    data class Reaction(
        @SerialName("in_reply_to") val inReplyTo: String,
        @SerialName("emoji") val emoji: String,
    ) : Known

    /** A tool asks the owner; the routes are sent back as commands, the token never shown. */
    @Serializable
    @SerialName("approval")
    data class Approval(
        @SerialName("approval_id") val approvalId: String,
        @SerialName("kind") val kind: String,
        @SerialName("text") val text: String,
        @SerialName("token") val token: String,
        @SerialName("ttl_s") val ttlS: Int,
        @SerialName("approve_command") val approveCommand: String,
        @SerialName("deny_command") val denyCommand: String,
        @SerialName("detail") val detail: String? = null,
    ) : Known

    /** Withdraws an approval card. */
    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        @SerialName("approval_id") val approvalId: String,
        @SerialName("outcome") val outcome: ApprovalOutcome,
    ) : Known

    /** A host-resolved preview of the row at [inReplyTo], a `server_seq`. */
    @Serializable
    @SerialName("link_preview")
    data class LinkPreview(
        @SerialName("in_reply_to") val inReplyTo: ULong,
        @SerialName("url") val url: String,
        @SerialName("site") val site: String,
        @SerialName("title") val title: String,
        @SerialName("description") val description: String? = null,
        @SerialName("image_ref") val imageRef: String? = null,
    ) : Known

    /** The profile's read frontier after any client moved it. */
    @Serializable
    @SerialName("read_state")
    data class ReadState(
        @SerialName("profile_id") val profileId: String,
        @SerialName("read_up_to_seq") val readUpToSeq: ULong,
    ) : Known

    /**
     * A page of history, oldest first. Protocol v2's [prevBeforeSeq] is the backward cursor (design
     * section 7, the `history_page.prev_before_seq?` row).
     */
    @Serializable
    @SerialName("history_page")
    data class HistoryPage(
        @SerialName("profile_id") val profileId: String,
        @SerialName("messages") val messages: List<HistoryMessage>,
        @SerialName("next_after_seq") val nextAfterSeq: ULong,
        @SerialName("history_head_seq") val historyHeadSeq: ULong,
        @SerialName("prev_before_seq") val prevBeforeSeq: ULong? = null,
    ) : Known

    /** Reserved: in the catalogue, never sent yet. */
    @Serializable
    @SerialName("notice")
    data class Notice(
        @SerialName("kind") val kind: String,
        @SerialName("text") val text: String,
    ) : Known

    /**
     * Completes pairing. Protocol v2 requires [pushSalt], 32 bytes in standard base64, and adds
     * [push] (design section 7, the `pair_approved.push_salt` and `pair_approved.push` rows).
     */
    @Serializable
    @SerialName("pair_approved")
    data class PairApproved(
        @SerialName("device_id") val deviceId: String,
        @SerialName("candidates") val candidates: List<Candidate>,
        @SerialName("profiles") val profiles: List<Profile>,
        @SerialName("push_salt") val pushSalt: String? = null,
        @SerialName("push") val push: List<PushPlatform>? = null,
    ) : Known

    /** Ends a pairing without one. */
    @Serializable
    @SerialName("pair_denied")
    data class PairDenied(
        @SerialName("reason") val reason: PairDeniedReason,
    ) : Known

    /**
     * A refusal. [code] is the refusal's own word, an open set the client branches on (PROTOCOL.md
     * "Errors"); protocol v2 adds [MUTATIONS_GONE] (design section 7, the `mutation_seq` row).
     */
    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
        @SerialName("client_msg_id") val clientMsgId: String? = null,
        @SerialName("ref") val ref: String? = null,
        @SerialName("direction") val direction: VersionDirection? = null,
        @SerialName("client_version") val clientVersion: Int? = null,
        @SerialName("min_version") val minVersion: Int? = null,
        @SerialName("max_version") val maxVersion: Int? = null,
    ) : Known {
        companion object {
            /** The refusal of a version outside the daemon's window. */
            const val UNSUPPORTED_PROTOCOL_VERSION = "unsupported_protocol_version"

            /** Protocol v2: a mutation cursor older than the feed keeps; the profile's cache is rebuilt. */
            const val MUTATIONS_GONE = "mutations_gone"
        }
    }

    /** Keepalive answer. */
    @Serializable
    @SerialName("pong")
    data object Pong : Known

    /** One frame of a longer event; its bytes are the frame's raw tail (PROTOCOL.md "Continuation frames"). */
    @Serializable
    @SerialName("event_part")
    data class EventPart(
        @SerialName("index") val index: Int,
        @SerialName("count") val count: Int,
    ) : Known

    /** Protocol v2: the rolling thought headings of a turn, a whole snapshot (design section 7, the `thought` row). */
    @Serializable
    @SerialName("thought")
    data class Thought(
        @SerialName("turn_id") val turnId: String,
        @SerialName("in_reply_to") val inReplyTo: String,
        @SerialName("text") val text: String,
    ) : Known

    /** Protocol v2: removes the thought card; never the end of a turn (design section 7, the `thought_done` row). */
    @Serializable
    @SerialName("thought_done")
    data class ThoughtDone(
        @SerialName("turn_id") val turnId: String,
    ) : Known

    /** Protocol v2: a turn completed, once, after its last delivery (design section 7, the `turn_done` row). */
    @Serializable
    @SerialName("turn_done")
    data class TurnDone(
        @SerialName("turn_id") val turnId: String,
    ) : Known

    /**
     * Protocol v2: a page of the model list (design section 7, the `models` row). [next] is true when
     * another page follows; the shape leaves its type open, see ProvisionalV2Test.
     */
    @Serializable
    @SerialName("models")
    data class Models(
        @SerialName("entries") val entries: List<ModelEntry>,
        @SerialName("next") val next: Boolean? = null,
    ) : Known

    /** Protocol v2: a chat's model changed, on every device (design section 7, the `model_changed` row). */
    @Serializable
    @SerialName("model_changed")
    data class ModelChanged(
        @SerialName("profile_id") val profileId: String,
        @SerialName("provider") val provider: String,
        @SerialName("model") val model: String,
        @SerialName("label") val label: String,
        @SerialName("source") val source: ModelSource,
        @SerialName("note") val note: String? = null,
    ) : Known

    /** Protocol v2: a voice note's transcript (design section 7, the `transcript` row). */
    @Serializable
    @SerialName("transcript")
    data class Transcript(
        @SerialName("client_msg_id") val clientMsgId: String,
        @SerialName("text") val text: String,
    ) : Known

    /** Protocol v2: the answer to `request_status` (design section 7, the `request_status` row). */
    @Serializable
    @SerialName("request_status_page")
    data class RequestStatusPage(
        @SerialName("requests") val requests: List<RequestOutcome>,
    ) : Known

    /**
     * Protocol v2: the answer to `mutations_pull` (design section 7, the `mutation_seq` row). [next]
     * is the cursor to pull from next; the shape leaves its type open, see ProvisionalV2Test.
     */
    @Serializable
    @SerialName("mutations_page")
    data class MutationsPage(
        @SerialName("rows") val rows: List<MutationRow>,
        @SerialName("next") val next: ULong? = null,
    ) : Known

    /** Protocol v2: the answer to `history_search` (design section 7, the `history_search` row). */
    @Serializable
    @SerialName("search_results")
    data class SearchResults(
        @SerialName("profile_id") val profileId: String,
        @SerialName("query") val query: String,
        @SerialName("hits") val hits: List<SearchHit>,
        @SerialName("next_before_seq") val nextBeforeSeq: ULong? = null,
    ) : Known
}
