package io.tezra.fermix.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private fun read(json: String): PlaintextRead = readPushPlaintext(padded(json))

private fun unreadable(reason: String): PlaintextRead = PlaintextRead.Unreadable(reason)

/**
 * An opened push's bytes as design section 10 pads them, and the typed plaintext of section 7's row: a known
 * kind's every field is required and typed, `preview_text` alone may be null, and an unknown kind is the
 * generic notification's.
 */
class PushPlaintextTest {
    @Test
    fun `a bucket of another size, one with no mark, and one with bytes after the mark are refused`() {
        val json = """{"kind":"message","profile_id":"main","server_seq":1}"""
        assertEquals(unreadable("padding"), readPushPlaintext(padded(json).copyOf(PADDED_PLAINTEXT_BYTES - 1)))
        assertEquals(unreadable("padding"), readPushPlaintext(ByteArray(PADDED_PLAINTEXT_BYTES)))
        val unmarked = json.encodeToByteArray().copyOf(PADDED_PLAINTEXT_BYTES)
        assertEquals(unreadable("padding"), readPushPlaintext(unmarked))
        val trailing = padded(json).also { it[PADDED_PLAINTEXT_BYTES - 1] = 1 }
        assertEquals(unreadable("padding"), readPushPlaintext(trailing))
    }

    @Test
    fun `bytes that are not one JSON object in UTF-8 are refused`() {
        assertEquals(unreadable("json"), read("""["message"]"""))
        assertEquals(unreadable("json"), read("""{"kind":"message",}"""))
        val invalid = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte(), 0x80.toByte())
        assertEquals(unreadable("json"), readPushPlaintext(invalid.copyOf(PADDED_PLAINTEXT_BYTES)))
    }

    @Test
    fun `a message needs its profile and a server_seq from 1 to what the store keeps, and its preview may be null`() {
        assertEquals(
            PlaintextRead.Read(PushPlaintext.Message("main", 7uL, null)),
            read("""{"kind":"message","profile_id":"main","server_seq":7}"""),
        )
        assertEquals(
            PlaintextRead.Read(PushPlaintext.Message("main", Long.MAX_VALUE.toULong(), "hi")),
            read("""{"kind":"message","profile_id":"main","server_seq":9223372036854775807,"preview_text":"hi"}"""),
        )
        assertEquals(
            unreadable("field server_seq"),
            read("""{"kind":"message","profile_id":"main","server_seq":9223372036854775808}"""),
        )
        assertEquals(
            unreadable("field server_seq"),
            read("""{"kind":"message","profile_id":"main","server_seq":18446744073709551615}"""),
        )
        assertEquals(unreadable("field server_seq"), read("""{"kind":"message","profile_id":"main","server_seq":0}"""))
        assertEquals(
            unreadable("field server_seq"),
            read("""{"kind":"message","profile_id":"main","server_seq":"7"}"""),
        )
        assertEquals(unreadable("field server_seq"), read("""{"kind":"message","profile_id":"main","server_seq":-1}"""))
        assertEquals(unreadable("field profile_id"), read("""{"kind":"message","server_seq":7}"""))
        assertEquals(unreadable("field profile_id"), read("""{"kind":"message","profile_id":"","server_seq":7}"""))
        assertEquals(
            unreadable("field preview_text"),
            read("""{"kind":"message","profile_id":"main","server_seq":7,"preview_text":5}"""),
        )
    }

    @Test
    fun `an approval needs its id and its expiry in Unix seconds, and a failed turn its id and code`() {
        assertEquals(
            unreadable("field expires_at"),
            read("""{"kind":"approval","profile_id":"main","approval_id":"a","expires_at":-5}"""),
        )
        assertEquals(
            unreadable("field approval_id"),
            read("""{"kind":"approval","profile_id":"main","approval_id":7,"expires_at":5}"""),
        )
        assertEquals(unreadable("field code"), read("""{"kind":"turn_failed","profile_id":"main","turn_id":"t"}"""))
        assertEquals(unreadable("field turn_id"), read("""{"kind":"turn_failed","profile_id":"main","code":"x"}"""))
    }

    @Test
    fun `no kind, a kind that is no string, or one this app does not know is the generic notification's`() {
        assertEquals(PlaintextRead.Read(PushPlaintext.Unknown), read("""{"profile_id":"main"}"""))
        assertEquals(PlaintextRead.Read(PushPlaintext.Unknown), read("""{"kind":7,"profile_id":"main"}"""))
        assertEquals(PlaintextRead.Read(PushPlaintext.Unknown), read("""{"kind":"reaction","profile_id":"main"}"""))
    }
}
