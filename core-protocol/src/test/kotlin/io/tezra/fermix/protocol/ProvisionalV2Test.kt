package io.tezra.fermix.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

// PROVISIONAL UNTIL THE PROTOCOL V2 EXPORT IS VENDORED (engine stage D1). Every test in this file
// gates a shape protocol v2 adds or changes, from a sample frame written here out of design section
// 7, because the vendored export is protocol v1 (v0.12.1). When D1's export is vendored, its
// fixtures replace these samples: each shape listed below is checked against them, the listed
// assumptions are settled by the export, and what protocol v1 alone carries is reconsidered.
//
// The provisional shapes, by design section 7 row:
//   client events (V2_CLIENT_EVENTS): push_unregister, request_status, mutations_pull,
//     history_search, models_pull
//   server events (V2_SERVER_EVENTS): thought, thought_done, turn_done, models, model_changed,
//     transcript, request_status_page, mutations_page, search_results
//   fields added to protocol v1 shapes (V2_FIELDS); hello.last_mutation_seq,
//     pair_request.platform/attestation and pair_approved.push_salt are required in v2
//   push_register: v2 replaces {apns_token, environment} with {platform, token, environment?}
//   tool_event: v2 drops detail and adds status
//   history_pull: v2 takes exactly one of after_seq and before_seq
//   pair_request: v2 carries the DER chain as its raw tail
//   closed sets (V2_VALUES, V2_CLOSED_SETS and the enums): pair_denied.reason gains three values;
//     attach_begin.kind is image, audio, document or video; a timeline row's kind is text, media or
//     system
//   error.code mutations_gone (ServerEvent.Error.MUTATIONS_GONE)
//   the pairing link (`v` row, QR v=2): link version 2, and its required profile parameter
//     (REQUIRED_PARAMETERS)
//   the bounds of every shape above (V2_CLIENT_BOUNDS, V2_SERVER_BOUNDS)
//   the fields every shape above requires (V2_CLIENT_REQUIRED, V2_SERVER_REQUIRED)
//
// Rows of section 7 this module deliberately does not model, each to be settled by the export:
//   - `reaction` durability: history_page.messages[].metadata.reaction {emoji, ts}, and text_done.route
//     "also on the timeline row's metadata": a row's metadata stays a free-form JSON object here, and
//     the module that renders the timeline reads them from it;
//   - msg.reply_to_seq, advertised by caps.reply: reserved for M52 S2 (false in v2), so msg has no such
//     field and caps.reply is read but never acted on;
//   - Push plaintext ({kind, profile_id, ...}, kinds message, approval and turn_failed): the payload of
//     an APNs or FCM push, never a frame of this wire; the push module reads it (design section 12.2);
//   - Close codes (4004, device not paired, is new): a WebSocket close, never a frame; core-session maps
//     4003 and 4004 to the revoked state (design section 12.2).
// The `ack` row and caps.streaming "truthful per the active route" change when the phone sends or
// trusts a field, not any shape, so they have nothing here: ack stays {server_seq}, streaming a flag.
// What is version-bound, and what is not:
//   - a top-level field one version alone carries is unknown in the other, ignored on decode and
//     refused on encode (Rules.kt);
//   - a nested field protocol v2 adds (the caps flags, caps.model_state) and a value protocol v2 adds
//     to a closed set (pair_denied.reason's, attach_begin.kind video, a row's kind system) are not
//     version-bound: they decode and encode at v1 as at v2. No v1 daemon sends them, a v2 app refuses a
//     v1 daemon at hello (design D1), and the app encodes only v2, so binding them would add a table and
//     change nothing the app can meet.
//
// Assumptions where section 7 leaves a shape open, each to be settled by the export:
//   - models.next is a boolean, true when another page follows: models_pull carries no cursor;
//   - mutations_page.next is an unsigned integer, the after_mutation_seq of the next pull;
//   - mutations_pull.limit is 1 to 200, as history_pull's;
//   - request_status carries 1 to 32 ids; request_status_page answers at most 32 requests;
//   - request_status_page.requests[].error is the failure's error code, a string;
//   - tool_event.status is a non-empty string; its values are not named;
//   - text_delta.replace may be false, which means what its absence does;
//   - history_page.next_after_seq stays required on a backward page. The engine's companion socket
//     already pages this timeline backward with another shape: its history_page requires only
//     profile_id, messages and history_head_seq, a forward page carries next_after_seq, and a backward
//     page carries next_before_seq (apps/fermix_core/priv/companion/protocol.schema.json), where
//     section 7 names the cursor prev_before_seq. If D1 takes the companion's shape, this codec refuses
//     every backward page (MissingField next_after_seq) and reads no cursor from it. Open for the owner:
//     on a v2 backward page, is next_after_seq required, and is the cursor prev_ or next_before_seq?
//   - the hello_ack, caps and text_done additions are optional ("additive"), and pair_approved.push too;
//   - thought.text is not bounded here: its 1,000 units are the daemon's String.length, graphemes,
//     which a JVM cannot count the same way, so a bound here could refuse a valid thought;
//   - a v2 field is declared after the v1 fields of its shape, in section 7's order, which is the
//     order these samples pin.

/** Protocol v2's new client events (design section 7). */
internal val V2_CLIENT_EVENTS =
    setOf("push_unregister", "request_status", "mutations_pull", "history_search", "models_pull")

/** Protocol v2's new server events (design section 7). */
internal val V2_SERVER_EVENTS =
    setOf(
        "thought",
        "thought_done",
        "turn_done",
        "models",
        "model_changed",
        "transcript",
        "request_status_page",
        "mutations_page",
        "search_results",
    )

/** The fields protocol v2 adds to a protocol v1 definition of protocol.schema.json, by definition. */
internal val V2_FIELDS: Map<String, Set<String>> =
    mapOf(
        "hello" to setOf("last_mutation_seq"),
        "msg" to setOf("retry_of"),
        "history_pull" to setOf("before_seq"),
        "push_register" to setOf("platform", "token"),
        "pair_request" to setOf("platform", "attestation"),
        "hello_ack" to setOf("instance", "active_turns", "pending_approvals", "mutation_head_seq"),
        "caps" to
            setOf(
                "thoughts",
                "turn_done",
                "transcripts",
                "models",
                "search",
                "approval_replay",
                "reply",
                "push",
                "model_state",
            ),
        "text_delta" to setOf("replace"),
        "tool_event" to setOf("status"),
        "text_done" to setOf("route"),
        "history_page" to setOf("prev_before_seq"),
        "pair_approved" to setOf("push_salt", "push"),
    )

/** The values protocol v2 adds to a closed set of protocol.schema.json, by `definition.field`. */
internal val V2_VALUES: Map<String, Set<String>> =
    mapOf("pair_denied.reason" to setOf("attestation", "attestation_unavailable", "platform_unsupported"))

/** The open strings of protocol.schema.json that protocol v2 closes into a set, by `definition.field`. */
internal val V2_CLOSED_SETS: Set<String> = setOf("attach_begin.kind", "row.kind", "historyMessage.kind")

private const val SALT = "UaPjTwSGrUmgzbg5wGP0rXHjANajuYcNJzMk7j7qYAo="
private const val SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

// The samples the shape tests and the bounds below are written from.

private const val MSG_V2 =
    """{"v":2,"t":"msg","seq":2,"client_msg_id":"client-9","profile_id":"main","text":"Run again",""" +
        """"attach_ids":[],"retry_of":"client-8"}"""

private const val HISTORY_PULL_V2 =
    """{"v":2,"t":"history_pull","seq":4,"profile_id":"main","limit":50,"before_seq":120}"""

private const val PUSH_REGISTER_V2 =
    """{"v":2,"t":"push_register","seq":5,"platform":"android","token":"fcm-token-1"}"""

private const val REQUEST_STATUS_V2 =
    """{"v":2,"t":"request_status","seq":7,"client_msg_ids":["client-1","client-2"]}"""

private const val MUTATIONS_PULL_V2 =
    """{"v":2,"t":"mutations_pull","seq":8,"profile_id":"main","after_mutation_seq":41,"limit":100}"""

private const val HISTORY_SEARCH_V2 =
    """{"v":2,"t":"history_search","seq":9,"profile_id":"main","query":"dentist friday","limit":20,""" +
        """"before_seq":88}"""

private const val HELLO_ACK_V2 =
    """{"v":2,"t":"hello_ack","seq":1,"session_id":"mobile-session-2","min_version":2,"max_version":2,""" +
        """"profiles":[{"id":"main","name":"Fermix"}],""" +
        """"candidates":[{"host":"100.101.102.103","interface":"utun4","scope":"tailnet"}],""" +
        """"history_head_seq":40,"read_up_to_seq":38,"caps":{"commands":[{"name":"model","aliases":[],""" +
        """"description":"Switch this chat's model"}],"media":true,"streaming":true,""" +
        """"max_media_bytes":20971520,"thoughts":true,"turn_done":true,"transcripts":true,""" +
        """"models":true,"search":true,"approval_replay":true,"reply":false,"push":["apns","fcm"],""" +
        """"model_state":{"default":{"provider":"anthropic","model":"claude-opus-5-5",""" +
        """"label":"Claude Opus 5.5"},"override":{"provider":"openai","model":"gpt-6.1",""" +
        """"label":"GPT-6.1"}}},"instance":{"label":"Dev","host":"owner-mac","profile":"dev"},""" +
        """"active_turns":[{"turn_id":"turn-client-7","in_reply_to":"client-7"}],""" +
        """"pending_approvals":["sandbox-1"],"mutation_head_seq":12}"""

private const val TEXT_DONE_V2 =
    """{"v":2,"t":"text_done","seq":12,"turn_id":"turn-client-1","server_seq":14,"text":"Hello",""" +
        """"route":{"provider":"openai","model":"gpt-6.1"}}"""

private const val TOOL_EVENT_V2 =
    """{"v":2,"t":"tool_event","seq":11,"turn_id":"turn-client-1","tool":"web_search","phase":"stop",""" +
        """"status":"ok"}"""

private const val THOUGHT_V2 =
    """{"v":2,"t":"thought","seq":10,"turn_id":"turn-client-1","in_reply_to":"client-1",""" +
        """"text":"Searching the web"}"""

private const val THOUGHT_DONE_V2 =
    """{"v":2,"t":"thought_done","seq":13,"turn_id":"turn-client-1"}"""

private const val TURN_DONE_V2 =
    """{"v":2,"t":"turn_done","seq":14,"turn_id":"turn-client-1"}"""

private const val MODELS_V2 =
    """{"v":2,"t":"models","seq":15,"entries":[{"provider":"anthropic","model":"claude-opus-5-5",""" +
        """"label":"Claude Opus 5.5","trait":"deep","streams":false,"active":true,"default":true},""" +
        """{"provider":"ollama","listing_unavailable":true}],"next":true}"""

private const val MODEL_CHANGED_V2 =
    """{"v":2,"t":"model_changed","seq":16,"profile_id":"main","provider":"openai","model":"gpt-6.1",""" +
        """"label":"GPT-6.1","source":"override","note":"Computer History is off for this model"}"""

private const val TRANSCRIPT_V2 =
    """{"v":2,"t":"transcript","seq":17,"client_msg_id":"client-5","text":"Book the dentist for Friday"}"""

private const val REQUEST_STATUS_PAGE_V2 =
    """{"v":2,"t":"request_status_page","seq":18,"requests":[{"client_msg_id":"client-1",""" +
        """"status":"completed",""" +
        """"turn_id":"turn-client-1","result_server_seq":14},{"client_msg_id":"client-2","status":"failed",""" +
        """"error":"request_failed"}]}"""

private const val MUTATIONS_PAGE_V2 =
    """{"v":2,"t":"mutations_page","seq":19,"rows":[{"server_seq":17,"mutation_seq":42,""" +
        """"content":"Book the dentist for Friday"},{"server_seq":13,"mutation_seq":43,""" +
        """"metadata":{"reaction":{"emoji":"👍","ts":"2026-09-27T09:02:00Z"}}}],"next":43}"""

private const val SEARCH_RESULTS_V2 =
    """{"v":2,"t":"search_results","seq":20,"profile_id":"main","query":"dentist","hits":[{"server_seq":88,""" +
        """"role":"user","ts":"2026-09-24T09:15:00Z","excerpt":"book the dentist for Friday",""" +
        """"ranges":[{"start":9,"length":7}]}],"next_before_seq":88}"""

private const val HISTORY_PAGE_V2 =
    """{"v":2,"t":"history_page","seq":21,"profile_id":"main","messages":[{"server_seq":40,"role":"system",""" +
        """"content":"Switched to GPT-6.1","ts":"2026-09-27T09:03:00.000000Z","media_refs":[],""" +
        """"kind":"system"}],""" +
        """"next_after_seq":40,"history_head_seq":40,"prev_before_seq":40}"""

private const val HELLO_V2 =
    """{"v":2,"t":"hello","seq":1,"device_id":"device-1","app_version":"0.1.0","last_server_seq":0,""" +
        """"protocol_v":2,"last_mutation_seq":41}"""

private const val PAIR_REQUEST_V2 =
    """{"v":2,"t":"pair_request","seq":1,"device_name":"Owner's phone","model":"Google Pixel 9 Pro",""" +
        """"app_version":"0.1.0","platform":"android","attestation":{"kind":"android_keymint",""" +
        """"cert_lengths":[3,2]}}"""

/** The DER chain [PAIR_REQUEST_V2]'s cert_lengths split, as its raw tail. */
private val CHAIN = ByteArray(5) { it.toByte() }

private const val PAIR_APPROVED_V2 =
    """{"v":2,"t":"pair_approved","seq":1,"device_id":"device-2","candidates":[{"host":"192.168.1.8",""" +
        """"interface":"en0","scope":"lan"}],"profiles":[{"id":"main","name":"Fermix"}],""" +
        """"push_salt":"$SALT","push":["fcm"]}"""

/**
 * A field a protocol v2 shape requires, removed: the sample it is removed from, its path, the field
 * the refusal names, and the raw tail the sample's frame carries.
 */
private class V2Required(
    val sample: String,
    val path: String,
    val field: String = path,
    val raw: ByteArray = ByteArray(0),
) {
    override fun toString() = "${sample.substringAfter("\"t\":\"").substringBefore('"')} lacks $path"
}

// The fields the shapes protocol v2 adds or changes require, each by its path: the provisional
// required sets, which D1's schema settles. The fields of protocol v1 shapes are SchemaTest's.

private val V2_CLIENT_REQUIRED =
    listOf(
        V2Required(HELLO_V2, "last_mutation_seq"),
        V2Required(PUSH_REGISTER_V2, "platform"),
        V2Required(PUSH_REGISTER_V2, "token"),
        V2Required(HISTORY_PULL_V2, "before_seq", "after_seq"),
        V2Required(REQUEST_STATUS_V2, "client_msg_ids"),
    ) +
        listOf("platform", "attestation", "attestation.kind", "attestation.cert_lengths").map {
            V2Required(PAIR_REQUEST_V2, it, raw = CHAIN)
        } +
        listOf("profile_id", "after_mutation_seq", "limit").map { V2Required(MUTATIONS_PULL_V2, it) } +
        listOf("profile_id", "query", "limit").map { V2Required(HISTORY_SEARCH_V2, it) }

private val HELLO_ACK_V2_REQUIRED =
    listOf("instance.label", "instance.host", "instance.profile", "active_turns[0].turn_id") +
        listOf("caps.model_state.default") +
        listOf("default", "override").flatMap { side ->
            listOf("provider", "model", "label").map { "caps.model_state.$side.$it" }
        }

private val MODELS_V2_REQUIRED =
    listOf("entries", "entries[1].provider") +
        listOf("provider", "model", "label", "streams", "active", "default").map { "entries[0].$it" }

private val SEARCH_RESULTS_V2_REQUIRED =
    listOf("profile_id", "query", "hits", "hits[0].ranges[0].start", "hits[0].ranges[0].length") +
        listOf("server_seq", "role", "ts", "excerpt", "ranges").map { "hits[0].$it" }

private val V2_SERVER_REQUIRED =
    HELLO_ACK_V2_REQUIRED.map { V2Required(HELLO_ACK_V2, it) } +
        listOf(V2Required(PAIR_APPROVED_V2, "push_salt")) +
        listOf("route.provider", "route.model").map { V2Required(TEXT_DONE_V2, it) } +
        listOf("turn_id", "in_reply_to", "text").map { V2Required(THOUGHT_V2, it) } +
        listOf(V2Required(THOUGHT_DONE_V2, "turn_id"), V2Required(TURN_DONE_V2, "turn_id")) +
        MODELS_V2_REQUIRED.map { V2Required(MODELS_V2, it) } +
        listOf("profile_id", "provider", "model", "label", "source").map { V2Required(MODEL_CHANGED_V2, it) } +
        listOf("client_msg_id", "text").map { V2Required(TRANSCRIPT_V2, it) } +
        listOf("requests", "requests[0].client_msg_id", "requests[0].status").map {
            V2Required(REQUEST_STATUS_PAGE_V2, it)
        } +
        listOf("rows", "rows[0].server_seq", "rows[0].mutation_seq").map { V2Required(MUTATIONS_PAGE_V2, it) } +
        SEARCH_RESULTS_V2_REQUIRED.map { V2Required(SEARCH_RESULTS_V2, it) }

/**
 * A protocol v2 bound, broken: the sample it is planted in, the path it is planted at, a JSON value
 * past it, and the field the refusal names.
 */
private class V2Bound(
    val sample: String,
    val path: String,
    val value: String,
    val field: String = path,
) {
    override fun toString() = "${sample.substringAfter("\"t\":\"").substringBefore('"')} $path = ${value.take(40)}"
}

private const val EMPTY = "\"\""

/** The bounds of the client events protocol v2 adds or changes, each a rule of Rules.kt. */
private val V2_CLIENT_BOUNDS =
    listOf(
        V2Bound(MSG_V2, "retry_of", EMPTY),
        V2Bound(HISTORY_PULL_V2, "before_seq", "0"),
        V2Bound(PUSH_REGISTER_V2, "token", EMPTY),
        V2Bound(REQUEST_STATUS_V2, "client_msg_ids", "[]"),
        V2Bound(REQUEST_STATUS_V2, "client_msg_ids[0]", EMPTY),
        V2Bound(MUTATIONS_PULL_V2, "profile_id", EMPTY),
        V2Bound(MUTATIONS_PULL_V2, "limit", "0"),
        V2Bound(MUTATIONS_PULL_V2, "limit", "201"),
        V2Bound(HISTORY_SEARCH_V2, "profile_id", EMPTY),
        V2Bound(HISTORY_SEARCH_V2, "query", EMPTY),
        V2Bound(HISTORY_SEARCH_V2, "limit", "0"),
        V2Bound(HISTORY_SEARCH_V2, "before_seq", "0"),
    )

private val HELLO_ACK_V2_TEXTS =
    listOf("instance.label", "instance.host", "instance.profile", "active_turns[0].turn_id") +
        listOf("active_turns[0].in_reply_to", "pending_approvals[0]") +
        listOf("default", "override").flatMap { side ->
            listOf("provider", "model", "label").map { "caps.model_state.$side.$it" }
        }

/** The bounds of the server events protocol v2 adds or changes, each a rule of Rules.kt. */
private val V2_SERVER_BOUNDS =
    HELLO_ACK_V2_TEXTS.map { V2Bound(HELLO_ACK_V2, it, EMPTY) } +
        listOf("route.provider", "route.model").map { V2Bound(TEXT_DONE_V2, it, EMPTY) } +
        listOf(
            V2Bound(TOOL_EVENT_V2, "status", EMPTY),
            V2Bound(HISTORY_PAGE_V2, "prev_before_seq", "0"),
            V2Bound(THOUGHT_V2, "turn_id", EMPTY),
            V2Bound(THOUGHT_V2, "in_reply_to", EMPTY),
            V2Bound(THOUGHT_DONE_V2, "turn_id", EMPTY),
            V2Bound(TURN_DONE_V2, "turn_id", EMPTY),
            V2Bound(MODELS_V2, "entries[1].listing_unavailable", "false"),
            V2Bound(TRANSCRIPT_V2, "client_msg_id", EMPTY),
        ) +
        listOf("provider", "model", "label", "trait").map { V2Bound(MODELS_V2, "entries[0].$it", EMPTY) } +
        listOf("profile_id", "provider", "model", "label", "note").map { V2Bound(MODEL_CHANGED_V2, it, EMPTY) } +
        listOf(
            V2Bound(
                REQUEST_STATUS_PAGE_V2,
                "requests",
                List(33) { """{"client_msg_id":"client-$it","status":"accepted"}""" }.joinToString(",", "[", "]"),
            ),
            V2Bound(REQUEST_STATUS_PAGE_V2, "requests[0].client_msg_id", EMPTY),
            V2Bound(REQUEST_STATUS_PAGE_V2, "requests[0].turn_id", EMPTY),
            V2Bound(REQUEST_STATUS_PAGE_V2, "requests[0].result_server_seq", "0"),
            V2Bound(REQUEST_STATUS_PAGE_V2, "requests[1].error", EMPTY),
            V2Bound(MUTATIONS_PAGE_V2, "rows[0].server_seq", "0"),
            V2Bound(MUTATIONS_PAGE_V2, "rows[0].mutation_seq", "0"),
            V2Bound(
                MUTATIONS_PAGE_V2,
                "rows[0].media_refs",
                """[{"ref":"r","kind":"image","mime":"image/jpeg","size_bytes":1,"sha256":"!"}]""",
                "rows[0].media_refs[0].sha256",
            ),
            V2Bound(SEARCH_RESULTS_V2, "profile_id", EMPTY),
            V2Bound(SEARCH_RESULTS_V2, "hits[0].server_seq", "0"),
            V2Bound(SEARCH_RESULTS_V2, "hits[0].role", EMPTY),
            V2Bound(SEARCH_RESULTS_V2, "hits[0].ts", "\"!\""),
            V2Bound(SEARCH_RESULTS_V2, "hits[0].ranges[0].start", "-1"),
            V2Bound(SEARCH_RESULTS_V2, "hits[0].ranges[0].length", "0"),
            // The excerpt is 27 characters: a range from 21 of 7 ends one past it.
            V2Bound(SEARCH_RESULTS_V2, "hits[0].ranges[0].start", "21", "hits[0].ranges[0]"),
            V2Bound(SEARCH_RESULTS_V2, "hits[0].ranges[0].start", "2147483647", "hits[0].ranges[0]"),
            V2Bound(SEARCH_RESULTS_V2, "next_before_seq", "0"),
        )

class ProvisionalV2Test {
    /** Decodes [sample] as it stands, then refuses it with [bound]'s value planted, under its field. */
    private fun refuses(
        bound: V2Bound,
        decode: (JsonObject) -> Unit,
    ) {
        val sample = Json.parseToJsonElement(bound.sample).jsonObject
        decode(sample)
        val plant = planted(sample, bound.path, Json.parseToJsonElement(bound.value))
        val refusal = assertThrows<ProtocolException.InvalidField>("$bound") { decode(plant) }
        assertEquals(bound.field, refusal.field, "$bound")
    }

    @TestFactory
    fun `every protocol v2 bound is refused under its path`(): List<DynamicTest> =
        V2_CLIENT_BOUNDS.map { bound ->
            dynamicTest("client $bound") { refuses(bound) { header -> decodeClientEvent(frameOf("$header")) } }
        } +
            V2_SERVER_BOUNDS.map { bound ->
                dynamicTest("server $bound") {
                    refuses(bound) { header ->
                        decodeLogicalServerEvent(V2, 1uL, "${JsonObject(header - "v" - "seq")}".encodeToByteArray())
                    }
                }
            }

    private fun encodesTo(
        sample: String,
        event: ClientEvent,
        seq: ULong,
        raw: ByteArray = ByteArray(0),
    ) {
        val frame = encodeClientEvent(V2, seq, event, raw)
        assertEquals(sample, headerOf(frame))
        assertArrayEquals(raw, Frame.decode(frame).raw)
        assertEquals(event, decodeClientEvent(frame).event)
    }

    private fun decodesTo(
        sample: String,
        event: ServerEvent.Known,
    ) {
        val decoded = decodeServerEvent(frameOf(sample))
        assertEquals(V2, decoded.v)
        assertEquals(event, decoded.event)
        assertEquals(sample, headerOf(encodeServerEvent(V2, decoded.seq, event)))
    }

    // Client additions.

    @Test
    fun `hello carries protocol_v 2 and the mutation cursor, which v2 requires and v1 lacks`() {
        val hello = ClientEvent.Hello("device-1", "0.1.0", 0uL, V2, lastMutationSeq = 41uL)
        encodesTo(HELLO_V2, hello, 1uL)
        val missing =
            assertThrows<ProtocolException.MissingField> {
                encodeClientEvent(
                    V2,
                    1uL,
                    hello.copy(lastMutationSeq = null),
                )
            }
        assertEquals("last_mutation_seq", missing.field)
        val stray =
            assertThrows<ProtocolException.FieldNotInVersion> { encodeClientEvent(V1, 1uL, hello.copy(protocolV = V1)) }
        assertEquals("last_mutation_seq", stray.field)
    }

    @Test
    fun `msg carries retry_of`() {
        encodesTo(
            MSG_V2,
            ClientEvent.Msg("client-9", "main", "Run again", emptyList(), retryOf = "client-8"),
            2uL,
        )
    }

    @Test
    fun `attach_begin takes the video kind`() {
        encodesTo(
            """{"v":2,"t":"attach_begin","seq":3,"attach_id":"attach-2","kind":"video","mime":"video/mp4",""" +
                """"size_bytes":1048576,"sha256":"$SHA"}""",
            ClientEvent.AttachBegin("attach-2", AttachKind.VIDEO, "video/mp4", 1_048_576, sha256 = SHA),
            3uL,
        )
    }

    @Test
    fun `history_pull pages backward with before_seq, and takes exactly one cursor`() {
        encodesTo(
            HISTORY_PULL_V2,
            ClientEvent.HistoryPull("main", limit = 50, beforeSeq = 120uL),
            4uL,
        )
        val both = ClientEvent.HistoryPull("main", afterSeq = 7uL, limit = 50, beforeSeq = 120uL)
        assertEquals(
            "before_seq",
            assertThrows<ProtocolException.InvalidField> { encodeClientEvent(V2, 4uL, both) }.field,
        )
        val none = ClientEvent.HistoryPull("main", limit = 50)
        assertEquals(
            "after_seq",
            assertThrows<ProtocolException.MissingField> { encodeClientEvent(V2, 4uL, none) }.field,
        )
        val backwardInV1 = ClientEvent.HistoryPull("main", afterSeq = 0uL, limit = 50, beforeSeq = 120uL)
        assertThrows<ProtocolException.FieldNotInVersion> { encodeClientEvent(V1, 4uL, backwardInV1) }
    }

    @Test
    fun `push_register is platform-neutral, with environment for ios alone`() {
        encodesTo(
            PUSH_REGISTER_V2,
            ClientEvent.PushRegister(platform = Platform.ANDROID, token = "fcm-token-1"),
            5uL,
        )
        encodesTo(
            """{"v":2,"t":"push_register","seq":5,"platform":"ios","token":"apns-token-1",""" +
                """"environment":"production"}""",
            ClientEvent.PushRegister(
                platform = Platform.IOS,
                token = "apns-token-1",
                environment = PushEnvironment.PRODUCTION,
            ),
            5uL,
        )
        val android =
            ClientEvent.PushRegister(
                platform = Platform.ANDROID,
                token = "t",
                environment = PushEnvironment.PRODUCTION,
            )
        assertEquals(
            "environment",
            assertThrows<ProtocolException.InvalidField> { encodeClientEvent(V2, 5uL, android) }.field,
        )
        val apns = ClientEvent.PushRegister(apnsToken = "0123", platform = Platform.IOS, token = "0123")
        assertEquals(
            "apns_token",
            assertThrows<ProtocolException.FieldNotInVersion> { encodeClientEvent(V2, 5uL, apns) }.field,
        )
        val noToken = ClientEvent.PushRegister(platform = Platform.ANDROID)
        assertEquals(
            "token",
            assertThrows<ProtocolException.MissingField> { encodeClientEvent(V2, 5uL, noToken) }.field,
        )
    }

    @Test
    fun `push_unregister is an empty event, and protocol v1 has none`() {
        encodesTo("""{"v":2,"t":"push_unregister","seq":6}""", ClientEvent.PushUnregister, 6uL)
        assertThrows<ProtocolException.EventNotInVersion> { encodeClientEvent(V1, 6uL, ClientEvent.PushUnregister) }
        assertThrows<ProtocolException.EventNotInVersion> {
            decodeClientEvent(frameOf("""{"v":1,"t":"push_unregister","seq":6}"""))
        }
    }

    @Test
    fun `pair_request carries its platform and attestation, and the DER chain as its raw tail`() {
        val request =
            ClientEvent.PairRequest(
                "Owner's phone",
                "Google Pixel 9 Pro",
                "0.1.0",
                Platform.ANDROID,
                Attestation(AttestationKind.ANDROID_KEYMINT, listOf(3, 2)),
            )
        encodesTo(PAIR_REQUEST_V2, request, 1uL, CHAIN)
        assertThrows<ProtocolException.MissingRaw> { encodeClientEvent(V2, 1uL, request) }
        val missing =
            assertThrows<ProtocolException.MissingField> {
                encodeClientEvent(
                    V2,
                    1uL,
                    request.copy(attestation = null),
                    CHAIN,
                )
            }
        assertEquals("attestation", missing.field)
    }

    @Test
    fun `a pair_request chain is at most six certificates and 16 KiB, and its lengths sum to the tail`() {
        val request = { lengths: List<Int> ->
            ClientEvent.PairRequest(
                "phone",
                "Pixel",
                "0.1.0",
                Platform.ANDROID,
                Attestation(AttestationKind.ANDROID_KEYMINT, lengths),
            )
        }
        val refused = { lengths: List<Int>, tail: Int ->
            assertThrows<ProtocolException.InvalidField> {
                encodeClientEvent(V2, 1uL, request(lengths), ByteArray(tail))
            }.field
        }
        assertEquals("attestation.cert_lengths", refused(listOf(3, 3), 5))
        assertEquals("attestation.cert_lengths", refused(List(7) { 1 }, 7))
        assertEquals("attestation.cert_lengths", refused(emptyList(), 1))
        assertEquals("attestation", refused(listOf(16_385), 16_385))
        assertEquals("attestation.cert_lengths[0]", refused(listOf(0, 3), 3))
        encodeClientEvent(V2, 1uL, request(listOf(16_384)), ByteArray(16_384))
        encodeClientEvent(V2, 1uL, request(List(6) { 1 }), ByteArray(6))
    }

    @Test
    fun `request_status asks for at most 32 requests`() {
        encodesTo(
            REQUEST_STATUS_V2,
            ClientEvent.RequestStatus(listOf("client-1", "client-2")),
            7uL,
        )
        val many = ClientEvent.RequestStatus(List(33) { "client-$it" })
        assertEquals(
            "client_msg_ids",
            assertThrows<ProtocolException.InvalidField> { encodeClientEvent(V2, 7uL, many) }.field,
        )
        encodeClientEvent(V2, 7uL, ClientEvent.RequestStatus(List(32) { "client-$it" }))
    }

    @Test
    fun `mutations_pull reads from a mutation cursor`() {
        encodesTo(
            MUTATIONS_PULL_V2,
            ClientEvent.MutationsPull("main", 41uL, 100),
            8uL,
        )
    }

    @Test
    fun `history_search takes a query of at most 256 characters and a limit of 1 to 50`() {
        encodesTo(
            HISTORY_SEARCH_V2,
            ClientEvent.HistorySearch("main", "dentist friday", 20, 88uL),
            9uL,
        )
        val wide = ClientEvent.HistorySearch("main", "dentist", 51)
        assertEquals("limit", assertThrows<ProtocolException.InvalidField> { encodeClientEvent(V2, 9uL, wide) }.field)
        val long = ClientEvent.HistorySearch("main", "😀".repeat(257), 20)
        assertEquals("query", assertThrows<ProtocolException.InvalidField> { encodeClientEvent(V2, 9uL, long) }.field)
        encodeClientEvent(V2, 9uL, ClientEvent.HistorySearch("main", "😀".repeat(256), 20))
    }

    @Test
    fun `models_pull is an empty event`() {
        encodesTo("""{"v":2,"t":"models_pull","seq":10}""", ClientEvent.ModelsPull, 10uL)
    }

    // The pairing link.

    @Test
    fun `a link version 2 requires and reads the profile, a label like the name`() {
        val fixture = Json.parseToJsonElement(vendored("fixtures/pairing_links.jsonl").trim()).jsonObject
        val v2 =
            fixture
                .getValue("uri")
                .jsonPrimitive.content
                .replace("?v=1&", "?v=2&")
        check("?v=2&" in v2) { "the vendored link is no longer version 1" }
        assertEquals("profile", assertThrows<ProtocolException.MissingParameter> { PairingLink.parse(v2) }.name)
        val parsed = PairingLink.parse("$v2&profile=dev+box")
        assertEquals(2, parsed.version)
        assertEquals("dev box", parsed.profile)
        listOf("&profile=a%01b", "&profile=+", "&profile=%ZZ").forEach { profile ->
            val refusal =
                assertThrows<ProtocolException.MalformedParameter>(profile) { PairingLink.parse(v2 + profile) }
            assertEquals("profile", refusal.name)
        }
        val repeated =
            assertThrows<ProtocolException.RepeatedParameter> { PairingLink.parse("$v2&profile=a&profile=b") }
        assertEquals("profile", repeated.name)
    }

    // Server additions.

    /** Every caps flag protocol v2 adds, each set. */
    private val v2Caps =
        Caps(
            commands = listOf(CommandDescriptor("model", emptyList(), "Switch this chat's model")),
            media = true,
            streaming = true,
            maxMediaBytes = 20_971_520,
            thoughts = true,
            turnDone = true,
            transcripts = true,
            models = true,
            search = true,
            approvalReplay = true,
            reply = false,
            push = listOf(PushPlatform.APNS, PushPlatform.FCM),
            modelState =
                ModelState(
                    ModelRef("anthropic", "claude-opus-5-5", "Claude Opus 5.5"),
                    ModelRef("openai", "gpt-6.1", "GPT-6.1"),
                ),
        )

    @Test
    fun `hello_ack carries the instance, every caps flag, the turns and approvals in flight, and the mutation head`() {
        val sample = HELLO_ACK_V2
        val helloAck =
            ServerEvent.HelloAck(
                "mobile-session-2",
                V2,
                V2,
                listOf(Profile("main", "Fermix")),
                listOf(Candidate("100.101.102.103", "utun4", CandidateScope.TAILNET)),
                40uL,
                38uL,
                v2Caps,
                Instance("Dev", "owner-mac", "dev"),
                listOf(ActiveTurn("turn-client-7", "client-7")),
                listOf("sandbox-1"),
                12uL,
            )
        decodesTo(sample, helloAck)
        val five = helloAck.copy(pendingApprovals = List(5) { "sandbox-$it" })
        val refusal = assertThrows<ProtocolException.InvalidField> { encodeServerEvent(V2, 1uL, five) }
        assertEquals("pending_approvals", refusal.field)
    }

    @Test
    fun `pair_approved requires the push salt in v2 and names the push services`() {
        val approved =
            ServerEvent.PairApproved(
                "device-2",
                listOf(Candidate("192.168.1.8", "en0", CandidateScope.LAN)),
                listOf(Profile("main", "Fermix")),
                SALT,
                listOf(PushPlatform.FCM),
            )
        decodesTo(PAIR_APPROVED_V2, approved)
        val missing =
            assertThrows<ProtocolException.MissingField> { encodeServerEvent(V2, 1uL, approved.copy(pushSalt = null)) }
        assertEquals("push_salt", missing.field)
        val short = approved.copy(pushSalt = "c2FsdA==")
        assertEquals(
            "push_salt",
            assertThrows<ProtocolException.InvalidField> { encodeServerEvent(V2, 1uL, short) }.field,
        )
    }

    @Test
    fun `pair_denied names the attestation and platform refusals`() {
        listOf(
            "attestation" to PairDeniedReason.ATTESTATION,
            "attestation_unavailable" to PairDeniedReason.ATTESTATION_UNAVAILABLE,
            "platform_unsupported" to PairDeniedReason.PLATFORM_UNSUPPORTED,
        ).forEach { (word, reason) ->
            decodesTo("""{"v":2,"t":"pair_denied","seq":1,"reason":"$word"}""", ServerEvent.PairDenied(reason))
        }
    }

    @Test
    fun `text_delta marks a whole snapshot with replace`() {
        decodesTo(
            """{"v":2,"t":"text_delta","seq":9,"turn_id":"turn-client-1","text":"Hello, wor","replace":true}""",
            ServerEvent.TextDelta("turn-client-1", "Hello, wor", replace = true),
        )
    }

    @Test
    fun `text_done names the route that produced it`() {
        decodesTo(
            TEXT_DONE_V2,
            ServerEvent.TextDone("turn-client-1", 14uL, "Hello", route = Route("openai", "gpt-6.1")),
        )
    }

    @Test
    fun `tool_event carries a status and no detail in v2`() {
        decodesTo(
            TOOL_EVENT_V2,
            ServerEvent.ToolEvent("turn-client-1", "web_search", ToolPhase.STOP, status = "ok"),
        )
        val withDetail =
            """{"v":2,"t":"tool_event","seq":11,"turn_id":"t","tool":"web_search","phase":"stop","detail":"x"}"""
        assertEquals(
            ServerEvent.ToolEvent("t", "web_search", ToolPhase.STOP),
            decodeServerEvent(frameOf(withDetail)).event,
        )
        val detail = ServerEvent.ToolEvent("t", "web_search", ToolPhase.STOP, detail = "x")
        assertEquals(
            "detail",
            assertThrows<ProtocolException.FieldNotInVersion> { encodeServerEvent(V2, 11uL, detail) }.field,
        )
    }

    @Test
    fun `thought carries the rolling headings of a turn`() {
        decodesTo(
            THOUGHT_V2,
            ServerEvent.Thought("turn-client-1", "client-1", "Searching the web"),
        )
    }

    @Test
    fun `thought_done and turn_done name the turn`() {
        decodesTo(
            THOUGHT_DONE_V2,
            ServerEvent.ThoughtDone("turn-client-1"),
        )
        decodesTo(
            TURN_DONE_V2,
            ServerEvent.TurnDone("turn-client-1"),
        )
    }

    @Test
    fun `models pages the model list, with a provider whose listing failed`() {
        decodesTo(
            MODELS_V2,
            ServerEvent.Models(
                listOf(
                    ModelEntry(
                        "anthropic",
                        "claude-opus-5-5",
                        "Claude Opus 5.5",
                        "deep",
                        streams = false,
                        active = true,
                        isDefault = true,
                    ),
                    ModelEntry("ollama", listingUnavailable = true),
                ),
                next = true,
            ),
        )
        val both = ServerEvent.Models(listOf(ModelEntry("ollama", model = "llama", listingUnavailable = true)))
        assertEquals(
            "entries[0].listing_unavailable",
            assertThrows<ProtocolException.InvalidField> {
                encodeServerEvent(V2, 15uL, both)
            }.field,
        )
        val partial =
            ServerEvent.Models(
                listOf(ModelEntry("openai", "gpt-6.1", "GPT-6.1", active = true, isDefault = false)),
            )
        assertEquals(
            "entries[0].streams",
            assertThrows<ProtocolException.MissingField> {
                encodeServerEvent(V2, 15uL, partial)
            }.field,
        )
        val listed = ModelEntry("openai", "gpt-6.1", "GPT-6.1", streams = true, active = true, isDefault = false)
        listOf(listed.copy(model = null) to "entries[0].model", listed.copy(label = null) to "entries[0].label")
            .forEach { (entry, field) ->
                val refusal =
                    assertThrows<ProtocolException.MissingField>(field) {
                        encodeServerEvent(V2, 15uL, ServerEvent.Models(listOf(entry)))
                    }
                assertEquals(field, refusal.field)
            }
        encodeServerEvent(V2, 15uL, ServerEvent.Models(listOf(listed)))
    }

    @Test
    fun `model_changed names the new model and its source`() {
        decodesTo(
            MODEL_CHANGED_V2,
            ServerEvent.ModelChanged(
                "main",
                "openai",
                "gpt-6.1",
                "GPT-6.1",
                ModelSource.OVERRIDE,
                "Computer History is off for this model",
            ),
        )
    }

    @Test
    fun `transcript carries a voice note's text`() {
        decodesTo(
            TRANSCRIPT_V2,
            ServerEvent.Transcript("client-5", "Book the dentist for Friday"),
        )
    }

    @Test
    fun `request_status_page says how each request stands`() {
        decodesTo(
            REQUEST_STATUS_PAGE_V2,
            ServerEvent.RequestStatusPage(
                listOf(
                    RequestOutcome("client-1", RequestState.COMPLETED, "turn-client-1", 14uL),
                    RequestOutcome("client-2", RequestState.FAILED, error = "request_failed"),
                ),
            ),
        )
    }

    @Test
    fun `mutations_page carries rows changed in place and the next cursor`() {
        val reaction =
            buildJsonObject {
                put(
                    "reaction",
                    buildJsonObject {
                        put("emoji", "👍")
                        put("ts", "2026-09-27T09:02:00Z")
                    },
                )
            }
        decodesTo(
            MUTATIONS_PAGE_V2,
            ServerEvent.MutationsPage(
                listOf(
                    MutationRow(17uL, 42uL, content = "Book the dentist for Friday"),
                    MutationRow(13uL, 43uL, metadata = reaction),
                ),
                next = 43uL,
            ),
        )
    }

    @Test
    fun `search_results is the companion socket's shape`() {
        decodesTo(
            SEARCH_RESULTS_V2,
            ServerEvent.SearchResults(
                "main",
                "dentist",
                listOf(
                    SearchHit(
                        88uL,
                        "user",
                        "2026-09-24T09:15:00Z",
                        "book the dentist for Friday",
                        listOf(MatchRange(9, 7)),
                    ),
                ),
                88uL,
            ),
        )
        // A range is in Unicode scalar values: "😀 dentist" is 9 of them and 10 UTF-16 units.
        val emoji = SEARCH_RESULTS_V2.replace("book the dentist for Friday", "😀 dentist")
        decodeServerEvent(frameOf(emoji.replace("\"start\":9", "\"start\":2")))
        val past = emoji.replace("\"start\":9", "\"start\":3")
        val refusal = assertThrows<ProtocolException.InvalidField> { decodeServerEvent(frameOf(past)) }
        assertEquals("hits[0].ranges[0]", refusal.field)
    }

    @Test
    fun `history_page pages backward and carries system rows`() {
        decodesTo(
            HISTORY_PAGE_V2,
            ServerEvent.HistoryPage(
                "main",
                listOf(
                    HistoryMessage(
                        40uL,
                        "system",
                        "Switched to GPT-6.1",
                        "2026-09-27T09:03:00.000000Z",
                        emptyList(),
                        MessageKind.SYSTEM,
                    ),
                ),
                40uL,
                40uL,
                40uL,
            ),
        )
    }

    @Test
    fun `error names mutations_gone`() {
        decodesTo(
            """{"v":2,"t":"error","seq":22,"code":"mutations_gone",""" +
                """"message":"the mutation cursor 3 is older than the feed"}""",
            ServerEvent.Error(ServerEvent.Error.MUTATIONS_GONE, "the mutation cursor 3 is older than the feed"),
        )
    }

    @Test
    fun `a v2 server event in a v1 frame is unknown there`() {
        val decoded = decodeServerEvent(frameOf("""{"v":1,"t":"turn_done","seq":14,"turn_id":"turn-client-1"}"""))
        assertEquals(
            ServerEvent.Unknown("turn_done", 14uL, """{"v":1,"t":"turn_done","seq":14,"turn_id":"turn-client-1"}"""),
            decoded.event,
        )
    }
}

/** The required fields of the shapes protocol v2 adds or changes, each planted absent. */
class ProvisionalV2RequiredTest {
    /** Decodes [required]'s sample as it stands, then refuses it without the field, by its path. */
    private fun lacks(
        required: V2Required,
        decode: (JsonObject) -> Unit,
    ) {
        val sample = Json.parseToJsonElement(required.sample).jsonObject
        decode(sample)
        val refusal =
            assertThrows<ProtocolException.MissingField>("$required") { decode(removed(sample, required.path)) }
        assertEquals(required.field, refusal.field, "$required")
    }

    @TestFactory
    fun `every field a protocol v2 shape requires is refused when absent, by its path`(): List<DynamicTest> =
        V2_CLIENT_REQUIRED.map { required ->
            dynamicTest("client $required") {
                lacks(required) { header -> decodeClientEvent(frameOf("$header", required.raw)) }
            }
        } +
            V2_SERVER_REQUIRED.map { required ->
                dynamicTest("server $required") { lacks(required) { header -> decodeServerEvent(frameOf("$header")) } }
            }
}
