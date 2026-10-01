package io.tezra.fermix.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The Instance screen's Diagnostics keep the newest 200 entries and let the oldest go. */
class DiagnosticsTest {
    @Test
    fun `the log keeps the newest 200 entries in order`() {
        val log = DiagnosticsLog()
        repeat(250) { log.add(Diagnostic(it.toLong(), DiagnosticKind.BOUND, "entry $it")) }
        val entries = log.entries.value
        assertEquals(200, entries.size)
        assertEquals("entry 50", entries.first().detail)
        assertEquals("entry 249", entries.last().detail)
    }
}
