package io.tezra.fermix.data

import io.tezra.fermix.protocol.Caps
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.MAX_CANDIDATES
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Base64

private const val KEY_BYTES = 32
private const val MAX_PORT = 65_535
private const val HEX_RADIX = 16

/** 32 bytes in standard base64: 43 characters and one `=`. */
private val BASE64_KEY = Regex("[A-Za-z0-9+/]{43}=")

/**
 * The names of the design module's `Tint` (design section 13.1), in its order: what [Instance.tint] may be.
 * This module does not depend on design, so design's TintNamesTest holds the enum to this list.
 */
val TINT_NAMES: List<String> = listOf("Slate", "Sage", "Clay", "Plum", "Ocean", "Sand")

/**
 * One paired daemon as this phone records it (design section 9.1): public data only, never a secret
 * (section 12.4). The device key lives in the Keystore under [keyAlias], and the pairing secret is zeroed
 * once the pairing ends; neither has a field here. The keys are held in the wire's own text, [gatewayPk]
 * and [pushSalt] in standard base64 and [tlsFp] in lowercase hex, so the record is immutable, as DataStore
 * requires, and each is handed out as fresh bytes. [id] is `sha256(gateway_pk)`, computed and never
 * stored: two daemons on one computer share a host name, so everything is keyed by the gateway key
 * (onboarding gotcha 8).
 *
 * [host] and [profile] are the daemon's computer and its home's `[fermix_core] profile` (design D11). [label]
 * is the daemon's own name and [nickname] the owner's, which lives on this phone alone and survives a
 * re-pairing (section 9.2); [tint] is one of [TINT_NAMES], the design module's `Tint` by name, which the UI
 * maps to its colour. [candidates] are the routes for the next race, [caps] the last `hello_ack`'s, none
 * before the first one. [fcmRegisteredAt] is when this phone last sent `push_register`, in Unix milliseconds.
 *
 * Protocol v2 (design section 7) supplies fields this record requires, so a version-1 pairing cannot make
 * one, and the app refuses a version-1 link at scan (D1). [pushSalt] is the `pair_approved.push_salt` row's,
 * required in v2 and absent from v1; [pushPlatforms] the `pair_approved.push` and `hello_ack.caps.push` row's;
 * [profile] the v2 QR's `profile` (the `v` row, section 6.3), which a version-1 link lacks; and [host],
 * [label] and [profile] are refreshed from the `hello_ack.instance` row's `{label, host, profile}`, which
 * v1 does not send; until the first `hello_ack`, a pairing has only the link's `name` for [host] and [label].
 */
@Serializable
data class Instance(
    @SerialName("gateway_pk") val gatewayPk: String,
    @SerialName("tls_fp") val tlsFp: String,
    @SerialName("host") val host: String,
    @SerialName("profile") val profile: String,
    @SerialName("label") val label: String,
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("tint") val tint: String,
    @SerialName("candidates") val candidates: List<
        @Serializable(with = CandidateSerializer::class)
        Candidate,
    >,
    @SerialName("port") val port: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("key_alias") val keyAlias: String,
    @SerialName("push_salt") val pushSalt: String,
    @SerialName("push_platforms") val pushPlatforms: List<PushPlatform>,
    @SerialName("caps") val caps: Caps? = null,
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean,
    @SerialName("fcm_registered_at") val fcmRegisteredAt: Long? = null,
) {
    init {
        keyBytes("gateway_pk", gatewayPk)
        keyBytes("push_salt", pushSalt)
        require(SHA256_HEX.matches(tlsFp)) { "tls_fp is not a SHA-256 in lowercase hex" }
        require(host.isNotBlank()) { "a paired instance has a host" }
        require(profile.isNotBlank()) { "a paired instance has a profile" }
        require(label.isNotBlank()) { "a paired instance has a label" }
        require(nickname == null || isNickname(nickname)) { "nickname '$nickname' is not 1 to 40 trimmed characters" }
        require(tint in TINT_NAMES) { "tint '$tint' is not one of $TINT_NAMES" }
        require(
            candidates.size <= MAX_CANDIDATES,
        ) { "${candidates.size} candidates is past the wire's $MAX_CANDIDATES" }
        require(port in 1..MAX_PORT) { "port $port is outside 1 to $MAX_PORT" }
        require(deviceId.isNotBlank()) { "a paired instance has a device id" }
        require(keyAlias.isNotBlank()) { "a paired instance has a key alias" }
        require(fcmRegisteredAt == null || fcmRegisteredAt >= 0L) { "fcm_registered_at is $fcmRegisteredAt" }
    }

    /** `sha256(gateway_pk)` in lowercase hex: the key of everything this phone keeps for the daemon. */
    @Transient
    val id: String = sha256Hex(gatewayPublicKey())

    /** The row's title: the owner's nickname, or else the daemon's own name (design section 9.2). */
    val title: String get() = nickname ?: label

    /** The daemon's static key, which the IK handshake authenticates; fresh bytes. */
    fun gatewayPublicKey(): ByteArray = keyBytes("gateway_pk", gatewayPk)

    /** The pin of the daemon's TLS certificate (design section 12.3); fresh bytes. */
    fun tlsFingerprint(): ByteArray = tlsFp.chunked(2).map { pair -> pair.toInt(HEX_RADIX).toByte() }.toByteArray()

    /** The salt of this phone's push key (design section 10); fresh bytes. */
    fun pushSaltBytes(): ByteArray = keyBytes("push_salt", pushSalt)
}

/**
 * This phone's instance records, in the Chats list's order: the type the DataStore holds. [repairNotices] are
 * the titles of the instances the launch check dropped, written in the same write as the drop and kept until
 * the owner has seen "Re-pair this Fermix", so a process that dies before showing them loses none.
 */
@Serializable
data class Instances(
    @SerialName("instances") val instances: List<Instance> = emptyList(),
    @SerialName("repair_notices") val repairNotices: List<String> = emptyList(),
) {
    init {
        val ids = instances.map { it.id }
        require(ids.size == ids.toSet().size) { "two records name one gateway key" }
        // A record's alias is deleted when the record goes, which would cut off any other record under it.
        val aliases = instances.map { it.keyAlias }
        require(aliases.size == aliases.toSet().size) { "two records name one key alias" }
        require(repairNotices.none { it.isBlank() }) { "a repair notice names no Fermix" }
    }
}

/** [text]'s 32 bytes, refused unless it is canonical standard base64, as the pairing link holds keys. */
private fun keyBytes(
    field: String,
    text: String,
): ByteArray {
    require(BASE64_KEY.matches(text)) { "$field is not 32 bytes of standard base64" }
    val bytes = Base64.getDecoder().decode(text)
    require(bytes.size == KEY_BYTES && Base64.getEncoder().encodeToString(bytes) == text) {
        "$field is not canonical base64"
    }
    return bytes
}
