package io.tezra.fermix

import io.tezra.fermix.data.Instance
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.session.SessionStore
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The sessions' keeper (design section 12.5): one session per paired instance, an approval's taken over and
 * never a second, every session put aside after 5 s out of sight, the timer cancelled by a return,
 * "Unpair" asking the daemon to forget the phone where "Remove" does not, and the candidate each session's last
 * `hello` went over handed to the sink, which keeps it on the record for the next process to race first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSupervisorTest {
    /**
     * The supervisor over [records], in [sessions] (the test's background scope unless a test ends its own),
     * its fake opener counting the sessions it opens for each instance, each an idle one over [store], and its
     * [forget] noting each session it is asked to send `unpair` over, which goes out while [sent] says so.
     */
    private class Rig(
        val scope: TestScope,
        initial: List<Instance>,
        sessions: CoroutineScope = scope.backgroundScope,
        store: SessionStore = NoStore,
    ) {
        val records = MutableStateFlow(initial)
        val opened = mutableMapOf<String, Int>()
        val faults = mutableListOf<String>()
        val forgotten = mutableListOf<Session>()
        var sent = false
        val supervisor =
            SessionSupervisor(
                records = records,
                opener = { instance, sessionScope ->
                    opened.merge(instance.id, 1, Int::plus)
                    idleSession(sessionScope, store)
                },
                sink = NoSink,
                scope = sessions,
                log = { message, _ -> faults += message },
                forget = { session ->
                    forgotten += session
                    sent
                },
            )

        /** Starts the supervisor on the test's own scheduler, as the app does on its I/O dispatcher. */
        fun start() = supervisor.start(StandardTestDispatcher(scope.testScheduler))

        fun session(id: String): Session = checkNotNull(supervisor.sessions.value[id]) { "no session for $id" }
    }

    private val first = record(1)
    private val second = record(2)

    @Test
    fun `the records, a return to sight and an approval's handover open one session per instance`() =
        runTest {
            val rig = Rig(this, listOf(first))
            rig.start()
            rig.supervisor.inSight.value = true
            runCurrent()
            assertEquals(mapOf(first.id to 1), rig.opened)
            rig.records.value = listOf(first.copy(nickname = "Studio"))
            rig.supervisor.inSight.value = false
            advanceTimeBy(BACKGROUND_GRACE_MILLIS + 1)
            rig.supervisor.inSight.value = true
            runCurrent()
            assertEquals(mapOf(first.id to 1), rig.opened)

            val approved = idleSession(backgroundScope)
            val replaced =
                rig.supervisor.adopt(second.id, approved) {
                    rig.records.value += second
                    null
                }
            runCurrent()
            assertEquals(null, replaced)
            assertSame(approved, rig.session(second.id))
            assertEquals(mapOf(first.id to 1), rig.opened)
            assertEquals(setOf(first.id, second.id), rig.supervisor.sessions.value.keys)
        }

    @Test
    fun `a return within 5 s out of sight cancels the timer, and a session is put aside only after 5 s away`() =
        runTest {
            val rig = Rig(this, listOf(first))
            rig.start()
            rig.supervisor.inSight.value = true
            runCurrent()
            val session = rig.session(first.id)
            // Every state the session passes through, so that a suspension undone at once is still seen.
            val states = mutableListOf<SessionState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.state.toList(states) }
            rig.supervisor.inSight.value = false
            advanceTimeBy(BACKGROUND_GRACE_MILLIS - 1)
            runCurrent()
            rig.supervisor.inSight.value = true
            advanceTimeBy(BACKGROUND_GRACE_MILLIS * 2)
            runCurrent()
            assertFalse(SessionState.Suspended in states, "a return within the grace still suspended: $states")

            rig.supervisor.inSight.value = false
            advanceTimeBy(BACKGROUND_GRACE_MILLIS - 1)
            runCurrent()
            assertNotEquals(SessionState.Suspended, session.state.value)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(SessionState.Suspended, session.state.value)
            rig.supervisor.inSight.value = true
            runCurrent()
            assertNotEquals(SessionState.Suspended, session.state.value)
            assertSame(session, rig.session(first.id))
        }

    @Test
    fun `nothing opens before the app is in sight, and a pairing approved out of sight is put aside`() =
        runTest {
            val rig = Rig(this, listOf(first))
            rig.start()
            advanceTimeBy(BACKGROUND_GRACE_MILLIS + 1)
            runCurrent()
            assertTrue(
                rig.supervisor.sessions.value
                    .isEmpty(),
            )
            val approved = idleSession(backgroundScope)
            rig.supervisor.adopt(second.id, approved) {
                rig.records.value += second
                null
            }
            runCurrent()
            assertEquals(SessionState.Suspended, approved.state.value)
            assertTrue(rig.opened.isEmpty())
        }

    @Test
    fun `a removed instance's session is closed before its removal runs, and one whose record goes with it`() =
        runTest {
            val rig = Rig(this, listOf(first, second))
            rig.start()
            rig.supervisor.inSight.value = true
            runCurrent()
            val removed = rig.session(first.id)
            val gone = mutableListOf<String>()
            // The removal deletes the instance's files, which its session's store and announcer use: Session.close
            // joins the session's run, so the session is over, and held no more, by the time the removal runs.
            val atRemoval = mutableListOf<Pair<SessionState, Boolean>>()
            rig.supervisor.remove(first.id, unpair = true) { id ->
                atRemoval += removed.state.value to (id in rig.supervisor.sessions.value)
                gone += id
                rig.records.value -= first
            }
            runCurrent()
            assertEquals(listOf(SessionState.Closed to false), atRemoval)
            assertEquals(listOf(first.id), gone)
            assertEquals(SessionState.Closed, removed.state.value)
            val other = rig.session(second.id)
            rig.records.value = emptyList()
            runCurrent()
            assertEquals(SessionState.Closed, other.state.value)
            assertTrue(
                rig.supervisor.sessions.value
                    .isEmpty(),
            )
        }

    @Test
    fun `a removal runs only once the store call its session's run was making has returned`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val rig = Rig(this, listOf(first), store = GatedStore(gate))
            rig.start()
            rig.supervisor.inSight.value = true
            runCurrent()
            val session = rig.session(first.id)
            val gone = mutableListOf<String>()
            // The removal deletes the database the store reads, so Session.close joins the run, store call and all.
            val removal = launch { rig.supervisor.remove(first.id, unpair = false) { gone += it } }
            runCurrent()
            assertEquals(emptyList<String>(), gone, "the removal ran while the session's store call was running")
            assertEquals(SessionState.Closed, session.state.value)
            gate.complete(Unit)
            runCurrent()
            assertTrue(removal.isCompleted, "the removal did not run once the store call returned")
            assertEquals(listOf(first.id), gone)
        }

    @Test
    fun `a removal runs only once a request writing its session's store, in the caller's coroutine, has returned`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val rig = Rig(this, listOf(first), store = GatedSends(gate))
            rig.start()
            rig.supervisor.inSight.value = true
            runCurrent()
            val session = rig.session(first.id)
            val sending = launch { session.send(ClientEvent.Msg("m1", "main", "hello", emptyList())) }
            runCurrent()
            val gone = mutableListOf<String>()
            // The removal deletes the database the store writes, so Session.close waits for the request too.
            val removal = launch { rig.supervisor.remove(first.id, unpair = false) { gone += it } }
            runCurrent()
            assertEquals(emptyList<String>(), gone, "the removal ran while a request was writing the store")
            assertEquals(SessionState.Closed, session.state.value)
            gate.complete(Unit)
            runCurrent()
            assertTrue(sending.isCompleted && !sending.isCancelled, "the request did not return")
            assertTrue(removal.isCompleted, "the removal did not run once the request returned")
            assertEquals(listOf(first.id), gone)
        }

    @Test
    fun `Unpair asks the daemon to forget the phone and waits for its close, and Remove asks nothing`() =
        runTest {
            val rig = Rig(this, listOf(first, second))
            rig.start()
            rig.supervisor.inSight.value = true
            runCurrent()
            val unpaired = rig.session(first.id)
            rig.sent = true
            val gone = mutableListOf<String>()
            val removal = launch { rig.supervisor.remove(first.id, unpair = true) { gone += it } }
            runCurrent()
            assertEquals(listOf(unpaired), rig.forgotten)
            // The daemon has UNPAIR_WAIT_MILLIS to answer `unpair` with its close before the instance goes.
            advanceTimeBy(UNPAIR_WAIT_MILLIS - 1)
            runCurrent()
            assertEquals(emptyList<String>(), gone)
            advanceTimeBy(2)
            runCurrent()
            assertTrue(removal.isCompleted)
            assertEquals(listOf(first.id), gone)

            rig.supervisor.remove(second.id, unpair = false) { gone += it }
            assertEquals(listOf(first.id, second.id), gone)
            assertEquals(listOf(unpaired), rig.forgotten)
        }

    @Test
    fun `an unpair that could not go out removes the instance at once`() =
        runTest {
            val rig = Rig(this, listOf(first))
            rig.start()
            rig.supervisor.inSight.value = true
            runCurrent()
            val session = rig.session(first.id)
            val gone = mutableListOf<String>()
            rig.supervisor.remove(first.id, unpair = true) { gone += it }
            assertEquals(listOf(session), rig.forgotten)
            assertEquals(listOf(first.id), gone)
        }

    @Test
    fun `an id that is no instance's is refused, and a second start too`() =
        runTest {
            val rig = Rig(this, listOf(first))
            val removal = runCatching { rig.supervisor.remove("suj-mbp", unpair = false) {} }
            assertTrue(removal.exceptionOrNull() is IllegalArgumentException, "$removal")
            val adoption = runCatching { rig.supervisor.adopt("suj-mbp", idleSession(backgroundScope)) { null } }
            assertTrue(adoption.exceptionOrNull() is IllegalArgumentException, "$adoption")
            rig.start()
            assertThrows<IllegalStateException> { rig.start() }
        }

    @Test
    fun `an instance the opener cannot open is logged and left without a session`() =
        runTest {
            val records = MutableStateFlow(listOf(first))
            val faults = mutableListOf<String>()
            val supervisor =
                SessionSupervisor(
                    records = records,
                    opener = { _, _ -> throw SessionUnavailable("no key") },
                    sink = NoSink,
                    scope = backgroundScope,
                    log = { message, _ -> faults += message },
                    forget = { error("nothing is unpaired here") },
                )
            supervisor.start(StandardTestDispatcher(testScheduler))
            supervisor.inSight.value = true
            runCurrent()
            assertTrue(supervisor.sessions.value.isEmpty())
            assertEquals(listOf("no session for ${first.id}"), faults)
        }

    @Test
    fun `the candidate a session's last hello went over reaches the sink, which keeps it on the record`() =
        runTest {
            val reached = mutableListOf<Pair<String, Candidate>>()
            val sink =
                object : EventSink by NoSink {
                    override suspend fun reached(
                        instanceId: String,
                        candidate: Candidate,
                    ) {
                        reached += instanceId to candidate
                    }
                }
            val supervisor =
                SessionSupervisor(
                    records = MutableStateFlow(listOf(first)),
                    opener = { _, sessionScope -> idleSession(sessionScope, lastSuccessful = IDLE_CANDIDATE) },
                    sink = sink,
                    scope = backgroundScope,
                    log = { message, _ -> error(message) },
                    forget = { error("nothing is unpaired here") },
                )
            supervisor.start(StandardTestDispatcher(testScheduler))
            supervisor.inSight.value = true
            runCurrent()
            assertEquals(listOf(first.id to IDLE_CANDIDATE), reached)
        }

    @Test
    fun `the end of the supervisor's scope, as the process's, closes every session it holds`() =
        runTest {
            val own = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val rig = Rig(this, listOf(first, second), sessions = own)
            rig.start()
            rig.supervisor.inSight.value = true
            runCurrent()
            val held = rig.supervisor.sessions.value.values
            assertEquals(2, held.size)
            own.cancel()
            runCurrent()
            assertTrue(held.all { it.state.value == SessionState.Closed })
        }

    @Test
    fun `an instance thinks while any of its turns runs, and stops only when the last one ends`() {
        val phone = TurnEffect.CardShown("turn-phone")
        val cron = TurnEffect.CardShown("turn-cron")
        val running = turnsAfter(turnsAfter(emptySet(), phone), cron)
        assertEquals(setOf("turn-phone", "turn-cron"), running)
        val phoneEnded = turnsAfter(running, TurnEffect.TurnEnded("turn-phone", TurnOutcome.Completed))
        assertEquals(setOf("turn-cron"), phoneEnded)
        assertEquals(setOf("turn-cron"), turnsAfter(phoneEnded, TurnEffect.CardText("turn-cron", "Reading")))
        assertEquals(emptySet<String>(), turnsAfter(phoneEnded, TurnEffect.TurnEnded("turn-cron", TurnOutcome.Over)))
    }
}
