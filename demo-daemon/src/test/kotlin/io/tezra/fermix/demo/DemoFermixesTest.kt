package io.tezra.fermix.demo

import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

// The demo's Fermixes, its links and its dialer, apart from any phone: what core-session's DemoDaemonTest
// drives end to end rests on these.

class DemoFermixesTest {
    @Test
    fun `each Fermix's link parses to its own route, pin and key, and the second shares the first's computer`() =
        runTest {
            val demo = DemoDaemon(DEMO_SEED, backgroundScope)
            val links = demo.fermixes.map { PairingLink.parse(it.link(window = 0)) }

            demo.fermixes.zip(links).forEach { (fermix, link) ->
                assertEquals(listOf(fermix.address), link.candidates)
                assertEquals(DEMO_PORT, link.port)
                assertArrayEquals(fermix.tlsFingerprint, link.tlsFingerprint)
                assertArrayEquals(fermix.gatewayKey.publicKey, link.gatewayPublicKey)
                assertEquals(fermix.host to fermix.profile, link.name to link.profile)
            }
            assertEquals(6, links.map { it.tlsFingerprint.toHexString() }.toSet().size)
            // The debug app tells the demo's pins from a real daemon's by these digests alone, no Fermix made.
            assertEquals(
                demo.fermixes.map { it.tlsFingerprint.toHexString() },
                demoPins(DEMO_SEED).map { it.toHexString() },
            )
            assertEquals(listOf("suj-mbp", "suj-mbp"), links.take(2).map { it.name })
            assertEquals(listOf("fermix", "fermix-dev"), links.take(2).map { it.profile })
        }

    @Test
    fun `a seed makes the same keys, pins and secrets every run, another seed others`() =
        runTest {
            val one = DemoDaemon(DEMO_SEED, backgroundScope).fermixes
            val two = DemoDaemon(DEMO_SEED, backgroundScope).fermixes
            val other = DemoDaemon(DEMO_SEED + 1, backgroundScope).fermixes

            assertEquals(one.map { it.link(window = 3) }, two.map { it.link(window = 3) })
            assertFalse(one.zip(other).any { (a, b) -> a.gatewayKey.publicKey.contentEquals(b.gatewayKey.publicKey) })
            assertFalse(one.first().secret(0).contentEquals(one.first().secret(1)), "each window has its own secret")
        }

    @Test
    fun `the next link opens a window on the first Fermix no phone paired with`() =
        runTest {
            val demo = DemoDaemon(DEMO_SEED, backgroundScope)
            val first = PairingLink.parse(demo.nextLink())
            val again = PairingLink.parse(demo.nextLink())

            assertArrayEquals(demo.fermixes.first().tlsFingerprint, first.tlsFingerprint)
            assertArrayEquals(first.tlsFingerprint, again.tlsFingerprint)
            assertFalse(first.secret.contentEquals(again.secret), "a new window, a new secret")
        }

    @Test
    fun `the dialer answers only a demo pin on the demo's port, and only at its Fermix's address`() =
        runTest {
            val demo = DemoDaemon(DEMO_SEED, backgroundScope)
            val fermix = demo.fermixes[2]

            assertNull(demo.dialerFor(DEMO_PORT, ByteArray(32) { 7 }))
            assertNull(demo.dialerFor(DEMO_PORT + 1, fermix.tlsFingerprint))
            val dialer = checkNotNull(demo.dialerFor(DEMO_PORT, fermix.tlsFingerprint))
            val elsewhere = Candidate(demo.fermixes[3].address, Candidate.Scope.TAILNET, Candidate.Kind.IP)
            assertThrows<TransportException.Unreachable> { dialer.dial(elsewhere) }
            val link = dialer.dial(fermix.route)
            assertFalse(link.closed.isCompleted, "the dial opened the demo's end")
            link.close(1000, "")
        }

    @Test
    fun `the demo's pictures decode as PNGs of their sizes, and its log is text`() {
        val blobs = demoBlobs()
        val sizes =
            listOf(DemoBlobName.SUNRISE, DemoBlobName.HARBOUR, DemoBlobName.PREVIEW).map { name ->
                val image = checkNotNull(ImageIO.read(ByteArrayInputStream(blobs.getValue(name).bytes())))
                image.width to image.height
            }

        assertEquals(listOf(640 to 427, 640 to 427, 240 to 160), sizes)
        val log = blobs.getValue(DemoBlobName.EXPORT_LOG)
        assertEquals("text/plain", log.mime)
        assertTrue(
            log
                .bytes()
                .decodeToString()
                .lines()
                .size > 1,
        )
        blobs.values.forEach { blob -> assertEquals(sha256(blob.bytes()).toHexString(), blob.ref) }
        assertEquals(blobs.values.map { it.ref }, demoBlobs().values.map { it.ref }, "the same bytes every run")
    }

    @Test
    fun `the demo refuses a scope that ended`() {
        val ended = CoroutineScope(Job().apply { cancel() } + StandardTestDispatcher())
        assertThrows<IllegalArgumentException> { DemoDaemon(DEMO_SEED, ended) }
    }
}
