package io.tezra.fermix.instance

import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.DiagnosticKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** The Instance screen's fingerprint, pairing date and log lines (design section 13.7). */
class InstanceTextTest {
    @Test
    fun `the key fingerprint is the gateway key hash's first sixteen digits, upper case, in fours`() {
        assertEquals("4F2A 9C71 E3B8 08D6", keyFingerprint("4f2a9c71e3b808d6" + "0".repeat(48)))
        assertThrows<IllegalArgumentException> { keyFingerprint("4f2a") }
    }

    @Test
    fun `Paired since is the day in the zone it is read in, whatever the machine's own`() {
        assertEquals("Sep 27, 2026", pairedDate(PAIRED_AT, ZoneOffset.UTC, Locale.US))
        assertEquals("Sep 26, 2026", pairedDate(PAIRED_AT, ZoneId.of("Pacific/Honolulu"), Locale.US))
        assertThrows<IllegalArgumentException> { pairedDate(-1L, ZoneOffset.UTC, Locale.US) }
    }

    @Test
    fun `a log line says when after the session opened, the kind and the detail`() {
        val entry = Diagnostic(atMs = 3_723_000, kind = DiagnosticKind.UNKNOWN_EVENT, detail = "t=call_offer")
        assertEquals("+1:02:03  unknown_event  t=call_offer", logLine(entry))
    }
}
