package io.tezra.fermix.onboarding

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a scanned or pasted text is (design section 13.3, step 3), each text one outcome: a link the
 * ceremony takes, an older or a newer Fermix's, or the scan's refusal, by field.
 */
class LinkOutcomeTest {
    @Test
    fun `a version-2 link with every field, its candidates in range, is the ceremony's`() {
        val taken = readLink(linkText()) as LinkOutcome.Link
        assertEquals(HOST, taken.link.name)
        assertArrayEquals(ByteArray(32) { (it + 1).toByte() }, taken.link.secret)
    }

    @Test
    fun `every other text reads as its one refusal or version`() {
        val table =
            listOf(
                "https://example.com/pair?v=2" to LinkOutcome.NotAFermixCode,
                "" to LinkOutcome.NotAFermixCode,
                "FERMIX://PAIR?v=2" to LinkOutcome.NotAFermixCode,
                linkText().replace("v=2", "v=1") to LinkOutcome.OlderFermix(HOST),
                linkText().replace("v=2", "v=3") to LinkOutcome.NewerFermix,
                linkText().replace("v=2", "v=two") to LinkOutcome.Invalid("v"),
                linkText().replace(Regex("&name=[^&]*"), "") to LinkOutcome.Invalid("name"),
                linkText().replace(Regex("secret=[^&]*"), "secret=c2hvcnQ%3D") to LinkOutcome.Invalid("secret"),
                linkText() + "&port=4032" to LinkOutcome.Invalid("port"),
                linkText().replace(Regex("candidates=[^&]*"), "candidates=%5B%5D") to LinkOutcome.Invalid("candidates"),
                linkText().replace("192.168.1.20", "8.8.8.8") to LinkOutcome.Invalid("candidates"),
            )
        for ((text, expected) in table) assertEquals(expected, readLink(text), text)
    }

    @Test
    fun `the scan refuses only a text that is no Fermix link the phone acts on`() {
        assertFalse(readLink(linkText()).refused)
        assertFalse(LinkOutcome.OlderFermix(HOST).refused)
        assertFalse(LinkOutcome.NewerFermix.refused)
        assertTrue(LinkOutcome.NotAFermixCode.refused)
        assertTrue(LinkOutcome.Invalid("secret").refused)
    }
}
