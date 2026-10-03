package io.tezra.fermix.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MatchRange
import io.tezra.fermix.protocol.MediaRef
import io.tezra.fermix.protocol.SearchHit
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.session.APPROVAL_ANSWER_PREFIX
import io.tezra.fermix.session.MAX_QUERY_SCALARS
import io.tezra.fermix.session.OneShot
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.withLinkPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [row] with [ref] attached. */
private fun withMedia(
    row: TimelineRow,
    ref: MediaRef,
): TimelineRow = (row as TimelineRow.Message).let { it.copy(message = it.message.copy(mediaRefs = listOf(ref))) }

/** The daemon's hit on row [seq], "timeout" marked in its excerpt. */
private fun hit(seq: Int) =
    SearchHit(seq.toULong(), "assistant", at(seq.toLong()), "raised the timeout to 300 s", listOf(MatchRange(11, 7)))

/**
 * Search (design section 13.7, D24): 300 ms after the owner stops typing, the daemon's index while a connection
 * is up and the daemon has `caps.search`, 20 hits a page and the older page as the list ends; otherwise the
 * phone's own index under the pinned line, never a search queued for later; the chips over cached rows only;
 * a hit picked opens the chat at it, stepped through with ▲ and ▼.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchTest {
    private class Rig(
        scope: TestScope,
        rows: List<TimelineRow> = emptyList(),
        var searches: Boolean = true,
    ) {
        val store = FakeChatStore(rows)
        val session = FakeChatSession(store)
        val jumps = mutableListOf<ULong>()
        val log = FakeLog()
        val search =
            ChatSearch(MutableStateFlow(session), store, scope.backgroundScope, { searches }, { jumps += it }, log.log)

        /** The query typed, and the debounce run out. */
        fun TestScope.typed(query: String) {
            search.query(query)
            advanceTimeBy(SEARCH_DEBOUNCE_MS)
            runCurrent()
        }
    }

    @Test
    fun `the search runs 300 ms after the owner stops typing, once`() =
        runTest {
            val rig = Rig(this)
            rig.search.open()
            rig.search.query("time")
            advanceTimeBy(SEARCH_DEBOUNCE_MS - 100)
            rig.search.query("timeout")
            advanceTimeBy(SEARCH_DEBOUNCE_MS - 1)
            runCurrent()
            assertEquals(emptyList<Pair<String, ULong?>>(), rig.session.searches.value)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf<Pair<String, ULong?>>("timeout" to null), rig.session.searches.value)
        }

    @Test
    fun `connected with caps search, the daemon's pages come, the older one as the list ends`() =
        runTest {
            val rig = Rig(this)
            rig.session.searchPage = { query, before ->
                val page = if (before == null) listOf(hit(9), hit(7)) else listOf(hit(3))
                ServerEvent.SearchResults(PROFILE, query, page, nextBeforeSeq = if (before == null) 7uL else null)
            }
            rig.search.open()
            rig.search.query("timeout")
            advanceTimeBy(SEARCH_DEBOUNCE_MS)
            runCurrent()
            val first = checkNotNull(rig.search.state.value)
            assertFalse(first.cachedOnly)
            assertTrue(first.more)
            assertEquals(listOf(9uL, 7uL), first.hits.map { it.serverSeq })
            assertEquals(listOf(11 until 18), first.hits.first().marks)
            rig.search.more()
            runCurrent()
            assertEquals(
                listOf(9uL, 7uL, 3uL),
                rig.search.state.value
                    ?.hits
                    ?.map { it.serverSeq },
            )
            assertEquals(listOf<Pair<String, ULong?>>("timeout" to null, "timeout" to 7uL), rig.session.searches.value)
            assertFalse(checkNotNull(rig.search.state.value).more)
        }

    @Test
    fun `offline or without the cap it is the cache's, under the pinned line, and nothing waits to be sent`() =
        runTest {
            val rows = listOf(agentRow(2, "raised the timeout"), agentRow(1, "nothing here"))
            val rig = Rig(this, rows)
            rig.session.connected.value = false
            rig.search.open()
            rig.search.query("timeout")
            advanceTimeBy(SEARCH_DEBOUNCE_MS)
            runCurrent()
            val offline = checkNotNull(rig.search.state.value)
            assertTrue(offline.cachedOnly)
            assertEquals(listOf(2uL), offline.hits.map { it.serverSeq })
            assertTrue(
                rig.store.outbox.value
                    .isEmpty(),
                "a search was queued",
            )
            assertTrue(
                rig.session.sent.value
                    .isEmpty(),
                "a search was sent",
            )
            rig.session.connected.value = true
            rig.searches = false
            rig.search.query("timeout ")
            advanceTimeBy(SEARCH_DEBOUNCE_MS)
            runCurrent()
            assertTrue(checkNotNull(rig.search.state.value).cachedOnly)
            assertTrue(
                rig.session.searches.value
                    .isEmpty(),
                "a daemon without caps.search was asked",
            )
        }

    @Test
    fun `the chips filter cached rows, and an approval's answer is never a hit`() =
        runTest {
            val image = MediaRef("aa".repeat(32), "image", "image/png", 10L)
            val doc = MediaRef("bb".repeat(32), "document", "application/pdf", 10L)
            val picture = withMedia(agentRow(4, "the report chart"), image)
            val file = withMedia(agentRow(3, "the report file"), doc)
            val card = LinkPreviewCard("https://hexdocs.pm/elixir/Task.html", "hexdocs.pm", "Task — Elixir")
            val link = withLinkPreview(agentRow(2, "the report guide"), card)
            val answer = userRow(5, "/confirm the report token", clientMsgId = "${APPROVAL_ANSWER_PREFIX}ap-1:00")
            val rig = Rig(this, listOf(answer, picture, file, link), searches = false)
            rig.search.open()
            rig.search.query("report")
            advanceTimeBy(SEARCH_DEBOUNCE_MS)
            runCurrent()
            assertEquals(
                listOf(4uL, 3uL, 2uL),
                rig.search.state.value
                    ?.hits
                    ?.map { it.serverSeq },
            )
            val chips =
                mapOf(
                    SearchChip.MEDIA to listOf(4uL),
                    SearchChip.FILES to listOf(3uL),
                    SearchChip.LINKS to listOf(2uL),
                )
            chips.forEach { (chip, seqs) ->
                rig.search.chip(chip)
                runCurrent()
                assertEquals(
                    seqs,
                    rig.search.state.value
                        ?.hits
                        ?.map { it.serverSeq },
                    "$chip",
                )
            }
        }

    @Test
    fun `a hit picked opens the chat at it, and the steps walk older and newer`() =
        runTest {
            val rig = Rig(this)
            rig.session.searchPage =
                { query, _ -> ServerEvent.SearchResults(PROFILE, query, listOf(hit(9), hit(7), hit(5))) }
            rig.search.open()
            rig.search.query("timeout")
            advanceTimeBy(SEARCH_DEBOUNCE_MS)
            runCurrent()
            rig.search.pick(1)
            assertEquals(
                SearchMode.IN_CHAT,
                rig.search.state.value
                    ?.mode,
            )
            rig.search.step(older = true)
            rig.search.step(older = true)
            rig.search.step(older = false)
            assertEquals(listOf(7uL, 5uL, 7uL), rig.jumps)
            rig.search.back()
            assertEquals(
                SearchMode.LIST,
                rig.search.state.value
                    ?.mode,
            )
            rig.search.back()
            assertNull(rig.search.state.value)
        }

    @Test
    fun `a first page that does not come while the daemon is up says so, never by its words, and comes again`() =
        runTest {
            listOf(OneShot.Refused("internal_error"), OneShot.TimedOut, OneShot.Busy).forEach { ends ->
                val rig = Rig(this, listOf(agentRow(2, "raised the timeout")))
                rig.session.searchFails = ends
                rig.search.open()
                with(rig) { typed("timeout") }
                val failed = checkNotNull(rig.search.state.value)
                assertTrue(failed.failed, "$ends")
                assertFalse(failed.cachedOnly, "$ends: the cache's hits stood in for the daemon's")
                assertEquals(emptyList<ShownHit>(), failed.hits)
                assertFalse(failed.searching)
                assertEquals(listOf("A search page did not come: ${ends::class.simpleName}"), rig.log.lines.value)
                rig.session.searchFails = null
                rig.session.searchPage = { query, _ -> ServerEvent.SearchResults(PROFILE, query, listOf(hit(9))) }
                rig.search.retry()
                runCurrent()
                val again = checkNotNull(rig.search.state.value)
                assertFalse(again.failed)
                assertEquals(listOf(9uL), again.hits.map { it.serverSeq })
            }
        }

    @Test
    fun `an older page that does not come keeps the hits held, says so, and comes again`() =
        runTest {
            val rig = Rig(this)
            rig.session.searchPage = { query, before ->
                val page = if (before == null) listOf(hit(9)) else listOf(hit(3))
                ServerEvent.SearchResults(PROFILE, query, page, nextBeforeSeq = if (before == null) 9uL else null)
            }
            rig.search.open()
            with(rig) { typed("timeout") }
            rig.session.searchFails = OneShot.TimedOut
            rig.search.more()
            runCurrent()
            val failed = checkNotNull(rig.search.state.value)
            assertTrue(failed.failed)
            assertEquals(listOf(9uL), failed.hits.map { it.serverSeq })
            rig.search.more()
            runCurrent()
            assertEquals(2, rig.session.searches.value.size, "an older page was asked for past the failed one")
            rig.session.searchFails = null
            rig.search.retry()
            runCurrent()
            assertEquals(listOf(9uL, 3uL), checkNotNull(rig.search.state.value).hits.map { it.serverSeq })
            assertEquals(listOf("A search page did not come: TimedOut"), rig.log.lines.value)
        }

    @Test
    fun `a query past what a search takes is cut to it, and searches`() =
        runTest {
            val rig = Rig(this)
            rig.search.open()
            with(rig) { typed("😀".repeat(MAX_QUERY_SCALARS + 44)) }
            val asked =
                rig.session.searches.value
                    .single()
                    .first
            assertEquals(MAX_QUERY_SCALARS, asked.codePointCount(0, asked.length))
            assertEquals(
                asked,
                rig.search.state.value
                    ?.query,
            )
            assertFalse(checkNotNull(rig.search.state.value).failed)
        }

    @Test
    fun `a daemon's hit on a row the cache holds as an approval's answer is never shown`() =
        runTest {
            val answer = userRow(9, "", clientMsgId = "${APPROVAL_ANSWER_PREFIX}ap-1:00")
            val rig = Rig(this, listOf(answer, agentRow(7, "raised the timeout")))
            rig.session.searchPage = { query, _ -> ServerEvent.SearchResults(PROFILE, query, listOf(hit(9), hit(7))) }
            rig.search.open()
            with(rig) { typed("timeout") }
            assertEquals(listOf(7uL), checkNotNull(rig.search.state.value).hits.map { it.serverSeq })
        }

    @Test
    fun `stepping through the chat marks the query's words in the hit's row, and nothing in the list`() {
        val stepping =
            SearchUi(query = " raised  Timeout ", mode = SearchMode.IN_CHAT, hits = listOf(hitOf(hit(9))), step = 0)
        assertEquals(InChatMarks(9uL, listOf("raised", "Timeout")), inChatMarksOf(stepping))
        assertNull(inChatMarksOf(stepping.copy(mode = SearchMode.LIST)))
        assertNull(inChatMarksOf(stepping.copy(step = 1)), "no hit at the step")
        assertNull(inChatMarksOf(stepping.copy(query = "  ")))
        val wash = SpanStyle(background = Color.Yellow)
        val marked = withMarks(AnnotatedString("I raised the timeout, the TIMEOUT."), listOf("timeout"), wash)
        assertEquals(listOf(13 until 20, 26 until 33), marked.spanStyles.map { it.start until it.end })
        assertEquals("I raised the timeout, the TIMEOUT.", marked.text)
    }

    @Test
    fun `a daemon's ranges count scalar values, so an emoji before a match moves it by two chars`() {
        val shown = hitOf(SearchHit(1uL, "user", at(0), "👀 timeout", listOf(MatchRange(2, 7))))
        assertEquals(Sender.User, shown.sender)
        assertEquals(listOf(3 until 10), shown.marks)
        assertEquals("timeout", shown.excerpt.substring(shown.marks.single()))
    }
}
