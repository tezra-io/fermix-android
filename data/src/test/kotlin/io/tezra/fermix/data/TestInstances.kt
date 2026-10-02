package io.tezra.fermix.data

import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.transport.Candidate
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

internal const val KEY_BYTES = 32

internal fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** Lowercase hex, by the JDK's own formatter rather than the module's. */
internal fun hexOf(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

/** A daemon's 32-byte key, every byte [fill]. */
internal fun key(fill: Int): ByteArray = ByteArray(KEY_BYTES) { fill.toByte() }

/** What [Instance.id] and a cached blob's name must be: the SHA-256 of the bytes, in lowercase hex. */
internal fun idOf(bytes: ByteArray): String = hexOf(MessageDigest.getInstance("SHA-256").digest(bytes))

internal val TAILNET_CANDIDATE = Candidate("100.101.102.103", Candidate.Scope.TAILNET, Candidate.Kind.IP)

/** One paired daemon, its gateway key every byte [gateway]; the rest a pairing would record. */
internal fun instance(
    gateway: Int,
    host: String = "suj-mbp",
    nickname: String? = null,
    tint: String = "Slate",
    keyAlias: String = "fermix.device.$gateway.0102030405060708",
): Instance =
    Instance(
        gatewayPk = base64(key(gateway)),
        tlsFp = hexOf(key(gateway + 1)),
        host = host,
        profile = "fermix",
        label = host,
        nickname = nickname,
        tint = tint,
        candidates = listOf(TAILNET_CANDIDATE),
        port = 4031,
        deviceId = "device-$gateway",
        keyAlias = keyAlias,
        pushSalt = base64(key(0x73)),
        pushPlatforms = listOf(PushPlatform.FCM),
        notificationsEnabled = true,
    )
