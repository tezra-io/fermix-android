package io.tezra.fermix.chat

import io.tezra.fermix.data.ChatState
import io.tezra.fermix.data.Instance
import io.tezra.fermix.protocol.Caps
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.CommandDescriptor
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.ModelRef
import io.tezra.fermix.protocol.ModelState
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.transport.Candidate
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale

internal const val HOST = "suj-mbp"
internal const val PROFILE = "main"

private const val KEY_BYTES = 32
private const val PORT = 4031
private const val PUSH_SALT_FILL = 0x73

private fun base64(fill: Int): String = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES) { fill.toByte() })

/** The daemon's commands, as the visual canon's palette lists them. */
internal val COMMANDS =
    listOf(
        CommandDescriptor("new", emptyList(), "Start a fresh conversation session."),
        CommandDescriptor("stop", emptyList(), "Stop all running Fermix work and clear queued messages."),
        CommandDescriptor("compact", emptyList(), "Compact this conversation history now."),
        CommandDescriptor("tasks", emptyList(), "List running and recent background work."),
        CommandDescriptor("model", listOf("m"), "Switch the model for this chat."),
        CommandDescriptor("sandbox", emptyList(), "Inspect or update sandbox policy."),
        CommandDescriptor("help", emptyList(), "List available commands."),
    )

/** A record as a pairing with the daemon whose gateway key is every byte [gateway] leaves it. */
internal fun sample(
    gateway: Int = 1,
    host: String = HOST,
    nickname: String? = null,
): Instance =
    Instance(
        gatewayPk = base64(gateway),
        tlsFp = "%02x".format(Locale.ROOT, gateway + 1).repeat(KEY_BYTES),
        host = host,
        profile = "fermix",
        label = host,
        nickname = nickname,
        tint = "Slate",
        candidates = listOf(Candidate("100.101.42.$gateway", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
        port = PORT,
        deviceId = "device-$gateway",
        keyAlias = "fermix.device.$gateway.0102030405060708",
        pushSalt = base64(PUSH_SALT_FILL),
        pushPlatforms = listOf(PushPlatform.FCM),
        caps = Caps(commands = COMMANDS, maxMediaBytes = 20_000_000L),
        notificationsEnabled = true,
    )

/** A record whose daemon says its model: the config's GPT-6 Astra. */
internal fun withModel() =
    sample().let {
        it.copy(
            caps = it.caps?.copy(modelState = ModelState(ModelRef("codex", "gpt-6-astra", "GPT-6 Astra"))),
        )
    }

/** The models the daemon lists, as the canon's "Model" sheet shows them: two providers and one it cannot list. */
internal val ENTRIES =
    listOf(
        ModelEntry("codex", "gpt-6-sol", "GPT-6 Sol"),
        ModelEntry("codex", "gpt-6-luna", "GPT-6 Luna", trait = "fast, cheaper"),
        ModelEntry("anthropic", "claude-opus-5.5", "Claude Opus 5.5", "best quality", streams = false, active = true),
        ModelEntry("anthropic", "claude-haiku-4.5", "Claude Haiku 4.5", "fastest", streams = false),
        ModelEntry("ollama", listingUnavailable = true),
    )

/** A chat with no draft, no agent name and previews on, as a database starts. */
internal val PLAIN_CHAT = ChatState(draft = null, agentName = null, previews = true)

/** 27 Sep 2026, 09:00 UTC: the samples' morning. */
internal val MORNING: Instant = Instant.parse("2026-09-27T09:00:00Z")

/** The samples' zone. */
internal val UTC: ZoneOffset = ZoneOffset.UTC

/** [minutes] past [MORNING], as the daemon writes a time. */
internal fun at(minutes: Long): String = MORNING.plus(minutes, ChronoUnit.MINUTES).toString()

/** [minutes] past [MORNING], in Unix milliseconds. */
internal fun wallAt(minutes: Long): Long = MORNING.plus(minutes, ChronoUnit.MINUTES).toEpochMilli()

/** The agent's row [seq], [text] at [minutes] past [MORNING]. */
internal fun agentRow(
    seq: Int,
    text: String,
    minutes: Long = 0,
    metadata: JsonObject? = null,
): TimelineRow =
    TimelineRow.Message(
        HistoryMessage(
            serverSeq = seq.toULong(),
            role = "assistant",
            content = text,
            ts = at(minutes),
            mediaRefs = emptyList(),
            metadata = metadata,
        ),
    )

/** The owner's row [seq], [text] at [minutes] past [MORNING], sent as [clientMsgId]. */
internal fun userRow(
    seq: Int,
    text: String,
    minutes: Long = 0,
    clientMsgId: String? = "m$seq",
): TimelineRow =
    TimelineRow.Message(
        HistoryMessage(
            serverSeq = seq.toULong(),
            role = "user",
            content = text,
            ts = at(minutes),
            mediaRefs = emptyList(),
            clientMsgId = clientMsgId,
        ),
    )

/** The owner's message [text] as [clientMsgId]. */
internal fun msg(
    clientMsgId: String,
    text: String,
): ClientEvent.Msg = ClientEvent.Msg(clientMsgId, PROFILE, text, emptyList())
