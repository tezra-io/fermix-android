package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.MatchRange
import io.tezra.fermix.protocol.ModelEntry
import io.tezra.fermix.protocol.SearchHit
import io.tezra.fermix.protocol.ServerEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The search page of ProvisionalV2Test's SEARCH_RESULTS_V2, design section 7's shape, as a model. */
private val RESULTS =
    ServerEvent.SearchResults(
        PROFILE,
        "dentist",
        listOf(
            SearchHit(88uL, "user", "2026-09-24T09:15:00Z", "book the dentist for Friday", listOf(MatchRange(9, 7))),
        ),
        nextBeforeSeq = 88uL,
    )

/** ProvisionalV2Test's MODELS_V2 entries, a page of two: a model, and a provider the daemon could not list. */
private val OPUS = ModelEntry("anthropic", "claude-opus-5-5", "Claude Opus 5.5", "deep", false, true, true)
private val OLLAMA = ModelEntry("ollama", listingUnavailable = true)
private val GPT = ModelEntry("openai", "gpt-6.1", "GPT-6.1", streams = true, active = false, isDefault = false)

/**
 * The one-shot requests (Session.search, Session.pullModels): asked once of the connection that is up and
 * reconciled, never put in the outbox, answered in the order the daemon reads them, bounded by 30 s, and
 * given up with their caller; one the daemon refused without naming it lets the next go past its 30 s.
 */
class OneShotTest {
    @Test
    fun `a search goes as history_search and its page comes back`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val answer = async { harness.session.search("dentist") }
            assertEquals(
                ClientEvent.HistorySearch(PROFILE, "dentist", 20),
                connection.expect<ClientEvent.HistorySearch>(),
            )
            connection.send(RESULTS)
            assertEquals(OneShot.Answered(RESULTS), answer.await())
            assertTrue(harness.store.items.isEmpty(), "a search is never an outbox item")
        }

    @Test
    fun `an older page asks before its cursor`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val answer = async { harness.session.search("dentist", beforeSeq = 88uL) }
            assertEquals(88uL, connection.expect<ClientEvent.HistorySearch>().beforeSeq)
            connection.send(RESULTS.copy(hits = emptyList(), nextBeforeSeq = null))
            assertEquals(OneShot.Answered(RESULTS.copy(hits = emptyList(), nextBeforeSeq = null)), answer.await())
        }

    @Test
    fun `no connection is offline, and nothing waits for the next one`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            val connection = harness.daemon.accept()
            assertEquals(OneShot.Offline, harness.session.search("dentist"))
            assertEquals(OneShot.Offline, harness.session.pullModels())
            connection.connect()
            harness.settle()
            assertTrue(
                connection.link.toDaemon
                    .tryReceive()
                    .isFailure,
                "nothing was queued for the connection",
            )
        }

    @Test
    fun `no answer in 30 s is timed out`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val others = mutableListOf<ClientEvent>()
            backgroundScope.launch { connection.answerPings(backgroundScope, afterMs = 10, others) }
            val answer = async { harness.session.search("dentist") }
            delay(ANSWER_TIMEOUT_MS - 1)
            assertTrue(answer.isActive)
            delay(2)
            assertEquals(OneShot.TimedOut, answer.await())
        }

    @Test
    fun `an error naming nothing is the app's, never taken for a waiting search's`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val first = async { harness.session.search("dentist") }
            connection.expect<ClientEvent.HistorySearch>()
            val second = async { harness.session.search("friday") }
            connection.expect<ClientEvent.HistorySearch>()
            // A msg's refusal, which PROTOCOL.md's "Errors" sends with no context.
            connection.send(ServerEvent.Error("request_backlog_full", "32 requests already waiting"))
            connection.send(RESULTS.copy(hits = emptyList()))
            connection.send(RESULTS.copy(query = "friday", hits = emptyList()))
            assertEquals(OneShot.Answered(RESULTS.copy(hits = emptyList())), first.await())
            assertEquals(OneShot.Answered(RESULTS.copy(query = "friday", hits = emptyList())), second.await())
            assertTrue(harness.events.any { it is SessionEvent.Refused }, "the app heard the msg's refusal")
        }

    @Test
    fun `a search the daemon refused with an error naming nothing times out, and the app hears the error`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val others = mutableListOf<ClientEvent>()
            backgroundScope.launch { connection.answerPings(backgroundScope, afterMs = 10, others) }
            val answer = async { harness.session.search("dentist") }
            harness.settle()
            connection.send(ServerEvent.Error("invalid_field", "query"))
            delay(ANSWER_TIMEOUT_MS + 1)
            assertEquals(OneShot.TimedOut, answer.await())
            assertTrue(harness.events.any { it is SessionEvent.Refused })
        }

    @Test
    fun `a search the daemon refused, asked again once it timed out, gets its own page`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val others = mutableListOf<ClientEvent>()
            backgroundScope.launch { connection.answerPings(backgroundScope, afterMs = 10, others) }
            val refused = async { harness.session.search("dentist") }
            harness.settle()
            connection.send(ServerEvent.Error("request_failed", "the store failed"))
            delay(ANSWER_TIMEOUT_MS + 1)
            assertEquals(OneShot.TimedOut, refused.await())
            // "Try again": the same query, whose page no longer goes to the search the daemon refused.
            val again = async { harness.session.search("dentist") }
            harness.settle()
            connection.send(RESULTS)
            assertEquals(OneShot.Answered(RESULTS), again.await())
        }

    @Test
    fun `a pull the daemon refused, once it timed out, leaves the next pull its own models`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val others = mutableListOf<ClientEvent>()
            backgroundScope.launch { connection.answerPings(backgroundScope, afterMs = 10, others) }
            val refused = async { harness.session.pullModels() }
            harness.settle()
            connection.send(ServerEvent.Error("request_failed", "the registry failed"))
            delay(ANSWER_TIMEOUT_MS + 1)
            assertEquals(OneShot.TimedOut, refused.await())
            val again = async { harness.session.pullModels() }
            harness.settle()
            connection.send(ServerEvent.Models(listOf(GPT)))
            assertEquals(OneShot.Answered(listOf(GPT)), again.await())
        }

    @Test
    fun `eight searches the daemon refused leave the ninth asked, not busy`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val others = mutableListOf<ClientEvent>()
            backgroundScope.launch { connection.answerPings(backgroundScope, afterMs = 10, others) }
            val refused = List(MAX_ASKED) { async { harness.session.search("dentist") } }
            harness.settle()
            repeat(MAX_ASKED) { connection.send(ServerEvent.Error("request_failed", "the store failed")) }
            delay(ANSWER_TIMEOUT_MS + 1)
            refused.forEach { assertEquals(OneShot.TimedOut, it.await()) }
            val ninth = async { harness.session.search("friday") }
            harness.settle()
            connection.send(RESULTS.copy(query = "friday", hits = emptyList()))
            assertEquals(OneShot.Answered(RESULTS.copy(query = "friday", hits = emptyList())), ninth.await())
        }

    @Test
    fun `a search given up within its 30 s takes its own page, so the same query's next search gets its`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val first = async { harness.session.search("dentist") }
            connection.expect<ClientEvent.HistorySearch>()
            first.cancel()
            val older = async { harness.session.search("dentist", beforeSeq = 88uL) }
            connection.expect<ClientEvent.HistorySearch>()
            val olderPage = RESULTS.copy(hits = emptyList(), nextBeforeSeq = null)
            connection.send(RESULTS)
            connection.send(olderPage)
            assertEquals(OneShot.Answered(olderPage), older.await())
        }

    @Test
    fun `each page goes to the search of its query, whatever order they come in`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val first = async { harness.session.search("dentist") }
            connection.expect<ClientEvent.HistorySearch>()
            val second = async { harness.session.search("friday") }
            connection.expect<ClientEvent.HistorySearch>()
            connection.send(RESULTS.copy(query = "friday", hits = emptyList()))
            connection.send(RESULTS)
            assertEquals(OneShot.Answered(RESULTS), first.await())
            assertEquals(OneShot.Answered(RESULTS.copy(query = "friday", hits = emptyList())), second.await())
        }

    @Test
    fun `a hit on an approval's answer is never in the page, by the daemon's routes or a card's`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val card = vendoredServer("approval") as ServerEvent.Approval
            val learned = card.copy(token = "cap-token", approveCommand = "/grant cap-token")
            connection.send(learned.copy(denyCommand = "/refuse cap-token"))
            harness.settle()
            val answer = async { harness.session.search("token") }
            connection.expect<ClientEvent.HistorySearch>()
            val words = listOf("/confirm opaque-token", "…soul apply opaque-token", "grant cap-token")
            val answers =
                words.mapIndexed { i, excerpt -> SearchHit(40uL + i.toULong(), "user", TS, excerpt, emptyList()) }
            val kept = SearchHit(30uL, "assistant", TS, "/confirm opaque-token is what you type", emptyList())
            connection.send(RESULTS.copy(query = "token", hits = answers + kept))
            assertEquals(OneShot.Answered(RESULTS.copy(query = "token", hits = listOf(kept))), answer.await())
        }

    @Test
    fun `an owner's own words that start with a route's word are found`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val answer = async { harness.session.search("dentist") }
            connection.expect<ClientEvent.HistorySearch>()
            // The engine's excerpts are 16 tokens, cut with "…", so one may start mid-sentence.
            val words = listOf("…confirm the dentist booking for Friday", "deny the dentist's invoice, it is wrong")
            val owners =
                words.mapIndexed { i, excerpt -> SearchHit(50uL + i.toULong(), "user", TS, excerpt, emptyList()) }
            connection.send(RESULTS.copy(hits = owners))
            assertEquals(OneShot.Answered(RESULTS.copy(hits = owners)), answer.await())
        }

    @Test
    fun `the connection ending first interrupts it`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val answer = async { harness.session.search("dentist") }
            connection.expect<ClientEvent.HistorySearch>()
            connection.close(NORMAL_CLOSURE, LIFETIME_REASON)
            assertEquals(OneShot.Interrupted, answer.await())
        }

    @Test
    fun `a caller that gave up keeps its place, so the next search gets its own page`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val first = async { harness.session.search("dentist") }
            connection.expect<ClientEvent.HistorySearch>()
            first.cancel()
            val second = async { harness.session.search("friday") }
            connection.expect<ClientEvent.HistorySearch>()
            connection.send(RESULTS)
            connection.send(RESULTS.copy(query = "friday", hits = emptyList()))
            assertEquals(OneShot.Answered(RESULTS.copy(query = "friday", hits = emptyList())), second.await())
        }

    @Test
    fun `past eight waiting a search is busy and not sent`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            repeat(MAX_ASKED) { launch { harness.session.search("dentist") } }
            harness.settle()
            assertEquals(OneShot.Busy, harness.session.search("dentist"))
            repeat(MAX_ASKED) { connection.expect<ClientEvent.HistorySearch>() }
            harness.settle()
            assertTrue(
                connection.link.toDaemon
                    .tryReceive()
                    .isFailure,
            )
            repeat(MAX_ASKED) { connection.send(RESULTS) }
        }

    @Test
    fun `a page no search waits for is dropped, the search waiting answered still, and no diagnostic quotes it`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val answer = async { harness.session.search("my secret words") }
            connection.expect<ClientEvent.HistorySearch>()
            connection.send(RESULTS.copy(query = "my other secret"))
            connection.send(RESULTS.copy(query = "my secret words"))
            assertEquals(OneShot.Answered(RESULTS.copy(query = "my secret words")), answer.await())
            assertFalse(connection.link.phoneClose.isCompleted, "the phone closed its connection")
            assertTrue(harness.diagnostics().any { it.kind == DiagnosticKind.BOUND })
            assertTrue(harness.diagnostics().none { "secret" in it.detail })
        }

    @Test
    fun `a caller past its replies' bound hears why, and none more are held for it`() {
        val waiter = Waiter(Asking.MEDIA, "a".repeat(64))
        repeat(MAX_QUEUED_REPLIES + 1) { waiter.deliver(Reply.Progress) }
        assertTrue(waiter.overrun != null, "the overrun was not told")
        repeat(MAX_QUEUED_REPLIES) { assertEquals(Reply.Progress, waiter.replies.tryReceive().getOrNull()) }
        assertTrue(waiter.replies.tryReceive().isClosed, "more was held than the bound")
    }

    @Test
    fun `a blank query or one past 256 scalar values is refused before anything goes`() =
        runTest {
            val harness = Harness(this)
            harness.connect()
            assertThrows<IllegalArgumentException> { harness.session.search("  ") }
            assertThrows<IllegalArgumentException> { harness.session.search("😀".repeat(MAX_QUERY_SCALARS + 1)) }
            assertEquals(
                OneShot.Offline,
                Harness(this).also { it.open() }.session.search("😀".repeat(MAX_QUERY_SCALARS)),
            )
        }

    @Test
    fun `every models page of one pull is assembled, until one says no next`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val answer = async { harness.session.pullModels() }
            assertEquals(ClientEvent.ModelsPull, connection.expect<ClientEvent.ModelsPull>())
            connection.send(ServerEvent.Models(listOf(OPUS, OLLAMA), next = true))
            connection.send(ServerEvent.Models(listOf(GPT)))
            assertEquals(OneShot.Answered(listOf(OPUS, OLLAMA, GPT)), answer.await())
            assertTrue(harness.events.none { it is SessionEvent.Models })
        }

    @Test
    fun `a models answer no pull asked for, a slash model's, goes to the app`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            connection.send(ServerEvent.Models(listOf(OPUS), next = false))
            harness.settle()
            assertTrue(SessionEvent.Models(listOf(OPUS), next = false) in harness.events)
        }

    @Test
    fun `past 64 pages the pull ends with what came, and says so`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            val answer = async { harness.session.pullModels() }
            connection.expect<ClientEvent.ModelsPull>()
            repeat(MAX_MODEL_PAGES + 2) { connection.send(ServerEvent.Models(listOf(GPT), next = true)) }
            assertEquals(OneShot.Answered(List(MAX_MODEL_PAGES) { GPT }), answer.await())
            connection.send(ServerEvent.Models(listOf(GPT), next = false))
            harness.settle()
            assertTrue(harness.diagnostics().any { it.kind == DiagnosticKind.BOUND })
            assertTrue(harness.events.none { it is SessionEvent.Models }, "the rest of that answer was dropped")
        }
}
