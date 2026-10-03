package io.tezra.fermix.instance

import io.tezra.fermix.data.Instance
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.transport.Candidate
import java.util.Base64
import java.util.Locale

internal const val HOST = "suj-mbp"

/** 27 September 2026, 09:41 UTC. */
internal const val PAIRED_AT = 1_790_502_060_000L

internal val LAN = Candidate("192.168.1.24", Candidate.Scope.LAN, Candidate.Kind.IP)
internal val TAILNET = Candidate("100.101.42.7", Candidate.Scope.TAILNET, Candidate.Kind.IP)

private const val KEY_BYTES = 32
private const val PORT = 4031

private fun base64(fill: Int): String = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES) { fill.toByte() })

/** A record as a pairing with the daemon whose gateway key is every byte [gateway] leaves it, with push. */
internal fun sample(
    gateway: Int = 1,
    nickname: String? = null,
    push: List<PushPlatform> = listOf(PushPlatform.FCM),
): Instance =
    Instance(
        gatewayPk = base64(gateway),
        tlsFp = "%02x".format(Locale.ROOT, gateway + 1).repeat(KEY_BYTES),
        host = HOST,
        profile = "fermix",
        label = HOST,
        nickname = nickname,
        tint = "Slate",
        candidates = listOf(LAN, TAILNET),
        port = PORT,
        deviceId = "device-$gateway",
        keyAlias = "fermix.device.$gateway.0102030405060708",
        pushSalt = base64(0x73),
        pushPlatforms = push,
        notificationsEnabled = true,
        deviceName = "Pixel 9 Pro",
        pairedAt = PAIRED_AT,
    )
