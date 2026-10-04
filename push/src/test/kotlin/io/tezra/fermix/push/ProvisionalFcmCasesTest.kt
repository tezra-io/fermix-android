package io.tezra.fermix.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

// PROVISIONAL UNTIL THE PROTOCOL V2 EXPORT IS VENDORED (engine stage D1). Design section 7's export
// obligations give push_vectors.json "an FCM case (same keys/salt/nonce, the exact padded plaintext and
// message.data map) and one vector per push kind on both platforms"; the vendored file, protocol v1's, has
// the APNs case alone (PushVectorTest). Until D1's file is vendored, each FCM case is built here the way the
// daemon would build it, from the vendored vector's own keys, salt and nonce and design sections 7 and 10:
// the typed plaintext, padded to 2,048 bytes with one 0x80 and zeros, sealed under the push key, and sent as
// {"v":"2","n","c"}. When D1's file lands, its cases replace these, byte for byte.

/** One FCM case: the plaintext the daemon would seal, and what the phone reads from it. */
class FcmCase(
    val name: String,
    val json: String,
    val expected: PlaintextRead,
) {
    override fun toString(): String = name
}

/**
 * Each kind of design section 7's push plaintext as an FCM message the phone receives (design section 10):
 * bounded, opened by the push key the device side derives, unpadded and typed.
 */
class ProvisionalFcmCasesTest {
    private val vector = pushVector()

    /** The daemon's side of [case]: sealed with the vector's push key and nonce. */
    private fun sent(case: FcmCase): Map<String, String> =
        fcmData(vector.hex("nonce"), seal(vector.hex("push_key"), vector.hex("nonce"), padded(case.json)))

    @ParameterizedTest
    @MethodSource("cases")
    fun `every kind opens with the key the phone derives, and reads as its type`(case: FcmCase) {
        val envelope = (PushEnvelope.read(sent(case)) as EnvelopeRead.Read).envelope
        val device = SoftwareKey(vector.hex("device_static_private"))
        val shared = device.agree(vector.hex("gateway_static_public"))
        val key = PushKeys.derive(shared, vector.hex("apns_key_salt"))
        val opened = checkNotNull(PushCipher.open(key, envelope.nonce(), envelope.sealed()))
        assertEquals(PADDED_PLAINTEXT_BYTES, opened.size)
        assertEquals(case.expected, readPushPlaintext(opened))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `every message's data map stays within FCM's 4,096 bytes, about the 2,800 that section 10 measured`(
        case: FcmCase,
    ) {
        val bytes = sent(case).entries.sumOf { (key, value) -> key.length + value.length }
        assertEquals(2_752, sent(case).getValue("c").length)
        assertTrue(bytes in 2_700..2_800, "$bytes")
    }

    companion object {
        @JvmStatic
        fun cases(): List<FcmCase> =
            listOf(
                FcmCase(
                    "message",
                    """{"kind":"message","profile_id":"main","server_seq":42,"preview_text":"hello from fermix"}""",
                    PlaintextRead.Read(PushPlaintext.Message("main", 42uL, "hello from fermix")),
                ),
                FcmCase(
                    "message whose preview did not fit",
                    """{"kind":"message","profile_id":"main","server_seq":43,"preview_text":null}""",
                    PlaintextRead.Read(PushPlaintext.Message("main", 43uL, null)),
                ),
                FcmCase(
                    "approval",
                    """{"kind":"approval","profile_id":"main","approval_id":"appr_7","expires_at":1790000000}""",
                    PlaintextRead.Read(PushPlaintext.Approval("main", "appr_7", 1_790_000_000L)),
                ),
                FcmCase(
                    "turn_failed",
                    """{"kind":"turn_failed","profile_id":"main","turn_id":"turn_9","code":"timeout"}""",
                    PlaintextRead.Read(PushPlaintext.TurnFailed("main", "turn_9", "timeout")),
                ),
                FcmCase(
                    "a kind this app does not know",
                    """{"kind":"call_missed","profile_id":"main","call_id":"c_1"}""",
                    PlaintextRead.Read(PushPlaintext.Unknown),
                ),
            )
    }
}
