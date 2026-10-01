package io.tezra.fermix.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeout
import java.time.Duration

/**
 * The envelope and the header around every event (PROTOCOL.md "Envelope, ordering, and version
 * negotiation"): `v`, `t` and `seq`, an unknown server `t`, unknown and null fields, and a header
 * that is not one JSON object in UTF-8 under RFC 8259, or that nests too deep to read safely.
 */
class EnvelopeTest {
    private val pong = """{"v":1,"t":"pong","seq":35}"""

    @Test
    fun `an unknown server event decodes to Unknown with its name, seq and header, and never throws`() {
        val header = """{"v":2,"t":"future_event","seq":7,"anything":{"nested":[1,2]}}"""
        val decoded = decodeServerEvent(frameOf(header))
        assertEquals(ServerEvent.Unknown("future_event", 7uL, header), decoded.event)
        assertEquals(2, decoded.v)
        assertEquals(7uL, decoded.seq)
    }

    @Test
    fun `an unknown event is built only with a name and a seq from 1`() {
        assertThrows<IllegalArgumentException> { ServerEvent.Unknown("", 1uL, "{}") }
        assertThrows<IllegalArgumentException> { ServerEvent.Unknown("future_event", 0uL, "{}") }
    }

    @Test
    fun `an unknown server event may carry a raw tail, which comes back untouched`() {
        val decoded = decodeServerEvent(frameOf("""{"v":2,"t":"call_audio","seq":7}""", byteArrayOf(1, 2, 3)))
        assertEquals("call_audio", (decoded.event as ServerEvent.Unknown).t)
        assertEquals(listOf<Byte>(1, 2, 3), decoded.raw.toList())
    }

    @Test
    fun `an unknown client event is refused, as the daemon refuses it`() {
        val refusal =
            assertThrows<ProtocolException.UnknownEvent> {
                decodeClientEvent(
                    frameOf("""{"v":1,"t":"event_part","seq":1}"""),
                )
            }
        assertEquals("event_part", refusal.t)
    }

    @Test
    fun `an unknown field is ignored on decode and never written on encode`() {
        val reaction =
            decodeServerEvent(
                frameOf("""{"v":1,"t":"reaction","seq":20,"in_reply_to":"client-1","later":true,"emoji":"👍"}"""),
            )
        assertEquals(ServerEvent.Reaction("client-1", "👍"), reaction.event)
        assertEquals(
            """{"v":1,"t":"reaction","seq":20,"in_reply_to":"client-1","emoji":"👍"}""",
            headerOf(encodeServerEvent(1, 20uL, reaction.event as ServerEvent.Known)),
        )
    }

    @Test
    fun `an unknown field is ignored whatever it holds, a null or a number of any JSON form`() {
        listOf("null", """{"x":null}""", "[null]", "-1.5e3", "0").forEach { value ->
            val header = """{"v":1,"t":"pong","seq":1,"later":$value}"""
            assertEquals(ServerEvent.Pong, decodeServerEvent(frameOf(header)).event, header)
        }
    }

    @Test
    fun `a key named twice takes its last value, as the parser reads it`() {
        assertEquals(ServerEvent.Pong, decodeServerEvent(frameOf("""{"v":1,"t":"error","seq":1,"t":"pong"}""")).event)
        assertEquals(2uL, decodeServerEvent(frameOf("""{"v":1,"t":"pong","seq":1,"seq":2}""")).seq)
    }

    @Test
    fun `a number or a bare word JSON does not allow is refused, wherever it is`() {
        val row = { metadata: String ->
            """{"v":1,"t":"row","seq":19,"profile_id":"main","server_seq":18,"role":"assistant","text":"x",""" +
                """"ts":"2026-09-27T09:01:05.000000Z","media_refs":[],"metadata":$metadata}"""
        }
        val page = { serverSeq: String ->
            """{"v":1,"t":"history_page","seq":26,"profile_id":"main","messages":[{"server_seq":$serverSeq,""" +
                """"role":"user","content":"Hi","ts":"2026-09-27T09:00:00Z","media_refs":[]}],""" +
                """"next_after_seq":14,"history_head_seq":18}"""
        }
        val media = """"media_refs":[{"ref":"r","kind":"image","mime":"image/jpeg","size_bytes":01}]"""
        val headers =
            listOf(
                """{"v":1,"t":"pong","seq":+1}""",
                """{"v":1,"t":"pong","seq":01}""",
                """{"v":+1,"t":"pong","seq":1}""",
                """{"v":01,"t":"pong","seq":1}""",
                """{"v":1,"t":pong,"seq":1}""",
                """{"v":1,"t":"accepted","seq":3,"client_msg_id":"c","duplicate":true,"server_seq":007}""",
                """{"v":1,"t":"pong","seq":1,"later":garbage}""",
                """{"v":1,"t":"future","seq":1,"later":.5}""",
                row("""{"a":garbage}"""),
                row("""{"b":+1}"""),
                row("""{"c":01}"""),
                // Inside an array: a known field of an entry, an unknown array, and one in metadata.
                page("013"),
                page("+13"),
                row("{}").replace(""""media_refs":[]""", media),
                """{"v":1,"t":"pong","seq":1,"later":[+1]}""",
                """{"v":1,"t":"future","seq":1,"later":[1,[01]]}""",
                row("""{"d":[01]}"""),
            )
        headers.forEach { header ->
            assertThrows<ProtocolException.MalformedHeader>(header) { decodeServerEvent(frameOf(header)) }
        }
        val pull = """{"v":1,"t":"history_pull","seq":10,"profile_id":"main","after_seq":0,"limit":05}"""
        assertThrows<ProtocolException.MalformedHeader> { decodeClientEvent(frameOf(pull)) }
    }

    @Test
    fun `a refusal never quotes the header it refused`() {
        val approval =
            """{"v":1,"t":"approval","seq":2,"approval_id":"a","kind":"sandbox","text":"Allow?",""" +
                """"token":"SECRETTOKEN123","ttl_s":37,"approve_command":"/confirm SECRETTOKEN123",""" +
                """"deny_command":"/deny SECRETTOKEN123"}"""
        val broken = listOf(approval.dropLast(1), approval.replace("37", "+1"), approval.replace("37", "\"37\""))
        broken.forEach { header ->
            val refusal = assertThrows<ProtocolException>(header) { decodeServerEvent(frameOf(header)) }
            generateSequence<Throwable>(refusal) { it.cause }.take(8).forEach { link ->
                assertFalse(link.message.orEmpty().contains("SECRETTOKEN123"), "${link::class.simpleName}: $header")
            }
        }
    }

    @Test
    fun `nesting past 32 levels is refused before it is parsed, on a known event, an unknown one, or a field`() {
        val unclosed = "[".repeat(MAX_HEADER_BYTES - 40)
        val deep = "[".repeat(3_000)
        val headers =
            listOf(
                """{"v":1,"t":"pong","seq":1,"x":$unclosed""",
                """{"v":1,"t":"future","seq":1,"x":$unclosed""",
                """{"v":1,"t":"future","seq":1,"x":${"[".repeat(2_031)}${"]".repeat(2_031)}}""",
                """{"v":1,"t":"pong","seq":1,"x":${"{\"a\":".repeat(33)}1${"}".repeat(33)}}""",
                // 33 levels, one past the bound: the object and 32 arrays.
                """{"v":1,"t":"future","seq":1,"x":${"[".repeat(32)}${"]".repeat(32)}}""",
                // An escape in a string ends where the scan says, so the nesting after it is counted.
                """{"v":1,"t":"future","seq":1,"a":"\"","x":$deep""",
                """{"v":1,"t":"future","seq":1,"a":"\n","x":$deep""",
                """{"v":1,"t":"future","seq":1,"a":"\\","x":$deep""",
            )
        headers.forEach { header ->
            check(header.length <= MAX_HEADER_BYTES) { "the planted header is ${header.length} bytes" }
            onSmallStack {
                assertThrows<ProtocolException.MalformedHeader>(header.take(80)) { decodeServerEvent(frameOf(header)) }
            }
        }
        val within = """{"v":1,"t":"future","seq":1,"x":${"[".repeat(31)}${"]".repeat(31)}}"""
        assertEquals("future", (decodeServerEvent(frameOf(within)).event as ServerEvent.Unknown).t)
        val inString = """{"v":1,"t":"future","seq":1,"x":"${"[".repeat(100)}\"{"}"""
        assertEquals("future", (decodeServerEvent(frameOf(inString)).event as ServerEvent.Unknown).t)
        val afterEscapedQuote = """{"v":1,"t":"future","seq":1,"x":"\"${"[".repeat(40)}"}"""
        assertEquals("future", (decodeServerEvent(frameOf(afterEscapedQuote)).event as ServerEvent.Unknown).t)
    }

    @Test
    fun `a raw control character inside a string is refused, as RFC 8259 has it escaped`() {
        val headers =
            listOf(
                """{"v":1,"t":"notice","seq":35,"kind":"k","text":"a${'\u0001'}b"}""",
                """{"v":1,"t":"notice","seq":35,"kind":"k","text":"a${'\n'}b"}""",
                """{"v":1,"t":"fut${'\t'}re","seq":35}""",
                """{"v":1,"t":"future","seq":35,"a${'\u0000'}b":1}""",
                """{"v":1,"t":"future","seq":35,"a":"\${'\u0001'}"}""",
            )
        headers.forEach { header ->
            assertThrows<ProtocolException.MalformedHeader>(header) { decodeServerEvent(frameOf(header)) }
        }
        val logical = """{"t":"notice","kind":"k","text":"a${'\u0001'}b"}""".encodeToByteArray()
        assertThrows<ProtocolException.MalformedHeader> { decodeLogicalServerEvent(1, 35uL, logical) }
        val escaped = """{"v":1,"t":"notice","seq":35,"kind":"k","text":"a\u0001\n\tb"}"""
        assertEquals(ServerEvent.Notice("k", "a\u0001\n\tb"), decodeServerEvent(frameOf(escaped)).event)
        val spaced = "{\n\t\"v\":1,\r\n\"t\":\"pong\", \"seq\":35}"
        assertEquals(ServerEvent.Pong, decodeServerEvent(frameOf(spaced)).event)
    }

    @Test
    fun `a null in a free-form field far down a wide array is found quickly, and named by its path`() {
        val key = "k".repeat(500_000)
        val wide = List(250_000) { "1" }.joinToString(",", "[", "]")
        val row =
            """{"t":"row","profile_id":"main","server_seq":18,"role":"assistant","text":"x",""" +
                """"ts":"2026-09-27T09:01:05.000000Z","media_refs":[],"metadata":{"$key":$wide,"last":[1,null]}}"""
        check(row.length <= MAX_EVENT_BYTES) { "the planted event is ${row.length} bytes" }
        val refusal =
            assertTimeout(Duration.ofSeconds(5)) {
                assertThrows<ProtocolException.NullField> { decodeLogicalServerEvent(1, 19uL, row.encodeToByteArray()) }
            }
        assertEquals("metadata.last[1]", refusal.field)
    }

    @Test
    fun `a refusal quotes a free-form key only in part, however long it is`() {
        val key = "K".repeat(200_000)
        val row =
            """{"t":"row","profile_id":"main","server_seq":18,"role":"assistant","text":"x",""" +
                """"ts":"2026-09-27T09:01:05.000000Z","media_refs":[],"metadata":{"$key":{"$key":null}}}"""
        val refusal =
            assertThrows<ProtocolException.NullField> { decodeLogicalServerEvent(1, 19uL, row.encodeToByteArray()) }
        val quoted = "K".repeat(64) + "…"
        assertEquals("metadata.$quoted.$quoted", refusal.field)
        assertTrue(refusal.message.orEmpty().length < 256, "the refusal's message is ${refusal.message?.length}")
        // Cut by code points, so a key of emoji is never cut through a surrogate pair.
        val emoji = row.replace("{\"$key\":null}", "null").replace(key, "😀".repeat(100)).encodeToByteArray()
        val split = assertThrows<ProtocolException.NullField> { decodeLogicalServerEvent(1, 19uL, emoji) }
        assertEquals("metadata.${"😀".repeat(64)}…", split.field)
    }

    @Test
    fun `an optional field sent as null is refused, never read as absent`() {
        val refusal =
            assertThrows<ProtocolException.NullField> {
                decodeServerEvent(
                    frameOf(
                        """{"v":1,"t":"accepted","seq":3,"client_msg_id":"client-1","duplicate":false,""" +
                            """"server_seq":null}""",
                    ),
                )
            }
        assertEquals("server_seq", refusal.field)
        val nested =
            assertThrows<ProtocolException.NullField> {
                decodeServerEvent(
                    frameOf(
                        """{"v":1,"t":"row","seq":19,"profile_id":"main","server_seq":18,"role":"assistant",""" +
                            """"text":"x",""" +
                            """"ts":"2026-09-27T09:01:05.000000Z","media_refs":[],"metadata":{"turn_id":null}}""",
                    ),
                )
            }
        assertEquals("metadata.turn_id", nested.field)
    }

    @Test
    fun `an optional field the model leaves null is absent from the header`() {
        val frame = encodeClientEvent(1, 7uL, ClientEvent.Command("client-3", "main", "help"))
        assertEquals(
            """{"v":1,"t":"command","seq":7,"client_msg_id":"client-3","profile_id":"main","name":"help"}""",
            headerOf(frame),
        )
    }

    @Test
    fun `v is 1 or 2, and anything else is refused by type`() {
        assertEquals(2, decodeServerEvent(frameOf("""{"v":2,"t":"pong","seq":1}""")).v)
        assertEquals(
            3L,
            assertThrows<ProtocolException.UnsupportedVersion> {
                decodeServerEvent(frameOf("""{"v":3,"t":"pong","seq":1}"""))
            }.version,
        )
        assertEquals(
            0L,
            assertThrows<ProtocolException.UnsupportedVersion> {
                decodeServerEvent(frameOf("""{"v":0,"t":"pong","seq":1}"""))
            }.version,
        )
        listOf(
            """{"v":"1","t":"pong","seq":1}""",
            """{"v":1.5,"t":"pong","seq":1}""",
            """{"t":"pong","seq":1}""",
        ).forEach { header ->
            assertEquals(
                "v",
                assertThrows<ProtocolException.InvalidEnvelope> { decodeServerEvent(frameOf(header)) }.field,
            )
        }
        assertThrows<ProtocolException.UnsupportedVersion> { encodeClientEvent(3, 1uL, ClientEvent.Ping) }
    }

    @Test
    fun `t is a non-empty string`() {
        listOf("""{"v":1,"t":"","seq":1}""", """{"v":1,"t":7,"seq":1}""", """{"v":1,"seq":1}""").forEach { header ->
            assertEquals(
                "t",
                assertThrows<ProtocolException.InvalidEnvelope> { decodeServerEvent(frameOf(header)) }.field,
            )
        }
    }

    @Test
    fun `seq is an unsigned 64-bit integer from 1, read whole up to 2^64 - 1`() {
        assertEquals(
            ULong.MAX_VALUE,
            decodeServerEvent(frameOf("""{"v":1,"t":"pong","seq":18446744073709551615}""")).seq,
        )
        listOf("0", "-1", "18446744073709551616", "1.0", "\"1\"", "null").forEach { seq ->
            val refusal =
                assertThrows<ProtocolException.InvalidEnvelope> {
                    decodeServerEvent(
                        frameOf("""{"v":1,"t":"pong","seq":$seq}"""),
                    )
                }
            assertEquals("seq", refusal.field, seq)
        }
        assertEquals(
            "seq",
            assertThrows<ProtocolException.InvalidEnvelope> {
                encodeClientEvent(1, 0uL, ClientEvent.Ping)
            }.field,
        )
        assertEquals(
            """{"v":1,"t":"ping","seq":18446744073709551615}""",
            headerOf(encodeClientEvent(1, ULong.MAX_VALUE, ClientEvent.Ping)),
        )
    }

    @Test
    fun `a header that is not UTF-8, not JSON, or not one object is refused`() {
        val invalidUtf8 = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte())
        assertThrows<ProtocolException.MalformedHeader> { decodeServerEvent(prefixed(invalidUtf8)) }
        val malformed =
            listOf("""{"v":1,"t":"pong","seq":1""", "[1]", "\"pong\"", "", """{"v":1,"t":"pong","seq":1} {}""")
        malformed.forEach { header ->
            assertThrows<ProtocolException.MalformedHeader>(header) { decodeServerEvent(frameOf(header)) }
        }
        decodeServerEvent(frameOf(pong))
    }

    @Test
    fun `an unknown value of a closed set is a decode error naming its field`() {
        val refusal =
            assertThrows<ProtocolException.InvalidField> {
                decodeServerEvent(
                    frameOf(
                        """{"v":1,"t":"tool_event","seq":10,"turn_id":"turn-client-1","tool":"web_search",""" +
                            """"phase":"pause"}""",
                    ),
                )
            }
        assertEquals("tool_event", refusal.t)
        assertEquals("phase", refusal.field)
        val nested =
            assertThrows<ProtocolException.InvalidField> {
                decodeServerEvent(
                    frameOf(
                        """{"v":1,"t":"pair_approved","seq":1,"device_id":"d","candidates":[{"host":"h",""" +
                            """"interface":"en0","scope":"wan"}],"profiles":[]}""",
                    ),
                )
            }
        assertEquals("candidates[0].scope", nested.field)
    }

    @Test
    fun `a field of the wrong type is refused by its path`() {
        val cases =
            mapOf(
                """{"v":1,"t":"accepted","seq":3,"client_msg_id":"c","duplicate":"false"}""" to "duplicate",
                """{"v":1,"t":"accepted","seq":3,"client_msg_id":7,"duplicate":false}""" to "client_msg_id",
                """{"v":1,"t":"accepted","seq":3,"client_msg_id":"c","duplicate":false,"server_seq":-1}""" to
                    "server_seq",
                """{"v":1,"t":"approval","seq":2,"approval_id":"a","kind":"k","text":"t","token":"x",""" +
                    """"ttl_s":2147483648,""" +
                    """"approve_command":"/confirm x","deny_command":"/deny x"}""" to "ttl_s",
                """{"v":1,"t":"hello_ack","seq":1,"session_id":"s","min_version":1,"max_version":1,"profiles":{},""" +
                    """"candidates":[],"history_head_seq":0,"read_up_to_seq":0,"caps":{"commands":[],""" +
                    """"max_media_bytes":1}}""" to
                    "profiles",
                """{"v":1,"t":"row","seq":5,"profile_id":"main","server_seq":13,"role":"user","text":"Hi",""" +
                    """"ts":"2026-09-27T09:00:00Z",""" +
                    """"media_refs":[],"metadata":[]}""" to "metadata",
                // A quoted integer is a string, whether the field is unsigned, 64-bit or 32-bit.
                """{"v":1,"t":"accepted","seq":3,"client_msg_id":"c","duplicate":true,"server_seq":"5"}""" to
                    "server_seq",
                """{"v":1,"t":"media_begin","seq":14,"ref":"r","server_seq":16,"kind":"document",""" +
                    """"mime":"application/pdf","size_bytes":"3","sha256":"${"b".repeat(64)}"}""" to "size_bytes",
                """{"v":1,"t":"approval","seq":2,"approval_id":"a","kind":"k","text":"t","token":"x","ttl_s":"37",""" +
                    """"approve_command":"/confirm x","deny_command":"/deny x"}""" to "ttl_s",
            )
        cases.forEach { (header, field) ->
            assertEquals(
                field,
                assertThrows<ProtocolException.InvalidField>(header) { decodeServerEvent(frameOf(header)) }.field,
            )
        }
    }

    @Test
    fun `a frame decodes as the header and tail its bytes say`() {
        val decoded = decodeServerEvent(frameOf(pong))
        assertEquals(ServerEvent.Pong, decoded.event)
        assertEquals(1, decoded.v)
        assertEquals(35uL, decoded.seq)
    }
}
