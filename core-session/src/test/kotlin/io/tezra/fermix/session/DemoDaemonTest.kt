package io.tezra.fermix.session

import io.tezra.fermix.demo.DEMO_PORT
import io.tezra.fermix.demo.DEMO_SEED
import io.tezra.fermix.demo.sha256
import io.tezra.fermix.protocol.ApprovalOutcome
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.protocol.ToolPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

// The debug app's demo (demo-daemon) against core-session's own pairing and session, on a virtual clock: the
// ceremony to a stored record, a window past its life, each Fermix's script replayed and an older page, a reply as
// it plays on a route that streams and on one that does not, a reconnect that pulls what was written while the
// phone was away, a restarted demo keeping the phone's cursors and beginning again only what had not finished, a
// row's media downloaded whole, an approval granted and one denied as the engine answers them, a stop, the
// diagnostics line, and a seed that plays two runs alike. A change to protocol or to core-session's surface that
// the demo no longer answers turns one of these red.

/** DemoTimes' defaults, as the debug app plays them. */
private const val REACH_MS = 900L
private const val CHECK_MS = 900L
private const val APPROVE_MS = 7_500L
private const val THINKING_MS = 1_200L
private const val DELTA_MS = 120L
private const val STREAM_MS = 2_000L
private const val WINDOW_MS = 120_000L

/** The rows of a fresh profile's first page (core-session's BACKWARD_PAGE_LIMIT). */
private const val NEWEST_PAGE = 50

/** The engine's words for a granted and a denied sandbox card's command (sandbox.ex). */
private const val GRANTED = "Sandbox updated. Access granted — resuming your request."
private const val DENIED = "Sandbox change denied — the pending grant was discarded."

class DemoDaemonTest {
    @Test
    fun `the demo's link pairs through Verify, approved 7500 ms after the request, to a stored record`() =
        runTest {
            val demo = DemoHarness(this)
            val states = mutableListOf<Pair<Long, PairingState>>()
            val handle = demo.pair(SoftwareDeviceKeys(), states)
            val approved = withTimeout(DEMO_WAIT_MS) { handle.state.first { it is PairingState.Approved } }
            var stored: InstanceFacts? = null
            handle.commit { facts -> null.also { stored = facts } }
            val session = assertInstanceOf<PairingState.Approved>(approved).session
            withTimeout(DEMO_WAIT_MS) { session.state.first { it is SessionState.Connected } }

            val names = states.map { it.second::class.simpleName }
            val expected = listOf("Validating", "Reaching", "Reaching", "Checking", "Securing", "Verify", "Approved")
            assertEquals(expected, names)
            val at = states.associate { (time, state) -> state::class.simpleName to time }
            assertTrue(at.getValue("Checking") - states[2].first >= REACH_MS, "Reaching shows while the dial waits")
            assertTrue(at.getValue("Securing") - at.getValue("Checking") >= CHECK_MS, "Checking shows its pause")
            val verifying = at.getValue("Approved") - at.getValue("Verify")
            assertTrue(verifying in APPROVE_MS..APPROVE_MS + CHECK_MS, "Verify shows ${verifying}ms")
            val fermix = demo.demo.fermixes.first()
            val facts = checkNotNull(stored)
            assertEquals("suj-mbp", facts.host)
            assertEquals("fermix", facts.profile)
            assertEquals("demo-0-1", facts.deviceId)
            assertEquals(DEMO_PORT, facts.port)
            assertEquals(listOf(fermix.route), facts.candidates)
            assertEquals(Base64.getEncoder().encodeToString(fermix.gatewayKey.publicKey), facts.gatewayPk)
            assertEquals(emptyList<Any>(), facts.pushPlatforms)
            // The next link is the same computer's other home: its title collides, and the app asks for a name.
            val next = PairingLink.parse(demo.demo.nextLink())
            assertEquals(listOf("suj-mbp", "fermix-dev"), listOf(next.name, next.profile))
            session.close()
        }

    @Test
    fun `a link past its window's life is refused before the code shows`() =
        runTest {
            val demo = DemoHarness(this)
            val states = mutableListOf<Pair<Long, PairingState>>()
            val handle = demo.pair(SoftwareDeviceKeys(), states, waitedMs = WINDOW_MS + 1)
            val ended = withTimeout(DEMO_WAIT_MS) { handle.state.first { it is PairingState.Ended } }

            assertEquals(PairingState.Expired, ended)
            assertFalse(states.any { it.second is PairingState.Verify }, "no code shows for a closed window")
        }

    @Test
    fun `the demo's history replays each Fermix's script in order, the newest page first and older pages on asking`() =
        runTest {
            val demo = DemoHarness(this)
            demo.demo.fermixes.forEach { fermix ->
                val store = demo.announcer.rows.size
                val seen = demo.events.size
                demo.open(fermix)
                demo.caughtUp()
                val rows =
                    demo.announcer.rows
                        .drop(store)
                        .map { assertInstanceOf<TimelineRow.Message>(it).message }
                val script = fermix.script.rows
                val newest = script.takeLast(NEWEST_PAGE)
                val first = script.size - newest.size + 1
                assertEquals(newest.map { it.text }, rows.map { it.content }, fermix.host)
                assertEquals(newest.map { it.role }, rows.map { it.role }, fermix.host)
                assertEquals((first..script.size).map { it.toULong() }, rows.map { it.serverSeq }, fermix.host)
                val older = script.take(first - 1).takeLast(NEWEST_PAGE).map { it.text }
                assertEquals(older, olderPage(demo, seen, first), fermix.host)
                demo.session.close()
                demo.store.cursors = EMPTY_CURSORS
            }
        }

    @Test
    fun `a reply shows its card, its tool and its words streamed over about 2 s`() =
        runTest {
            val demo = DemoHarness(this)
            demo.open(demo.demo.fermixes[2])
            demo.caughtUp()
            val sentAt = demo.now
            demo.say("m1", "How did the export go?")
            assertEquals(TurnOutcome.Completed, demo.ended("turn-m1"))

            val effects = demo.effectsOf("turn-m1")
            val kinds = effects.map { it.second::class.simpleName }.distinct()
            val tool = effects.any { it.second is TurnEffect.ToolChip }
            val card = listOf("CardShown", "CardText") + (if (tool) listOf("ToolChip") else emptyList()) + "CardRemoved"
            assertEquals(card + listOf("BubbleOpened", "BubbleText", "BubbleSealed", "TurnEnded"), kinds)
            val firstHeading = effects.first { it.second is TurnEffect.CardText }.first
            assertTrue(firstHeading - sentAt >= THINKING_MS, "the card thinks before its first heading")
            val texts = effects.filter { it.second is TurnEffect.BubbleText }.map { it.first }
            val sealed = effects.first { it.second is TurnEffect.BubbleSealed }.first
            assertTrue(
                sealed - texts.first() in STREAM_MS - 2 * DELTA_MS..STREAM_MS + DELTA_MS,
                "streamed ${sealed - texts.first()}ms",
            )
            assertTrue(texts.zipWithNext().all { (a, b) -> b - a >= DELTA_MS }, "deltas at most every 100 ms")
            if (tool) {
                val phases = effects.mapNotNull { (it.second as? TurnEffect.ToolChip)?.phase }
                assertEquals(listOf(ToolPhase.START, ToolPhase.STOP), phases)
            }
            demo.session.close()
        }

    @Test
    fun `a reply on a model that does not stream sends only its ending, and a pick applies from the next turn`() =
        runTest {
            val demo = DemoHarness(this)
            demo.open(demo.demo.fermixes[2])
            demo.caughtUp()
            demo.say("m1", "How did the export go?")
            demo.awaitEvent { (it as? SessionEvent.Turn)?.effect as? TurnEffect.CardText }
            check(demo.session.send(ClientEvent.Command("p1", PROFILE, "model", "anthropic/claude-opus-5.5")))
            demo.awaitEvent { it as? SessionEvent.ModelChanged }
            assertEquals(TurnOutcome.Completed, demo.ended("turn-m1"))

            demo.say("m2", "And now?")
            assertEquals(TurnOutcome.Completed, demo.ended("turn-m2"))
            val quiet = demo.effectsOf("turn-m2").map { it.second::class.simpleName }.toSet()
            assertTrue(quiet.containsAll(listOf("CardShown", "BubbleSealed", "TurnEnded")), "$quiet")
            assertFalse(quiet.any { it in setOf("CardText", "ToolChip", "BubbleText") }, "only the ending: $quiet")
            val m1 =
                demo.announcer.rows
                    .filterIsInstance<TimelineRow.Reply>()
                    .first { it.turnId == "turn-m1" }
            val m2 =
                demo.announcer.rows
                    .filterIsInstance<TimelineRow.Reply>()
                    .first { it.turnId == "turn-m2" }
            assertEquals("gpt-6-astra", m1.route?.model, "the turn running as the pick came keeps its route")
            assertEquals("claude-opus-5.5", m2.route?.model)
            demo.session.close()
        }

    @Test
    fun `a session's diagnostics hold the newer daemon's event it was sent`() =
        runTest {
            val demo = DemoHarness(this)
            demo.open()
            demo.caughtUp()

            val noted =
                demo.session.diagnostics.value
                    .filter { it.kind == DiagnosticKind.UNKNOWN_EVENT }
            assertEquals(listOf("call_offer"), noted.map { it.detail })
            demo.session.close()
        }

    @Test
    fun `a reconnect, and a restarted demo, keep the phone's cursors and number on from them`() =
        runTest {
            val first = DemoHarness(this)
            first.open()
            first.caughtUp()
            val head =
                first.demo.fermixes
                    .first()
                    .script.rows.size
                    .toULong()
            assertEquals(head, first.store.cursors.lastServerSeq)
            first.say("m1", "Thanks, that's all.")
            first.ended("turn-m1")
            assertEquals(head + 2uL, first.store.cursors.lastServerSeq)
            val announced = first.announcer.rows.size
            first.session.suspend()
            first.session.resume()
            first.caughtUp()
            assertEquals(announced, first.announcer.rows.size, "a reconnect pulls no row again")
            first.say("m2", "One more thing.")
            first.ended("turn-m2")
            assertEquals(head + 4uL, first.store.cursors.lastServerSeq)
            first.session.close()

            // The app restarted: a new demo of the same seed, the phone's store as it was. It begins nothing of what
            // the script had under way again: the card the phone saw is not raised anew.
            val again = DemoHarness(this)
            again.store.cursors = first.store.cursors
            again.open()
            again.caughtUp()
            assertEquals(0, again.announcer.rows.size, "the restarted demo sends no row the phone holds")
            again.say("m3", "Still there?")
            again.ended("turn-m3")
            val rows = again.announcer.rows.map { it.serverSeq }
            assertEquals(listOf(head + 5uL, head + 6uL), rows)
            assertTrue(first.events.any { it.second is SessionEvent.Approval }, "the first run raised the card")
            assertFalse(again.events.any { it.second is SessionEvent.Approval }, "the restarted run raises it no more")
            again.session.close()
        }

    @Test
    fun `a reconnect pulls what the demo wrote while the phone was away, in order, by the page after its cursor`() =
        runTest {
            val demo = DemoHarness(this)
            val studio = demo.demo.fermixes[2]
            demo.open(studio)
            demo.caughtUp()
            val head =
                studio.script.rows.size
                    .toULong()
            demo.say("m1", "How did the export go?")
            demo.say("m2", "And the backups?")
            demo.awaitRow { it == "And the backups?" }
            assertEquals(head + 2uL, demo.store.cursors.lastServerSeq)
            demo.session.suspend()
            val away = demo.announcer.rows.size
            // Both replies end, and are written, while the phone is away: no connection hears them live.
            delay(AWAY_MS)
            val resumedAt = demo.now
            demo.session.resume()
            demo.caughtUp()

            assertTrue(demo.now - resumedAt < REACH_MS, "a reconnect waits for none of a pairing's pauses")
            val pulled = demo.announcer.rows.drop(away)
            assertEquals(listOf(head + 3uL, head + 4uL), pulled.map { it.serverSeq })
            val rows = pulled.map { assertInstanceOf<TimelineRow.Message>(it, "a page's row, never a live reply") }
            assertEquals(listOf("assistant", "assistant"), rows.map { it.message.role })
            assertEquals(head + 4uL, demo.store.cursors.lastServerSeq, "the cursor at the demo's head")
            demo.say("m3", "Thanks.")
            assertEquals(head + 5uL, demo.awaitRow { it == "Thanks." }.serverSeq, "the next row follows on")
            demo.session.close()
        }

    @Test
    fun `a restarted demo runs again the long turn it had not finished, and it is answered once`() =
        runTest {
            val first = DemoHarness(this)
            val dev = first.demo.fermixes[1]
            first.open(dev)
            first.caughtUp()
            first.awaitEvent { (it as? SessionEvent.Turn)?.effect as? TurnEffect.CardText }
            first.session.close()

            // The app restarted inside the long turn's two minutes: a new demo, the phone's store as it was.
            val again = DemoHarness(this)
            again.store.cursors = first.store.cursors
            again.open(dev)
            again.caughtUp()
            assertEquals(TurnOutcome.Completed, again.ended("turn-demo-d2"))
            delay(LONG_TURN_MS * 2)
            val answers = again.announcer.rows.filter { wordsOf(it).startsWith(LONG_ANSWER) }
            assertEquals(1, answers.size, "one answer: ${again.announcer.rows.map(::wordsOf)}")
            again.session.close()
        }

    @Test
    fun `a row's media and a preview's image download whole, their bytes the digest the row names`(
        @TempDir into: File,
    ) = runTest {
        val demo = DemoHarness(this)
        demo.open()
        demo.caughtUp()
        val messages =
            demo.announcer.rows
                .filterIsInstance<TimelineRow.Message>()
                .map { it.message }
        val media = messages.flatMap { it.mediaRefs }
        val images = messages.flatMap { it.linkPreviews.orEmpty() }.mapNotNull { it.imageRef }
        assertEquals(listOf("sunrise.png", "harbour.png", "nightly-export.log"), media.map { it.filename })
        assertEquals(1, images.size, "the guide's preview")

        media.forEach { ref ->
            val file = File(into, ref.ref)
            val fetched = assertInstanceOf<OneShot.Answered<FetchedMedia>>(demo.session.fetchMedia(ref.ref, file))
            assertEquals(ref.sha256, fetched.value.sha256)
            assertEquals(ref.sizeBytes, fetched.value.sizeBytes)
            assertEquals(ref.sha256, sha256Hex(file.readBytes()), "${ref.filename}'s bytes")
        }
        val image = images.single()
        val file = File(into, image)
        val fetched = assertInstanceOf<OneShot.Answered<FetchedMedia>>(demo.session.fetchMedia(image, file))
        assertEquals(image, fetched.value.sha256, "a demo blob's ref is its digest")
        assertEquals(image, sha256Hex(file.readBytes()))
        demo.session.close()
    }

    @Test
    fun `a restarted demo runs the long turn a phone saw answered no more`() =
        runTest {
            val first = DemoHarness(this)
            val dev = first.demo.fermixes[1]
            first.open(dev)
            first.caughtUp()
            assertEquals(TurnOutcome.Completed, first.ended("turn-demo-d2"))
            first.caughtUp()
            first.session.close()

            val again = DemoHarness(this)
            again.store.cursors = first.store.cursors
            again.open(dev)
            again.caughtUp()
            delay(LONG_TURN_MS * 2)
            assertTrue(again.effectsOf("turn-demo-d2").isEmpty(), "the answered request runs no more")
            assertEquals(0, again.announcer.rows.size, "no second answer")
            again.session.close()
        }

    @Test
    fun `a granted card is answered as the engine answers it, and the request it held up runs again as a turn`() =
        runTest {
            val demo = DemoHarness(this)
            demo.open()
            demo.caughtUp()
            val card = demo.awaitEvent { it as? SessionEvent.Approval }
            assertEquals("sandbox-demo-restart-export", card.approvalId)

            assertInstanceOf<ApprovalAnswer.Sent>(demo.session.answerApproval(card.approvalId, approve = true))
            val resolved = demo.awaitEvent { it as? SessionEvent.ApprovalResolved }
            assertEquals(ApprovalOutcome.APPROVED, resolved.outcome)
            val granted = assertInstanceOf<TimelineRow.Reply>(demo.awaitRow { it.startsWith(GRANTED) })
            assertEquals("$GRANTED\ncommands + systemctl", granted.text)
            assertTrue(granted.turnId.startsWith("turn-approval-answer:"), "the command's own turn: ${granted.turnId}")
            assertEquals(TurnOutcome.Completed, demo.ended("turn-grant-resume-1"))
            val done = assertInstanceOf<TimelineRow.Reply>(demo.awaitRow { it.startsWith("Restarted") })
            assertEquals("turn-grant-resume-1", done.turnId, "the resumed request's turn, never a row of the Mac's")
            assertTrue(granted.serverSeq < done.serverSeq)
            demo.session.close()
        }

    @Test
    fun `a reply that asks ends saying so, and a denied card is answered inline and runs nothing`() =
        runTest {
            val demo = DemoHarness(this)
            demo.open(demo.demo.fermixes[2])
            demo.caughtUp()

            demo.say("m1", "Please deploy the build.")
            val ask = demo.awaitEvent { it as? SessionEvent.Approval }
            assertEquals(TurnOutcome.Completed, demo.ended("turn-m1"))
            val asked = assertInstanceOf<TimelineRow.Reply>(demo.awaitRow { it.startsWith("That needs your OK") })
            assertEquals("turn-m1", asked.turnId)
            assertEquals(60, ask.ttlS, "a sandbox card's ttl, the engine's")
            assertInstanceOf<ApprovalAnswer.Sent>(demo.session.answerApproval(ask.approvalId, approve = false))
            assertEquals(ApprovalOutcome.DENIED, demo.awaitEvent { it as? SessionEvent.ApprovalResolved }.outcome)
            demo.awaitRow { it == DENIED }
            delay(STREAM_MS * 2)
            assertFalse(demo.announcer.rows.any { wordsOf(it).startsWith("Done") }, "a denied request runs no more")
            assertFalse(demo.events.any { (it.second as? SessionEvent.Turn)?.effect?.turnId == "turn-grant-resume-1" })
            demo.session.close()
        }

    @Test
    fun `stop ends a reply mid-stream, and nothing of it comes after`() =
        runTest {
            val demo = DemoHarness(this)
            demo.open(demo.demo.fermixes[2])
            demo.caughtUp()
            demo.say("m1", "Write me a long answer.")
            demo.awaitEvent { (it as? SessionEvent.Turn)?.effect as? TurnEffect.BubbleText }
            assertTrue(demo.session.stop("s1"))

            assertEquals(TurnOutcome.Stopped, demo.ended("turn-m1"))
            val stoppedAt = demo.effectsOf("turn-m1").last().first
            val answer = demo.awaitRow { it == "Stopped." }
            assertEquals("turn-s1", assertInstanceOf<TimelineRow.Reply>(answer).turnId)
            delay(STREAM_MS * 2)
            assertFalse(
                demo.effectsOf("turn-m1").any { it.second is TurnEffect.BubbleSealed },
                "the stopped bubble never seals",
            )
            assertEquals(stoppedAt, demo.effectsOf("turn-m1").last().first, "nothing of the turn comes after its end")
            demo.session.close()
        }

    @Test
    fun `one seed plays two runs alike, another seed is another demo`() =
        runTest {
            val one = transcript(DEMO_SEED)
            val two = transcript(DEMO_SEED)
            assertEquals(one, two)
            assertTrue(one.size > SAID.size, "the transcript holds the replies")
            val other = DemoHarness(this, seed = DEMO_SEED + 1)
            assertNotEquals(DemoHarness(this).demo.nextLink(), other.demo.nextLink())
        }
}

/** DemoTimes' long turn, which the dev Fermix's request runs. */
private const val LONG_TURN_MS = 120_000L

/** The first words of the long turn's answer (DemoScripts' devScript). */
private const val LONG_ANSWER = "All 1,412 tests passed"

/** How long the phone stays away in the reconnect test: past any reply's end. */
private const val AWAY_MS = 30_000L

private fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHexString()

/**
 * The texts of the page older than row [first], the newest page's, that [demo]'s session loads, the events after
 * the first [seen] its own: none when the newest page says no older row is left.
 */
private suspend fun olderPage(
    demo: DemoHarness,
    seen: Int,
    first: Int,
): List<String> {
    val newest = demo.awaitEvent(seen) { it as? SessionEvent.OlderLoaded }
    val before = newest.prevBeforeSeq ?: return emptyList()
    assertEquals(first.toULong(), before, "the newest page says where the older rows end")
    check(demo.session.loadOlder(before)) { "the session asks for an older page" }
    val page = demo.awaitEvent(seen) { event -> (event as? SessionEvent.OlderLoaded)?.takeIf { it.rows.isNotEmpty() } }
    return page.rows.map(::wordsOf)
}

/** What the owner says in the seed test: each reply's plan is the seed's. */
private val SAID = listOf("Morning!", "Show me the slowest step.", "And the log?", "Thanks.")

/**
 * A run of the demo of [seed]: the owner's messages one after another on the main Fermix, and every turn effect
 * and row the phone took, each with its virtual time from the run's start.
 */
private suspend fun TestScope.transcript(seed: Long): List<String> {
    val demo = DemoHarness(this, seed)
    demo.open()
    demo.caughtUp()
    SAID.forEachIndexed { index, text ->
        demo.say("m$index", text)
        demo.ended("turn-m$index")
    }
    demo.session.close()
    return demo.events.mapNotNull { (at, event) ->
        when (event) {
            is SessionEvent.Turn -> "$at ${event.effect}"
            is SessionEvent.Approval -> "$at approval ${event.approvalId} ${event.text}"
            else -> null
        }
    } + demo.announcer.rows.map { "row ${it.serverSeq} ${wordsOf(it)}" }
}
