package io.tezra.fermix.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val TS = "2026-09-27T09:00:00.000000Z"

/**
 * Each rule of Rules.kt planted as a violation, refused by type and by field, on decode and, for
 * the events the phone builds, on encode as well (protocol.schema.json, PROTOCOL.md).
 */
class ValidationTest {
    private inline fun <reified T : ProtocolException> server(
        header: String,
        raw: ByteArray = ByteArray(0),
    ): T = assertThrows<T>(header) { decodeServerEvent(frameOf(header, raw)) }

    private inline fun <reified T : ProtocolException> client(
        header: String,
        raw: ByteArray = ByteArray(0),
    ): T = assertThrows<T>(header) { decodeClientEvent(frameOf(header, raw)) }

    private inline fun <reified T : ProtocolException> encoding(
        event: ClientEvent,
        raw: ByteArray = ByteArray(0),
    ): T = assertThrows<T>("$event") { encodeClientEvent(1, 1uL, event, raw) }

    private fun row(fields: String) =
        """{"v":1,"t":"row","seq":5,"profile_id":"main","server_seq":13,"role":"user","text":"Hi","ts":"$TS",""" +
            """"media_refs":[]$fields}"""

    private fun helloAck(
        candidates: String = "[]",
        profiles: String = "[]",
    ) = """{"v":1,"t":"hello_ack","seq":1,"session_id":"s","min_version":1,"max_version":1,"profiles":$profiles,""" +
        """"candidates":$candidates,"history_head_seq":0,"read_up_to_seq":0,"caps":{"commands":[],""" +
        """"max_media_bytes":1}}"""

    private fun candidate(
        host: String,
        scope: String = "lan",
    ) = """{"host":"$host","interface":"en0","scope":"$scope"}"""

    @Test
    fun `a required field that is absent is refused by name, nested fields by path`() {
        val msg =
            client<ProtocolException.MissingField>(
                """{"v":1,"t":"msg","seq":2,"client_msg_id":"c","profile_id":"main","attach_ids":[]}""",
            )
        assertEquals("text", msg.field)
        val nested =
            server<ProtocolException.MissingField>(helloAck(candidates = """[{"host":"h","interface":"en0"}]"""))
        assertEquals("candidates[0].scope", nested.field)
        val push =
            client<ProtocolException.MissingField>("""{"v":1,"t":"push_register","seq":13,"apns_token":"0123"}""")
        assertEquals("environment", push.field)
    }

    @Test
    fun `history_pull's limit is 1 to 200`() {
        listOf(0, 201).forEach { limit ->
            assertEquals(
                "limit",
                encoding<ProtocolException.InvalidField>(ClientEvent.HistoryPull("main", 0uL, limit)).field,
            )
            val header = """{"v":1,"t":"history_pull","seq":10,"profile_id":"main","after_seq":0,"limit":$limit}"""
            assertEquals("limit", client<ProtocolException.InvalidField>(header).field)
        }
        encodeClientEvent(1, 1uL, ClientEvent.HistoryPull("main", 0uL, 1))
        encodeClientEvent(1, 1uL, ClientEvent.HistoryPull("main", 0uL, 200))
    }

    @Test
    fun `a command name is lowercase letters, digits and underscores`() {
        listOf("Help", "he lp", "", "help\n", "aide-mémoire").forEach { name ->
            assertEquals(
                "name",
                encoding<ProtocolException.InvalidField>(ClientEvent.Command("c", "main", name)).field,
                name,
            )
        }
        encodeClientEvent(1, 1uL, ClientEvent.Command("c", "main", "skills_review2"))
    }

    @Test
    fun `pair_request text is at most 128 bytes of printable UTF-8`() {
        val texts =
            listOf(
                "é".repeat(64) + "a",
                "Owner\u0007s phone",
                "Owner\u0085s phone",
                "Owner\u007Fs phone",
                "Owner\u001Bs phone",
                "Owner\uD800s phone",
                "   ",
                "",
            )
        val request =
            """{"v":1,"t":"pair_request","seq":1,"device_name":"phone","model":"Pixel","app_version":"0.1.0"}"""
        val base = Json.parseToJsonElement(request).jsonObject
        val cases = listOf("device_name", "model", "app_version").flatMap { field -> texts.map { field to it } }
        cases.forEach { (field, text) ->
            val encoded = encoding<ProtocolException.InvalidField>(pairRequest(field, text))
            assertEquals(field, encoded.field, "$field $text")
            // The printer writes a lone surrogate as it is, which UTF-8 cannot carry, so it goes as an escape.
            val header = escape("${planted(base, field, JsonPrimitive(text))}")
            assertEquals(field, client<ProtocolException.InvalidField>(header).field, "$field $text")
        }
        encodeClientEvent(1, 1uL, ClientEvent.PairRequest("é".repeat(64), "é".repeat(64), "é".repeat(64)))
    }

    /** A protocol v1 pair_request with [text] as its [field] and plain text in the other two. */
    private fun pairRequest(
        field: String,
        text: String,
    ) = ClientEvent.PairRequest(
        if (field == "device_name") text else "phone",
        if (field == "model") text else "Pixel",
        if (field == "app_version") text else "0.1.0",
    )

    @Test
    fun `server_seq is on a duplicate accepted alone`() {
        val first = """{"v":1,"t":"accepted","seq":3,"client_msg_id":"client-1","duplicate":false,"server_seq":9}"""
        assertEquals("server_seq", server<ProtocolException.InvalidField>(first).field)
        decodeServerEvent(frameOf(first.replace("false", "true")))
    }

    @Test
    fun `a header that is not valid UTF-8 is refused before any field is read`() {
        val header =
            """{"v":1,"t":"pair_request","seq":1,"device_name":"X","model":"Pixel","app_version":"0.1.0"}"""
                .encodeToByteArray()
        header[header.indexOf('X'.code.toByte())] = 0xFF.toByte()
        assertThrows<ProtocolException.MalformedHeader> { decodeClientEvent(prefixed(header)) }
    }

    @Test
    fun `an error's message is at most 512 bytes, and a turn error's too`() {
        val long = "é".repeat(256) + "a"
        assertEquals(
            "message",
            server<ProtocolException.InvalidField>(
                """{"v":1,"t":"error","seq":29,"code":"x","message":"$long"}""",
            ).field,
        )
        val turn = """{"v":1,"t":"turn_error","seq":17,"turn_id":"t","code":"cancelled","message":"$long"}"""
        assertEquals("message", server<ProtocolException.InvalidField>(turn).field)
        decodeServerEvent(frameOf("""{"v":1,"t":"error","seq":29,"code":"x","message":"${"é".repeat(256)}"}"""))
    }

    @Test
    fun `a protocol v1 tool_event's detail is at most 512 bytes`() {
        val detail = "a".repeat(513)
        val header =
            """{"v":1,"t":"tool_event","seq":11,"turn_id":"t","tool":"web_search","phase":"stop","detail":"$detail"}"""
        assertEquals("detail", server<ProtocolException.InvalidField>(header).field)
    }

    @Test
    fun `an approval's routes are non-empty and at most 1,024 characters`() {
        val approval = { approve: String, deny: String ->
            """{"v":1,"t":"approval","seq":2,"approval_id":"a","kind":"sandbox","text":"Allow?","token":"x",""" +
                """"ttl_s":37,""" +
                """"approve_command":"$approve","deny_command":"$deny"}"""
        }
        assertEquals("approve_command", server<ProtocolException.InvalidField>(approval("", "/deny x")).field)
        assertEquals(
            "deny_command",
            server<ProtocolException.InvalidField>(approval("/confirm x", "d".repeat(1_025))).field,
        )
        decodeServerEvent(frameOf(approval("é".repeat(1_024), "/deny x")))
        assertEquals(
            "ttl_s",
            server<ProtocolException.InvalidField>(approval("/confirm x", "/deny x").replace("37", "0")).field,
        )
    }

    @Test
    fun `candidates are at most 16, each a lan or tailnet host of at most 253 bytes`() {
        val seventeen = List(17) { candidate("192.168.1.$it") }.joinToString(",", "[", "]")
        assertEquals("candidates", server<ProtocolException.InvalidField>(helloAck(candidates = seventeen)).field)
        decodeServerEvent(
            frameOf(helloAck(candidates = List(16) { candidate("192.168.1.$it") }.joinToString(",", "[", "]"))),
        )
        val scope = server<ProtocolException.InvalidField>(helloAck(candidates = "[${candidate("h", "wan")}]"))
        assertEquals("candidates[0].scope", scope.field)
        val host = server<ProtocolException.InvalidField>(helloAck(candidates = "[${candidate("h".repeat(254))}]"))
        assertEquals("candidates[0].host", host.field)
        decodeServerEvent(frameOf(helloAck(candidates = "[${candidate("h".repeat(253))}]")))
        val approved =
            """{"v":1,"t":"pair_approved","seq":1,"device_id":"d","candidates":$seventeen,"profiles":[]}"""
        assertEquals("candidates", server<ProtocolException.InvalidField>(approved).field)
    }

    @Test
    fun `a profile's name is at most 128 bytes`() {
        val profile = { name: String -> """[{"id":"main","name":"$name"}]""" }
        assertEquals(
            "profiles[0].name",
            server<ProtocolException.InvalidField>(
                helloAck(
                    profiles =
                        profile("é".repeat(64) + "a"),
                ),
            ).field,
        )
        assertEquals("profiles[0].name", server<ProtocolException.InvalidField>(helloAck(profiles = profile(""))).field)
        decodeServerEvent(frameOf(helloAck(profiles = profile("é".repeat(64)))))
    }

    @Test
    fun `only attach_chunk, media_chunk, event_part and a v2 pair_request carry a raw tail, never empty`() {
        assertEquals("attach_chunk", encoding<ProtocolException.MissingRaw>(ClientEvent.AttachChunk("a", 0)).t)
        assertEquals(
            "attach_chunk",
            client<ProtocolException.MissingRaw>("""{"v":1,"t":"attach_chunk","seq":4,"attach_id":"a","index":0}""").t,
        )
        assertEquals(
            "media_chunk",
            server<ProtocolException.MissingRaw>("""{"v":1,"t":"media_chunk","seq":15,"ref":"r","index":0}""").t,
        )
        assertEquals(
            "event_part",
            server<ProtocolException.MissingRaw>("""{"v":1,"t":"event_part","seq":34,"index":0,"count":2}""").t,
        )
        val tail = byteArrayOf(1)
        assertEquals(1, encoding<ProtocolException.UnexpectedRaw>(ClientEvent.Ping, tail).size)
        assertEquals(
            "pair_request",
            encoding<ProtocolException.UnexpectedRaw>(ClientEvent.PairRequest("p", "m", "1"), tail).t,
        )
        assertEquals("pong", server<ProtocolException.UnexpectedRaw>("""{"v":1,"t":"pong","seq":35}""", tail).t)
        assertEquals(
            "msg",
            client<ProtocolException.UnexpectedRaw>(
                """{"v":1,"t":"msg","seq":2,"client_msg_id":"c","profile_id":"main","text":"Hi","attach_ids":[]}""",
                tail,
            ).t,
        )
    }

    @Test
    fun `a digest is 64 hex digits and a timestamp starts with an RFC 3339 date and time`() {
        assertEquals("sha256", encoding<ProtocolException.InvalidField>(ClientEvent.AttachEnd("a", SHA.drop(1))).field)
        assertEquals("sha256", encoding<ProtocolException.InvalidField>(ClientEvent.AttachEnd("a", SHA + "\n")).field)
        assertEquals("ts", server<ProtocolException.InvalidField>(row("").replace(TS, "yesterday")).field)
        val media = ""","media_refs":[{"ref":"r","kind":"image","mime":"image/jpeg","size_bytes":1,"sha256":"zz"}]"""
        assertEquals(
            "media_refs[0].sha256",
            server<ProtocolException.InvalidField>(row("").replace(""","media_refs":[]""", media)).field,
        )
    }

    @Test
    fun `a msg has text or an attachment, and an upload's size is not negative`() {
        assertEquals(
            "text",
            encoding<ProtocolException.InvalidField>(ClientEvent.Msg("c", "main", "  ", emptyList())).field,
        )
        encodeClientEvent(1, 1uL, ClientEvent.Msg("c", "main", "", listOf("attach-1")))
        val upload = ClientEvent.AttachBegin("a", AttachKind.IMAGE, "image/jpeg", -1, sha256 = SHA)
        assertEquals("size_bytes", encoding<ProtocolException.InvalidField>(upload).field)
    }

    @Test
    fun `hello's protocol_v is the envelope's v`() {
        assertEquals(
            "protocol_v",
            encoding<ProtocolException.InvalidField>(ClientEvent.Hello("d", "1.0.0", 0uL, 2)).field,
        )
    }

    @Test
    fun `a row's link previews are 1 to 4 within their byte bounds, and truncated is only ever true`() {
        val card = """{"url":"https://example.com","site":"Example","title":"Example page"}"""
        val five = List(5) { card }.joinToString(",", ""","link_previews":[""", "]")
        assertEquals("link_previews", server<ProtocolException.InvalidField>(row(five)).field)
        assertEquals("link_previews", server<ProtocolException.InvalidField>(row(""","link_previews":[]""")).field)
        val site = card.replace("Example\"", "${"s".repeat(121)}\"")
        assertEquals(
            "link_previews[0].site",
            server<ProtocolException.InvalidField>(row(""","link_previews":[$site]""")).field,
        )
        assertEquals("truncated", server<ProtocolException.InvalidField>(row(""","truncated":false""")).field)
        val preview = """{"v":1,"t":"link_preview","seq":23,"in_reply_to":13,"url":"u","site":"s","title":"${"t".repeat(
            301,
        )}"}"""
        assertEquals("title", server<ProtocolException.InvalidField>(preview).field)
    }

    @Test
    fun `a history page holds at most 200 messages`() {
        val message = """{"server_seq":13,"role":"user","content":"Hi","ts":"$TS","media_refs":[]}"""
        val page = { count: Int ->
            """{"v":1,"t":"history_page","seq":26,"profile_id":"main","messages":${List(
                count,
            ) { message }.joinToString(",", "[", "]")},""" +
                """"next_after_seq":14,"history_head_seq":18}"""
        }
        assertEquals(
            "messages",
            assertThrows<ProtocolException.InvalidField> {
                decodeLogicalServerEvent(1, 26uL, logical(page(201)))
            }.field,
        )
        decodeLogicalServerEvent(1, 26uL, logical(page(200)))
    }

    @Test
    fun `a sequence number that counts from 1 is not 0`() {
        val done = """{"v":1,"t":"text_done","seq":12,"turn_id":"t","server_seq":0,"text":"x"}"""
        assertEquals("server_seq", server<ProtocolException.InvalidField>(done).field)
    }

    @Test
    fun `hello_ack's version window is not upside down`() {
        val ack = helloAck().replace(""""min_version":1,"max_version":1""", """"min_version":2,"max_version":1""")
        assertEquals("max_version", server<ProtocolException.InvalidField>(ack.replace(""""v":1""", """"v":2""")).field)
    }

    /** A page too long for one frame, as the logical JSON of a run: `t` and the fields, no `v` or `seq`. */
    private fun logical(header: String) =
        header.replace(""""v":1,""", "").replace(""","seq":26""", "").encodeToByteArray()

    private fun escape(text: String) =
        text.map { char -> if (char.needsEscape()) char.jsonEscape() else "$char" }.joinToString("")

    private fun Char.needsEscape() = this < ' ' || this in '\u007F'..'\u009F' || isSurrogate()

    private fun Char.jsonEscape() = "\\u" + code.toString(16).padStart(4, '0')
}
