package io.tezra.fermix.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The objects events nest, and the closed value sets of their fields. A value outside a set is a
// decode error naming its field; the rule that a client ignores what it does not know is about
// unknown events, not unknown values (design section 7).

/** What an upload is; protocol v2 accepts `video` (design section 7, the `attach_begin.kind: video` row). */
@Serializable
enum class AttachKind {
    @SerialName("image")
    IMAGE,

    @SerialName("audio")
    AUDIO,

    @SerialName("document")
    DOCUMENT,

    @SerialName("video")
    VIDEO,
}

/** The phone's platform (design section 7, the `pair_request.platform` row). */
@Serializable
enum class Platform {
    @SerialName("ios")
    IOS,

    @SerialName("android")
    ANDROID,
}

/** An APNs environment: protocol v1's `push_register`, and v2's for `ios` only. */
@Serializable
enum class PushEnvironment {
    @SerialName("development")
    DEVELOPMENT,

    @SerialName("production")
    PRODUCTION,
}

/** A push service a daemon is configured for (design section 7, the `pair_approved.push` row). */
@Serializable
enum class PushPlatform {
    @SerialName("apns")
    APNS,

    @SerialName("fcm")
    FCM,
}

/** Which attestation a pairing presents (design section 7, the `pair_request.attestation` row). */
@Serializable
enum class AttestationKind {
    @SerialName("android_keymint")
    ANDROID_KEYMINT,

    @SerialName("apple_app_attest")
    APPLE_APP_ATTEST,
}

/** Where a candidate route lies. */
@Serializable
enum class CandidateScope {
    @SerialName("lan")
    LAN,

    @SerialName("tailnet")
    TAILNET,
}

/** The answer to `attach_begin` and `attach_end`. */
@Serializable
enum class AttachStatus {
    @SerialName("upload")
    UPLOAD,

    @SerialName("present")
    PRESENT,
}

/** A tool's start or stop. */
@Serializable
enum class ToolPhase {
    @SerialName("start")
    START,

    @SerialName("stop")
    STOP,
}

/**
 * A timeline row's kind; protocol v2 closes the set and adds `system` (design section 7,
 * `history_page.messages[].kind`).
 */
@Serializable
enum class MessageKind {
    @SerialName("text")
    TEXT,

    @SerialName("media")
    MEDIA,

    @SerialName("system")
    SYSTEM,
}

/** How an approval card ended. */
@Serializable
enum class ApprovalOutcome {
    @SerialName("approved")
    APPROVED,

    @SerialName("denied")
    DENIED,

    @SerialName("expired")
    EXPIRED,
}

/** Why a pairing was refused; the last three are protocol v2's (design section 7, the `pair_denied.reason` row). */
@Serializable
enum class PairDeniedReason {
    @SerialName("denied")
    DENIED,

    @SerialName("timeout")
    TIMEOUT,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("device_disconnected")
    DEVICE_DISCONNECTED,

    @SerialName("attestation")
    ATTESTATION,

    @SerialName("attestation_unavailable")
    ATTESTATION_UNAVAILABLE,

    @SerialName("platform_unsupported")
    PLATFORM_UNSUPPORTED,
}

/** Which side must update, on `unsupported_protocol_version`. */
@Serializable
enum class VersionDirection {
    @SerialName("client_too_old")
    CLIENT_TOO_OLD,

    @SerialName("client_too_new")
    CLIENT_TOO_NEW,
}

/** Whether a chat's model is the config's or its own (design section 7, the `model_changed` row). */
@Serializable
enum class ModelSource {
    @SerialName("override")
    OVERRIDE,

    @SerialName("default")
    DEFAULT,
}

/** Where a request stands (design section 7, the `request_status` row). */
@Serializable
enum class RequestState {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("running")
    RUNNING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,
}

/** Protocol v2: the attestation a `pair_request` presents; [certLengths] split its raw tail, leaf first. */
@Serializable
data class Attestation(
    @SerialName("kind") val kind: AttestationKind,
    @SerialName("cert_lengths") val certLengths: List<Int>,
)

/** A profile of the daemon; there is one, `main`, today. */
@Serializable
data class Profile(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
)

/** A route to the daemon for later reconnects. */
@Serializable
data class Candidate(
    @SerialName("host") val host: String,
    @SerialName("interface") val networkInterface: String,
    @SerialName("scope") val scope: CandidateScope,
)

/** One entry of the command palette. */
@Serializable
data class CommandDescriptor(
    @SerialName("name") val name: String,
    @SerialName("aliases") val aliases: List<String>,
    @SerialName("description") val description: String,
)

/**
 * What the daemon can do. Protocol v2 adds a flag for every new server event family, the push
 * services, and the chat's model state (design section 7, the `hello_ack.caps` rows); an absent flag
 * is a daemon without that family.
 */
@Serializable
data class Caps(
    @SerialName("commands") val commands: List<CommandDescriptor>,
    @SerialName("media") val media: Boolean? = null,
    @SerialName("streaming") val streaming: Boolean? = null,
    @SerialName("max_media_bytes") val maxMediaBytes: Long,
    @SerialName("thoughts") val thoughts: Boolean? = null,
    @SerialName("turn_done") val turnDone: Boolean? = null,
    @SerialName("transcripts") val transcripts: Boolean? = null,
    @SerialName("models") val models: Boolean? = null,
    @SerialName("search") val search: Boolean? = null,
    @SerialName("approval_replay") val approvalReplay: Boolean? = null,
    @SerialName("reply") val reply: Boolean? = null,
    @SerialName("push") val push: List<PushPlatform>? = null,
    @SerialName("model_state") val modelState: ModelState? = null,
)

/** Protocol v2: who this daemon is (design section 7, the `hello_ack.instance` row). */
@Serializable
data class Instance(
    @SerialName("label") val label: String,
    @SerialName("host") val host: String,
    @SerialName("profile") val profile: String,
)

/** Protocol v2: a turn still running when the phone said hello (design section 7, `hello_ack.active_turns`). */
@Serializable
data class ActiveTurn(
    @SerialName("turn_id") val turnId: String,
    @SerialName("in_reply_to") val inReplyTo: String? = null,
)

/** Protocol v2: a model by provider, with its label. */
@Serializable
data class ModelRef(
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String,
    @SerialName("label") val label: String,
)

/** Protocol v2: the config's model, and the chat's own when it has one (design section 7, `caps.model_state`). */
@Serializable
data class ModelState(
    @SerialName("default") val defaultModel: ModelRef,
    @SerialName("override") val overrideModel: ModelRef? = null,
)

/** Protocol v2: the provider and model that produced a bubble (design section 7, the `text_done.route` row). */
@Serializable
data class Route(
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String,
)

/** A blob a row carries. */
@Serializable
data class MediaRef(
    @SerialName("ref") val ref: String,
    @SerialName("kind") val kind: String,
    @SerialName("mime") val mime: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("sha256") val sha256: String? = null,
    @SerialName("filename") val filename: String? = null,
    @SerialName("caption") val caption: String? = null,
)

/** A link preview stored on its row. */
@Serializable
data class LinkPreviewCard(
    @SerialName("url") val url: String,
    @SerialName("site") val site: String,
    @SerialName("title") val title: String,
    @SerialName("description") val description: String? = null,
    @SerialName("image_ref") val imageRef: String? = null,
)

/** A timeline row as a history page carries it (PROTOCOL.md "Timeline shapes"). */
@Serializable
data class HistoryMessage(
    @SerialName("server_seq") val serverSeq: ULong,
    @SerialName("role") val role: String,
    @SerialName("content") val content: String,
    @SerialName("ts") val ts: String,
    @SerialName("media_refs") val mediaRefs: List<MediaRef>,
    @SerialName("kind") val kind: MessageKind? = null,
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    @SerialName("in_reply_to") val inReplyTo: String? = null,
    @SerialName("metadata") val metadata: JsonObject? = null,
    @SerialName("link_previews") val linkPreviews: List<LinkPreviewCard>? = null,
    @SerialName("truncated") val truncated: Boolean? = null,
)

/**
 * Protocol v2: one entry of a `models` page (design section 7, the `models` row). A provider whose
 * live listing failed is `{provider, listing_unavailable: true}` and nothing else; every other entry
 * carries a model, its label and the three flags.
 */
@Serializable
data class ModelEntry(
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("trait") val trait: String? = null,
    @SerialName("streams") val streams: Boolean? = null,
    @SerialName("active") val active: Boolean? = null,
    @SerialName("default") val isDefault: Boolean? = null,
    @SerialName("listing_unavailable") val listingUnavailable: Boolean? = null,
)

/** Protocol v2: how one request stands (design section 7, the `request_status` row). */
@Serializable
data class RequestOutcome(
    @SerialName("client_msg_id") val clientMsgId: String,
    @SerialName("status") val status: RequestState,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("result_server_seq") val resultServerSeq: ULong? = null,
    @SerialName("error") val error: String? = null,
)

/** Protocol v2: a row updated in place, with what changed (design section 7, the `mutation_seq` row). */
@Serializable
data class MutationRow(
    @SerialName("server_seq") val serverSeq: ULong,
    @SerialName("mutation_seq") val mutationSeq: ULong,
    @SerialName("content") val content: String? = null,
    @SerialName("metadata") val metadata: JsonObject? = null,
    @SerialName("media_refs") val mediaRefs: List<MediaRef>? = null,
)

/** Protocol v2: a span of a search excerpt, in Unicode scalar values. */
@Serializable
data class MatchRange(
    @SerialName("start") val start: Int,
    @SerialName("length") val length: Int,
)

/** Protocol v2: one search hit, the companion socket's shape (design section 7, the `history_search` row). */
@Serializable
data class SearchHit(
    @SerialName("server_seq") val serverSeq: ULong,
    @SerialName("role") val role: String,
    @SerialName("ts") val ts: String,
    @SerialName("excerpt") val excerpt: String,
    @SerialName("ranges") val ranges: List<MatchRange>,
)
