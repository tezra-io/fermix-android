package io.tezra.fermix.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The push diagnostics ring: its lines as design section 10 names them, and its bound. */
class PushLogTest {
    @Test
    fun `a line names its decision, then its instance after a colon, then its detail`() {
        assertEquals("received", PushLine(1L, PushDecision.RECEIVED).toString())
        assertEquals("decrypted:fx-1", PushLine(1L, PushDecision.DECRYPTED, "fx-1").toString())
        assertEquals("suppressed:set:fx-1", PushLine(1L, PushDecision.SUPPRESSED_SET, "fx-1").toString())
        assertEquals("generic json", PushLine(1L, PushDecision.GENERIC, detail = "json").toString())
        assertEquals("timed:fx-1 41 ms", PushLine(1L, PushDecision.TIMED, "fx-1", "41 ms").toString())
    }

    @Test
    fun `a flood of pushes no key opened keeps to its own ring, and the lines of the opened ones stay`() {
        val log = PushLog()
        log.add(PushLine(1L, PushDecision.POSTED, "fx-1"))
        repeat(MAX_PUSH_LINES * 2) {
            log.addUnopened(
                listOf(PushLine(2L, PushDecision.RECEIVED), PushLine(2L, PushDecision.GENERIC, detail = "x")),
            )
        }
        assertEquals(listOf("posted:fx-1"), log.lines.value.map { it.toString() })
        assertEquals(MAX_PUSH_LINES, log.unopened.value.size)
        assertThrows(IllegalArgumentException::class.java) {
            log.addUnopened(listOf(PushLine(3L, PushDecision.DECRYPTED, "fx-1")))
        }
    }

    @Test
    fun `the ring keeps the newest lines up to its bound`() {
        val log = PushLog()
        repeat(MAX_PUSH_LINES + 2) { log.add(PushLine(it.toLong(), PushDecision.RECEIVED)) }
        assertEquals(MAX_PUSH_LINES, log.lines.value.size)
        assertEquals(
            2L,
            log.lines.value
                .first()
                .atMs,
        )
    }
}
