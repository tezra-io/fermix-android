package io.tezra.fermix.chats

import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.transport.Candidate
import java.util.Base64
import java.util.Locale

internal const val HOST = "suj-mbp"
internal const val LINUX_HOST = "suj-linux"

private const val KEY_BYTES = 32
private const val PORT = 4031

/** The byte every byte of the push salt is. */
private const val PUSH_SALT_FILL = 0x73

private fun base64(fill: Int): String = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES) { fill.toByte() })

/** A record as a pairing with the daemon whose gateway key is every byte [gateway] leaves it. */
internal fun sample(
    gateway: Int,
    host: String = HOST,
    profile: String = "fermix",
    tint: String = "Slate",
    nickname: String? = null,
): Instance =
    Instance(
        gatewayPk = base64(gateway),
        tlsFp = "%02x".format(Locale.ROOT, gateway + 1).repeat(KEY_BYTES),
        host = host,
        profile = profile,
        label = host,
        nickname = nickname,
        tint = tint,
        candidates = listOf(Candidate("100.101.42.$gateway", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
        port = PORT,
        deviceId = "device-$gateway",
        keyAlias = "fermix.device.$gateway.0102030405060708",
        pushSalt = base64(PUSH_SALT_FILL),
        pushPlatforms = listOf(PushPlatform.FCM),
        notificationsEnabled = true,
    )

/** A chat with no draft, no agent name and previews on, as a database starts. */
internal val PLAIN_CHAT = ChatState(draft = null, agentName = null, previews = true)

/** The daemon's row [seq], [text] sent at [ts]. */
internal fun message(
    seq: Int,
    text: String,
    ts: String = "2026-09-27T09:41:00Z",
): TimelineRow =
    TimelineRow.Message(
        HistoryMessage(serverSeq = seq.toULong(), role = "assistant", content = text, ts = ts, mediaRefs = emptyList()),
    )
