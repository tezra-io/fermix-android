package io.tezra.fermix.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Design section 5.1: a `ping` after 25 s with nothing sent; two missed `pong`s and the link is lost. */
class KeepaliveTest {
    @Test
    fun `nothing is due while frames go out, and a ping is due after 25 s with nothing sent`() {
        val keepalive = Keepalive(startMs = 0)
        keepalive.sent(atMs = 20_000)
        assertEquals(KeepaliveAction.NONE, keepalive.poll(atMs = 25_000))
        assertEquals(45_000L, keepalive.nextCheckMs)
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 45_000))
    }

    @Test
    fun `a pong answers the ping, gives the latency, and the next ping is 25 s after the last frame sent`() {
        val keepalive = Keepalive(startMs = 0)
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 25_000))
        assertEquals(38L, keepalive.pong(atMs = 25_038))
        assertEquals(50_000L, keepalive.nextCheckMs)
        assertEquals(KeepaliveAction.NONE, keepalive.poll(atMs = 49_999))
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 50_000))
    }

    @Test
    fun `two pings in a row with no pong within 25 s each lose the link`() {
        val keepalive = Keepalive(startMs = 0)
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 25_000))
        assertEquals(50_000L, keepalive.nextCheckMs)
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 50_000))
        assertEquals(75_000L, keepalive.nextCheckMs)
        assertEquals(KeepaliveAction.LOST, keepalive.poll(atMs = 75_000))
    }

    @Test
    fun `one missed pong is forgiven by the next ping's pong in time`() {
        val keepalive = Keepalive(startMs = 0)
        keepalive.poll(atMs = 25_000)
        keepalive.poll(atMs = 50_000)
        assertEquals(25_010L, keepalive.pong(atMs = 50_010))
        assertEquals(12L, keepalive.pong(atMs = 50_012))
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 75_000))
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 100_000))
        assertEquals(KeepaliveAction.LOST, keepalive.poll(atMs = 125_000))
    }

    @Test
    fun `a pong after its ping's 25 s answers that ping, with its round trip, and forgives nothing`() {
        val keepalive = Keepalive(startMs = 0)
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 25_000))
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 50_000))
        assertEquals(26_000L, keepalive.pong(atMs = 51_000))
        assertEquals(KeepaliveAction.LOST, keepalive.poll(atMs = 75_000))
    }

    @Test
    fun `while the actor holds the reader no ping is missed and pings still go, and the wait starts after it`() {
        val keepalive = Keepalive(startMs = 0)
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 25_000))
        keepalive.stall(atMs = 30_000)
        keepalive.sent(atMs = 40_000)
        assertEquals(65_000L, keepalive.nextCheckMs)
        assertEquals(KeepaliveAction.NONE, keepalive.poll(atMs = 50_000))
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 65_000))
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 90_000))
        keepalive.unstall(atMs = 100_000)
        assertEquals(KeepaliveAction.NONE, keepalive.poll(atMs = 100_000))
        assertEquals(115_000L, keepalive.nextCheckMs)
        assertNull(keepalive.pong(atMs = 100_005))
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 115_000))
        assertEquals(KeepaliveAction.LOST, keepalive.poll(atMs = 125_000))
    }

    @Test
    fun `a hand-off the actor takes at once moves no ping and keeps its round trip`() {
        val keepalive = Keepalive(startMs = 0)
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 25_000))
        keepalive.stall(atMs = 25_010)
        keepalive.unstall(atMs = 25_010)
        assertEquals(30L, keepalive.pong(atMs = 25_030))
    }

    @Test
    fun `a pong that answers no ping counts for nothing`() {
        val keepalive = Keepalive(startMs = 0)
        assertNull(keepalive.pong(atMs = 3_000))
        assertEquals(25_000L, keepalive.nextCheckMs)
        assertEquals(KeepaliveAction.PING, keepalive.poll(atMs = 25_000))
    }
}
