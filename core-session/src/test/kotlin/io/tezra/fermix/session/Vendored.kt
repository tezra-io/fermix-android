package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Frame
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.decodeServerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

// The vendored contract's fixtures, read from contracts/mobile itself, which core-session/build.gradle.kts puts
// on the test classpath: each line decoded by core-protocol's own decoder, as the daemon wrote it at protocol
// v1. A session refuses a v1 frame, so a test sends the model it decodes to again at v2.

private const val SERVER_EVENTS = "/fixtures/server_events.jsonl"
private const val SERVER_BINARY_FRAMES = "/fixtures/server_binary_frames.jsonl"
private const val CLIENT_EVENTS = "/fixtures/client_events.jsonl"

private fun vendoredLines(path: String): List<String> {
    val stream = checkNotNull(ClientFrame::class.java.getResourceAsStream(path)) { "$path is not on the classpath" }
    return stream
        .use {
            it.readBytes().decodeToString(
                throwOnInvalidSequence = true,
            )
        }.lines()
        .filter { it.isNotEmpty() }
}

private fun linesOf(
    path: String,
    t: String,
): List<String> = vendoredLines(path).filter { "\"t\":\"$t\"" in it }

/** The [nth] `t` [t] line of server_events.jsonl, counted from 1, as its model. */
internal fun vendoredServer(
    t: String,
    nth: Int = 1,
): ServerEvent.Known {
    val line = linesOf(SERVER_EVENTS, t)[nth - 1]
    return decodeServerEvent(Frame(line.encodeToByteArray(), ByteArray(0)).encode()).event as ServerEvent.Known
}

/** The `t` [t] line of server_binary_frames.jsonl: its model and its raw tail. */
internal fun vendoredServerBinary(t: String): Pair<ServerEvent.Known, ByteArray> {
    val json = Json.parseToJsonElement(linesOf(SERVER_BINARY_FRAMES, t).first()).jsonObject
    val header = json.getValue("header").toString().encodeToByteArray()
    val raw = Base64.getDecoder().decode(json.getValue("bytes_b64").jsonPrimitive.content)
    return decodeServerEvent(Frame(header, raw).encode()).event as ServerEvent.Known to raw
}

/** The [nth] `t` [t] line of client_events.jsonl, as the daemon reads it. */
internal fun vendoredClient(
    t: String,
    nth: Int = 1,
): ClientEvent = clientFrame(Frame(linesOf(CLIENT_EVENTS, t)[nth - 1].encodeToByteArray(), ByteArray(0)).encode()).event

/** The vendored `error` of [code]. */
internal fun vendoredError(code: String): ServerEvent.Error =
    linesOf(SERVER_EVENTS, "error")
        .map { decodeServerEvent(Frame(it.encodeToByteArray(), ByteArray(0)).encode()).event as ServerEvent.Error }
        .first { it.code == code }
