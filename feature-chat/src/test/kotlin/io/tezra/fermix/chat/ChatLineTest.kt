package io.tezra.fermix.chat

import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The chat's subtitle and its banner (design section 13.5; onboarding gotcha 20). */
class ChatLineTest {
    private val up = Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true)
    private val network =
        NetworkFacts(1L, defaultHasVpn = false, defaultHasCgnatAddress = false, otherUidVpnPresent = false)

    @Test
    fun `a chat's subtitle puts the link's trouble first, then thinking, then the path`() {
        assertEquals(ChatLine.Of(Link.Connecting), chatLine(Link.Connecting, thinking = true))
        val updating = up.copy(caughtUp = false)
        assertEquals(ChatLine.Of(updating), chatLine(updating, thinking = true))
        assertEquals(ChatLine.Thinking, chatLine(up, thinking = true))
        assertEquals(ChatLine.Of(up), chatLine(up, thinking = false))
        assertEquals(ChatLine.Nothing, chatLine(Link.NotOpen, thinking = false))
        assertEquals(ChatLine.Of(Link.ProtocolError), chatLine(Link.ProtocolError, thinking = false))
    }

    @Test
    fun `the banner says offline without a network and can't reach with one, and a handshake clears it`() {
        assertEquals(Banner.OFFLINE, bannerOf(Link.WaitingForNetwork, NetworkFacts.NONE))
        assertEquals(Banner.OFFLINE, bannerOf(Link.Connecting, NetworkFacts.NONE))
        assertEquals(Banner.UNREACHABLE, bannerOf(Link.CannotReach, network))
        assertNull(bannerOf(Link.Connecting, network))
        assertNull(bannerOf(up, network))
    }
}
