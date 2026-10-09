package io.tezra.fermix.demo

import io.tezra.fermix.noise.InitiatorHandshake
import io.tezra.fermix.noise.NoiseSession
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.MatchRange
import io.tezra.fermix.protocol.SearchHit
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.decodeServerEvent
import io.tezra.fermix.protocol.encodeClientEvent
import io.tezra.fermix.session.Link
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// What the demo writes from the owner's words and its own rows, apart from any phone: a quote and the deltas that
// never part a surrogate pair, a search matched as the engine's index matches and its excerpt cut at words' edges,
// words that name no time of day, and a socket that says nothing, or nothing a daemon reads, closed as the engine
// closes it: before its handshake, and after it a frame out of its seq, of another version or under another key.

/** The owner's emoji, two UTF-16 units: a cut between them is a lone surrogate, which UTF-8 writes as '?'. */
private const val GRIN = "😀"

/** The reason the demo closes `1002` with on a frame it will not take (DemoConnection's refusal). */
private const val REFUSED = "mobile protocol error"

/** A time of the clock, a part of the day or a weekday: words a row's own time beside them could contradict. */
private val TIME_OF_DAY =
    Regex(
        "\\b\\d{1,2}:\\d{2}\\b|\\b(overnight|tonight|morning|afternoon|evening|midnight|noon|yesterday|" +
            "monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        RegexOption.IGNORE_CASE,
    )

class DemoAnswersTest {
    @Test
    fun `a quote and the deltas never part a surrogate pair`() {
        val words = "a".repeat(79) + GRIN + " and more"

        val quote = quoteOf(words)
        assertEquals("a".repeat(79), quote, "the cut backs off the pair it would part")
        assertEquals("short $GRIN", quoteOf("short $GRIN"), "words that fit are quoted whole")
        val pieces = piecesOf("$GRIN$GRIN$GRIN", 1)
        assertEquals(listOf(GRIN, GRIN, GRIN), pieces)
        assertTrue(pieces.none { piece -> piece.last().isHighSurrogate() || piece.first().isLowSurrogate() })
        assertEquals("x$GRIN y", piecesOf("x$GRIN y", 2).joinToString(""))
    }

    @Test
    fun `a search's excerpt is cut at words' edges, each cut marked, its range shifted past the mark`() {
        val words =
            "The export ran at night and timed out at sixty seconds, the job's default; " +
                "it now reads in pages and writes each one as it goes, so a large table never waits on one call, " +
                "and the next run, which starts at two, finishes well within the new limit of two minutes."
        val row = HistoryMessage(1uL, AGENT, words, "2026-09-27T09:30:00Z", emptyList())

        val hit = checkNotNull(hitOf(row, "pages"))

        assertTrue(hit.excerpt.startsWith("…"), hit.excerpt)
        assertTrue(hit.excerpt.endsWith("…"), hit.excerpt)
        val inner = hit.excerpt.removePrefix("…").removeSuffix("…")
        assertTrue(words.contains(inner), "the excerpt is the row's own words")
        val at = words.indexOf(inner)
        assertTrue(at == 0 || words[at - 1] == ' ', "it starts on a word: $inner")
        val end = at + inner.length
        assertTrue(end == words.length || words[end] == ' ', "it ends on a word: $inner")
        val range = hit.ranges.single()
        assertEquals("pages", hit.excerpt.substring(range.start, range.start + range.length))
        assertEquals(MatchRange(range.start, 5), range)
        val whole = checkNotNull(hitOf(row.copy(content = "Short pages."), "pages"))
        assertEquals("Short pages.", whole.excerpt, "a row that fits is not cut")
    }

    @Test
    fun `a search matches as the engine's index does, each word at a word's start, every match ranged`() {
        val words = "Restart the export service so it picks up the new timeout."
        val row = HistoryMessage(1uL, OWNER, words, TS, emptyList())

        assertEquals(listOf("export", "timeout"), rangedOf(checkNotNull(hitOf(row, " export  TIMEOUT "))))
        assertEquals(listOf("export"), rangedOf(checkNotNull(hitOf(row, "exp"))), "a prefix ranges its whole word")
        assertEquals(listOf("picks up"), rangedOf(checkNotNull(hitOf(row, "picks-up"))), "a token's words, a phrase")
        assertNull(hitOf(row, "port"), "a match starts at a word's start")
        assertNull(hitOf(row, "export lunch"), "every word of the query matches")
        assertNull(hitOf(row, "-- …"), "a query with no letter or digit matches nothing")
        val accented = row.copy(content = "Le café est fermé.")
        assertEquals(listOf("café"), rangedOf(checkNotNull(hitOf(accented, "CAFE"))), "case and accents folded")
    }

    @Test
    fun `no word the demo writes names a time of day that the row's own time could contradict`() {
        val scripted =
            demoFermixes(DEMO_SEED).flatMap { fermix ->
                val script = fermix.script
                script.rows.map { it.text } + script.longTurn?.let { it.headings + it.answer }.orEmpty() +
                    listOfNotNull(script.approval?.text, script.approval?.approved)
            }
        val log = demoBlobs().getValue(DemoBlobName.EXPORT_LOG).bytes().decodeToString()
        val written = scripted + answers("the owner's words") + log.lines()

        assertEquals(emptyList<String>(), written.filter { TIME_OF_DAY.containsMatchIn(it) })
    }

    @Test
    fun `a socket that says nothing within the handshake deadline is closed 1008, and one that is no handshake 1002`() =
        runTest {
            val demo = DemoDaemon(DEMO_SEED, backgroundScope)
            val fermix = demo.fermixes.first()
            val dialer = checkNotNull(demo.dialerFor(DEMO_PORT, fermix.tlsFingerprint))

            val quiet = dialer.dial(fermix.route)
            val deadline = quiet.closed.await() as TransportException.Closed
            assertEquals(1008 to true, deadline.code to deadline.byDaemon)

            val stray = dialer.dial(fermix.route)
            assertTrue(stray.send("FXM1".encodeToByteArray() + byteArrayOf(9)))
            val refused = stray.closed.await() as TransportException.Closed
            assertEquals(1002, refused.code)
            assertFalse(refused.reason.isEmpty())
        }

    @Test
    fun `a frame after hello whose seq is not the next one is closed 1002, with no error`() =
        runTest {
            val demo = DemoDaemon(DEMO_SEED, backgroundScope)
            val fermix = demo.fermixes[2]
            val (link, noise) = pairedSocket(demo, fermix)

            assertTrue(link.send(noise.encrypt(encodeClientEvent(2, 1uL, helloOf(fermix)))))
            assertTrue(link.send(noise.encrypt(encodeClientEvent(2, 3uL, ClientEvent.Ping))))

            assertRefusedSilently(link, noise, REFUSED)
        }

    @Test
    fun `a frame of another version than the session's is closed 1002, with no error`() =
        runTest {
            val demo = DemoDaemon(DEMO_SEED, backgroundScope)
            val fermix = demo.fermixes[2]
            val (link, noise) = pairedSocket(demo, fermix)

            assertTrue(link.send(noise.encrypt(encodeClientEvent(2, 1uL, helloOf(fermix)))))
            assertTrue(link.send(noise.encrypt(encodeClientEvent(1, 2uL, ClientEvent.Ping))))

            assertRefusedSilently(link, noise, REFUSED)
        }

    @Test
    fun `a frame sealed under another session's key is closed 1002, with no error`() =
        runTest {
            val demo = DemoDaemon(DEMO_SEED, backgroundScope)
            val fermix = demo.fermixes[2]
            val (link, noise) = pairedSocket(demo, fermix)
            val (_, other) = pairedSocket(demo, fermix)

            assertTrue(link.send(noise.encrypt(encodeClientEvent(2, 1uL, helloOf(fermix)))))
            assertTrue(link.send(other.encrypt(encodeClientEvent(2, 2uL, ClientEvent.Ping))))

            assertRefusedSilently(link, noise, "$REFUSED: AEADBadTagException")
        }
}

/** A row's time in the search tests. */
private const val TS = "2026-09-27T09:30:00Z"

/** The words of [hit]'s excerpt that each of its ranges marks, the ranges counted in Unicode scalar values. */
private fun rangedOf(hit: SearchHit): List<String> =
    hit.ranges.map { range ->
        val from = hit.excerpt.offsetByCodePoints(0, range.start)
        hit.excerpt.substring(from, hit.excerpt.offsetByCodePoints(from, range.length))
    }

/** A paired phone's `hello` to [fermix], as the first session after its pairing says it. */
private fun helloOf(fermix: DemoFermix): ClientEvent.Hello =
    ClientEvent.Hello("demo-${fermix.index}-1", "0.1.0", 0uL, DAEMON_VERSION, lastMutationSeq = 0uL)

/**
 * A paired phone's socket to [fermix] of [demo] with its IK handshake done, as core-session's Connector runs it: the
 * link, and the phone's Noise session over it.
 */
private suspend fun pairedSocket(
    demo: DemoDaemon,
    fermix: DemoFermix,
): Pair<Link, NoiseSession> {
    val link = checkNotNull(demo.dialerFor(DEMO_PORT, fermix.tlsFingerprint)).dial(fermix.route)
    val handshake = InitiatorHandshake.ik(SoftwareKey.generate(), fermix.gatewayKey.publicKey)
    check(link.send(handshake.writeFirstMessage(ByteArray(0)))) { "message 1 went" }
    return link to handshake.readSecondMessage(link.incoming.receive()).session
}

/**
 * [link] closed by the demo `1002` for [reason], the refusal's own and never the idle bound's, after its `hello` was
 * answered and with no `error` among what the demo sent: a daemon closes on such a frame and says nothing of it.
 */
private suspend fun assertRefusedSilently(
    link: Link,
    noise: NoiseSession,
    reason: String,
) {
    val closed = link.closed.await() as TransportException.Closed
    assertEquals(Triple(1002, reason, true), Triple(closed.code, closed.reason, closed.byDaemon))
    val heard =
        generateSequence { link.incoming.tryReceive().getOrNull() }
            .map { decodeServerEvent(noise.decrypt(it)).event }
            .toList()
    assertTrue(heard.any { it is ServerEvent.HelloAck }, "the hello before it was answered: $heard")
    assertFalse(heard.any { it is ServerEvent.Error }, "no error: $heard")
}
