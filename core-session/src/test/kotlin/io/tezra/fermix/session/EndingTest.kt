package io.tezra.fermix.session

import io.tezra.fermix.noise.NoiseException
import io.tezra.fermix.protocol.VersionDirection
import io.tezra.fermix.transport.TransportException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import javax.crypto.AEADBadTagException

/**
 * What the session does after a connection ends (design section 7, the "Close codes" row; PROTOCOL.md
 * "Close codes"; onboarding gotcha 9).
 */
class EndingTest {
    @TestFactory
    fun `the daemon's close codes`(): List<DynamicTest> =
        listOf(
            1000 to Next.ReconnectNow,
            1002 to Next.Backoff,
            1003 to Next.Backoff,
            1008 to Next.Backoff,
            1009 to Next.Backoff,
            1011 to Next.Backoff,
            4001 to Next.Stop(SessionState.Replaced),
            4003 to Next.Stop(SessionState.Revoked),
            4004 to Next.Stop(SessionState.Revoked),
            4999 to Next.Backoff,
        ).map { (code, next) ->
            dynamicTest("close $code") { assertEquals(next, nextAfter(closedBy(code, byDaemon = true))) }
        }

    @Test
    fun `a close this side sent is never read as the daemon's`() {
        listOf(1000, 1002, 4003, 4004).forEach { code ->
            assertEquals(Next.Backoff, nextAfter(closedBy(code, byDaemon = false)), "this side's $code")
        }
    }

    @Test
    fun `a socket that failed with no close frame is retried with backoff`() {
        val unreachable = Ending.Transport(TransportException.Unreachable(IOException("reset")))
        assertEquals(Next.Backoff, nextAfter(unreachable))
    }

    @Test
    fun `the session's own endings`() {
        assertEquals(Next.Backoff, nextAfter(Ending.ProtocolError("a seq gap")))
        assertEquals(Next.ReconnectNow, nextAfter(Ending.KeepaliveLost))
        assertEquals(Next.ReconnectNow, nextAfter(Ending.NetworkChanged))
        assertEquals(Next.ReconnectNow, nextAfter(Ending.LifetimeReached))
    }

    @Test
    fun `only a connection that was up reconnects at once, and a daemon's close needs it up for 5 s`() {
        val lifetime = closedBy(NORMAL_CLOSURE, byDaemon = true)
        assertEquals(Next.ReconnectNow, nextAfterConnection(lifetime, upMs = STABLE_CONNECTION_MS))
        assertEquals(Next.Backoff, nextAfterConnection(lifetime, upMs = STABLE_CONNECTION_MS - 1))
        assertEquals(Next.Backoff, nextAfterConnection(lifetime, upMs = null))
        assertEquals(Next.ReconnectNow, nextAfterConnection(Ending.NetworkChanged, upMs = 0))
        assertEquals(Next.Backoff, nextAfterConnection(Ending.KeepaliveLost, upMs = null))
        assertEquals(Next.Stop(SessionState.Revoked), nextAfterConnection(closedBy(4003, byDaemon = true), upMs = 0))
    }

    @Test
    fun `unsupported_protocol_version stops the session as an older or a newer daemon, with no reconnect`() {
        assertEquals(Next.Stop(SessionState.OlderDaemon), nextAfter(Ending.Refused(VersionDirection.CLIENT_TOO_NEW)))
        assertEquals(Next.Stop(SessionState.NewerDaemon), nextAfter(Ending.Refused(VersionDirection.CLIENT_TOO_OLD)))
    }

    @Test
    fun `a pin mismatch or an IK authentication failure is an identity change, never a revocation`() {
        val pin = TransportException.PinMismatch(IOException("another leaf"))
        val ik = NoiseException.AuthenticationFailed(AEADBadTagException("tag"))
        val revoked = TransportException.Closed(4004, "device not paired", byDaemon = true)
        assertEquals(Next.Stop(SessionState.IdentityChanged), nextAfter(Ending.Transport(pin)))
        assertEquals(Next.Stop(SessionState.IdentityChanged), nextAfterRace(listOf(unreachable(), ik, revoked)))
    }

    @Test
    fun `a race whose daemon closed with 4003 or 4004 is a revocation`() {
        listOf(4003, 4004).forEach { code ->
            val failures = listOf(unreachable(), TransportException.Closed(code, "", byDaemon = true))
            assertEquals(Next.Stop(SessionState.Revoked), nextAfterRace(failures))
        }
    }

    @Test
    fun `a race that failed for any other reason backs off`() {
        val failures = listOf(unreachable(), TransportException.Closed(1008, "", byDaemon = true), IOException("x"))
        assertEquals(Next.Backoff, nextAfterRace(failures))
    }

    private fun closedBy(
        code: Int,
        byDaemon: Boolean,
    ) = Ending.Transport(TransportException.Closed(code, "", byDaemon))

    private fun unreachable() = TransportException.Unreachable(IOException("no route"))
}
