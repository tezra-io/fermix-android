package io.tezra.fermix.instance

import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.DiagnosticKind
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.transport.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A link read from a session's state and diagnostics (design sections 9.4 and 13.5). */
class LinkTest {
    private fun entry(kind: DiagnosticKind) = Diagnostic(atMs = 0, kind = kind, detail = "")

    private val protocolError = entry(DiagnosticKind.PROTOCOL_ERROR)
    private val closed = entry(DiagnosticKind.CLOSED)

    @Test
    fun `a 1002 shows as a protocol error while the session backs off, and never as revoked`() {
        for (state in listOf(SessionState.Connecting, SessionState.CannotReach, SessionState.WaitingForNetwork)) {
            val link = linkOf(state, listOf(closed, protocolError))
            assertEquals(Link.ProtocolError, link)
            assertNotEquals(Link.Revoked, link)
            assertFalse(link.needsTrust)
            assertNotEquals(Dot.ERR, link.dot)
            assertTrue(link.speaksOnRow)
        }
    }

    @Test
    fun `a connection after the protocol error, or a later ending, clears it`() {
        val up = linkOf(SessionState.Connected(Candidate.Scope.LAN, 9, caughtUp = true), listOf(protocolError))
        assertEquals(Link.Up(Candidate.Scope.LAN, 9, caughtUp = true), up)
        assertEquals(Link.Connecting, linkOf(SessionState.Connecting, listOf(protocolError, closed)))
        val unrelated = entry(DiagnosticKind.UNKNOWN_EVENT)
        assertEquals(Link.ProtocolError, linkOf(SessionState.Connecting, listOf(protocolError, unrelated)))
    }

    @Test
    fun `revoked and identity changed are the trust states, and only revoked is red`() {
        assertEquals(Link.Revoked, linkOf(SessionState.Revoked, listOf(protocolError)))
        assertEquals(Dot.ERR, Link.Revoked.dot)
        assertEquals(Link.IdentityChanged, linkOf(SessionState.IdentityChanged, emptyList()))
        assertEquals(Dot.WARN, Link.IdentityChanged.dot)
        assertTrue(Link.Revoked.needsTrust && Link.IdentityChanged.needsTrust)
        assertFalse(Link.Replaced.needsTrust)
    }

    @Test
    fun `the dot is never optimistic, and a row offline keeps its last message`() {
        assertEquals(Dot.OFF, linkOf(null, emptyList()).dot)
        assertEquals(Dot.WARN, linkOf(SessionState.Connecting, emptyList()).dot)
        assertEquals(Dot.OFF, linkOf(SessionState.CannotReach, emptyList()).dot)
        assertFalse(Link.CannotReach.speaksOnRow)
        assertFalse(Link.Connecting.speaksOnRow)
        assertTrue(Link.Replaced.speaksOnRow)
    }

    @Test
    fun `a session the app put aside is connecting as it comes back, and one out of races can't reach`() {
        assertEquals(Link.Connecting, linkOf(SessionState.Suspended, emptyList()))
        assertEquals(Link.Connecting, linkOf(SessionState.Suspended, listOf(entry(DiagnosticKind.RACE_FAILED))))
        val bound = listOf(entry(DiagnosticKind.RACE_FAILED), entry(DiagnosticKind.BOUND))
        assertEquals(Link.CannotReach, linkOf(SessionState.Suspended, bound))
    }
}
