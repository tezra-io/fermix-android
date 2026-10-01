package io.tezra.fermix.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An event the phone sends: the JSON header's fields after `v`, `t` and `seq`, which the codec
 * writes. Fields are declared in the order the vendored fixtures carry them, which is the order the
 * encoder writes them; the fields protocol v2 adds come after them. A field that is optional, or that
 * only one version carries, is nullable and absent when null; what each version requires and every
 * bound are in Rules.kt, and the daemon refuses a client event it does not know.
 */
@Serializable
sealed interface ClientEvent {
    /**
     * The first event of a paired session. [protocolV] equals the envelope's `v`. [lastMutationSeq]
     * is protocol v2's and required there (design section 7, the `mutation_seq` row).
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        @SerialName("device_id") val deviceId: String,
        @SerialName("app_version") val appVersion: String,
        @SerialName("last_server_seq") val lastServerSeq: ULong,
        @SerialName("protocol_v") val protocolV: Int,
        @SerialName("last_mutation_seq") val lastMutationSeq: ULong? = null,
    ) : ClientEvent

    /**
     * A message: text, attachments, or both. [retryOf] is protocol v2's: the request a "Run again"
     * re-runs, for presentation only (design section 7, the `msg.retry_of?` row).
     */
    @Serializable
    @SerialName("msg")
    data class Msg(
        @SerialName("client_msg_id") val clientMsgId: String,
        @SerialName("profile_id") val profileId: String,
        @SerialName("text") val text: String,
        @SerialName("attach_ids") val attachIds: List<String>,
        @SerialName("retry_of") val retryOf: String? = null,
    ) : ClientEvent

    /** Announces an upload by its digest, before any byte of it. */
    @Serializable
    @SerialName("attach_begin")
    data class AttachBegin(
        @SerialName("attach_id") val attachId: String,
        @SerialName("kind") val kind: AttachKind,
        @SerialName("mime") val mime: String,
        @SerialName("size_bytes") val sizeBytes: Long,
        @SerialName("name") val name: String? = null,
        @SerialName("sha256") val sha256: String,
    ) : ClientEvent

    /** One chunk of an upload; its bytes are the frame's raw tail. */
    @Serializable
    @SerialName("attach_chunk")
    data class AttachChunk(
        @SerialName("attach_id") val attachId: String,
        @SerialName("index") val index: Int,
    ) : ClientEvent

    /** Ends an upload. */
    @Serializable
    @SerialName("attach_end")
    data class AttachEnd(
        @SerialName("attach_id") val attachId: String,
        @SerialName("sha256") val sha256: String,
    ) : ClientEvent

    /** A slash command through the daemon's registry: `/name args`. */
    @Serializable
    @SerialName("command")
    data class Command(
        @SerialName("client_msg_id") val clientMsgId: String,
        @SerialName("profile_id") val profileId: String,
        @SerialName("name") val name: String,
        @SerialName("args") val args: String? = null,
    ) : ClientEvent

    /** Stops one request's turn. */
    @Serializable
    @SerialName("cancel")
    data class Cancel(
        @SerialName("profile_id") val profileId: String,
        @SerialName("client_msg_id") val clientMsgId: String,
    ) : ClientEvent

    /**
     * A page of history. Protocol v1 pages forward only, from [afterSeq]; protocol v2 takes exactly
     * one of [afterSeq] and [beforeSeq], the backward cursor (design section 7, the
     * `history_pull.before_seq?` row).
     */
    @Serializable
    @SerialName("history_pull")
    data class HistoryPull(
        @SerialName("profile_id") val profileId: String,
        @SerialName("after_seq") val afterSeq: ULong? = null,
        @SerialName("limit") val limit: Int,
        @SerialName("before_seq") val beforeSeq: ULong? = null,
    ) : ClientEvent

    /** Downloads a blob a row names. */
    @Serializable
    @SerialName("media_fetch")
    data class MediaFetch(
        @SerialName("ref") val ref: String,
    ) : ClientEvent

    /**
     * A push token. Protocol v1 carries an APNs token, [apnsToken] and [environment]; protocol v2 is
     * platform-neutral, [platform] and [token], with [environment] only for `ios` (design section 7,
     * the `push_register` row).
     */
    @Serializable
    @SerialName("push_register")
    data class PushRegister(
        @SerialName("apns_token") val apnsToken: String? = null,
        @SerialName("platform") val platform: Platform? = null,
        @SerialName("token") val token: String? = null,
        @SerialName("environment") val environment: PushEnvironment? = null,
    ) : ClientEvent

    /** The cumulative cursor of daemon output this phone has announced. */
    @Serializable
    @SerialName("ack")
    data class Ack(
        @SerialName("server_seq") val serverSeq: ULong,
    ) : ClientEvent

    /** Moves the profile's read frontier. */
    @Serializable
    @SerialName("read_state")
    data class ReadState(
        @SerialName("profile_id") val profileId: String,
        @SerialName("read_up_to_seq") val readUpToSeq: ULong,
    ) : ClientEvent

    /**
     * The first event of a pairing session. Protocol v2 requires [platform] and [attestation], with
     * the attestation's DER chain as the frame's raw tail (design section 7, the
     * `pair_request.platform` and `pair_request.attestation` rows).
     */
    @Serializable
    @SerialName("pair_request")
    data class PairRequest(
        @SerialName("device_name") val deviceName: String,
        @SerialName("model") val model: String,
        @SerialName("app_version") val appVersion: String,
        @SerialName("platform") val platform: Platform? = null,
        @SerialName("attestation") val attestation: Attestation? = null,
    ) : ClientEvent

    /** Removes this phone; the host stays authoritative. */
    @Serializable
    @SerialName("unpair")
    data object Unpair : ClientEvent

    /** Keepalive. */
    @Serializable
    @SerialName("ping")
    data object Ping : ClientEvent

    /** Protocol v2: clears this phone's push token (design section 7, the `push_unregister` row). */
    @Serializable
    @SerialName("push_unregister")
    data object PushUnregister : ClientEvent

    /** Protocol v2: asks how requests ended (design section 7, the `request_status` row). */
    @Serializable
    @SerialName("request_status")
    data class RequestStatus(
        @SerialName("client_msg_ids") val clientMsgIds: List<String>,
    ) : ClientEvent

    /** Protocol v2: rows updated in place since a mutation cursor (design section 7, the `mutation_seq` row). */
    @Serializable
    @SerialName("mutations_pull")
    data class MutationsPull(
        @SerialName("profile_id") val profileId: String,
        @SerialName("after_mutation_seq") val afterMutationSeq: ULong,
        @SerialName("limit") val limit: Int,
    ) : ClientEvent

    /** Protocol v2: searches the whole history (design section 7, the `history_search` row). */
    @Serializable
    @SerialName("history_search")
    data class HistorySearch(
        @SerialName("profile_id") val profileId: String,
        @SerialName("query") val query: String,
        @SerialName("limit") val limit: Int,
        @SerialName("before_seq") val beforeSeq: ULong? = null,
    ) : ClientEvent

    /** Protocol v2: asks for the paged `models` list (design section 7, the `models` row). */
    @Serializable
    @SerialName("models_pull")
    data object ModelsPull : ClientEvent
}
