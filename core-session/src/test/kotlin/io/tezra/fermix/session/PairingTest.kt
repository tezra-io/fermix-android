package io.tezra.fermix.session

import io.tezra.fermix.attest.AttestationChallenge
import io.tezra.fermix.attest.DEVICE_KEY_ALIAS_PREFIX
import io.tezra.fermix.demo.sasOf
import io.tezra.fermix.protocol.AttestationKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Platform
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.Base64

private const val WAIT_MS = 60_000L

/** The key this phone holds for the daemon from an earlier pairing. */
private const val OLD = "${DEVICE_KEY_ALIAS_PREFIX}old"

/** The paired session's route from the approval: the daemon's tailnet address. */
private val APPROVED_ROUTE = Candidate("100.101.102.104", Candidate.Scope.TAILNET, Candidate.Kind.IP)

class PairingTest {
    @Test
    fun `a pairing runs the ceremony in order and hands its connection to a paired session`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            val read = connection.pairRequest()
            harness.state<PairingState.Verify>()
            connection.send(APPROVAL)
            val hello = connection.next()
            connection.send(HELLO_ACK)
            val approved = harness.state<PairingState.Approved>()
            withTimeout(WAIT_MS) { approved.session.state.first { it is SessionState.Connected } }

            assertEquals(1uL, read.frame.seq)
            assertEquals(2, read.frame.v)
            val expected = ClientEvent.PairRequest("Pixel 9 Pro", "Google Pixel 9 Pro", "0.1.0", Platform.ANDROID)
            assertEquals(expected, read.request.copy(attestation = null))
            assertEquals(2uL, hello.seq)
            assertEquals(DEVICE_ID, assertInstanceOf<ClientEvent.Hello>(hello.event).deviceId)
            assertEquals(1, harness.daemon.dials)
            assertEquals(
                listOf(
                    PairingState.Validating,
                    PairingState.Reaching(emptyList(), 0),
                    PairingState.Reaching(listOf(TAILNET), 0),
                    PairingState.Checking,
                    PairingState.Securing,
                ),
                harness.states.take(5),
            )
            assertInstanceOf<PairingState.Verify>(harness.states[5])
            assertEquals(approved, harness.states.last())
            assertEquals(7, harness.states.size)
            approved.session.close()
        }

    @Test
    fun `the race dials over the link's own port and tls_fp pin, and the session keeps that dialer`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            val approved = harness.state<PairingState.Approved>()

            assertEquals(listOf(LINK_PORT to harness.tlsFingerprint.toHexString()), harness.dialersMade)
            approved.session.close()
        }

    @Test
    fun `pair_request carries the attestation chain as its raw tail, its leaf the handshake's key`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            val read = connection.pairRequest()

            val attestation = checkNotNull(read.request.attestation)
            assertEquals(AttestationKind.ANDROID_KEYMINT, attestation.kind)
            assertEquals(listOf(read.chain[0].size, read.chain[1].size), attestation.certLengths)
            assertArrayEquals(leafCertificate(checkNotNull(connection.responded).initiatorStatic), read.chain[0])
            assertArrayEquals(issuerCertificate(), read.chain[1])
            // The challenge is the one the daemon derives from the secret it holds.
            assertArrayEquals(AttestationChallenge.of(harness.secret), harness.keys.challenges.single())
        }

    @Test
    fun `the SAS shown is the one the daemon derives from the handshake hash`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            val verify = harness.state<PairingState.Verify>()

            assertEquals(sasOf(checkNotNull(connection.responded).handshakeHash), verify.sas)
            assertEquals("Pixel 9 Pro", verify.deviceName)
            assertEquals(-PAIRING_WINDOW_MS, verify.expiresAt.elapsedNow().inWholeMilliseconds)
        }

    @Test
    fun `Verify prints without its SAS, so no log line of a state carries the code`() =
        runTest {
            val verify = PairingState.Verify("481062", testScheduler.timeSource.markNow(), "Pixel 9 Pro")

            assertFalse("481062" in verify.toString(), verify.toString())
            assertTrue("Pixel 9 Pro" in verify.toString())
            assertEquals(verify, verify.copy())
        }

    @Test
    fun `the link's secret is zeroed once the handshake consumed it, before Verify`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            assertTrue(harness.link.secret.any { it != 0.toByte() })
            val connection = harness.paired()
            connection.pairRequest()
            harness.state<PairingState.Verify>()

            assertTrue(harness.link.secret.all { it == 0.toByte() })
            // A link pairs once.
            assertThrows<IllegalArgumentException> {
                Pairing.start(harness.link, harness.keys, IDENTITY, harness.parts(backgroundScope), backgroundScope)
            }
        }

    @Test
    fun `the keepalive runs while the owner decides, and hello follows at the next seq`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            harness.state<PairingState.Verify>()
            val pings = mutableListOf<ULong>()
            repeat(3) {
                val ping = connection.next()
                assertEquals(ClientEvent.Ping, ping.event)
                pings += ping.seq
                connection.send(ServerEvent.Pong)
            }
            connection.send(APPROVAL)
            val hello = connection.next()

            assertEquals(listOf(2uL, 3uL, 4uL), pings)
            assertEquals(5uL, hello.seq)
            assertInstanceOf<ClientEvent.Hello>(hello.event)
            assertInstanceOf<PairingState.Approved>(harness.states.last()).session.close()
        }

    @Test
    fun `the approval's facts are the instance record's, under the attempt's new key`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            val facts = harness.state<PairingState.Approved>().facts

            val gatewayPk = Base64.getEncoder().encodeToString(harness.daemon.gatewayKey.publicKey)
            assertEquals(gatewayPk, facts.gatewayPk)
            assertEquals(harness.tlsFingerprint.toHexString(), facts.tlsFp)
            assertEquals(HOST_NAME, facts.host)
            assertEquals(HOST_NAME, facts.label)
            assertEquals(LINK_PROFILE, facts.profile)
            assertEquals(listOf(APPROVED_ROUTE), facts.candidates)
            assertEquals(LINK_PORT, facts.port)
            assertEquals(DEVICE_ID, facts.deviceId)
            assertEquals(APPROVAL.pushSalt, facts.pushSalt)
            assertEquals(listOf(PushPlatform.FCM), facts.pushPlatforms)
            assertEquals(harness.keys.generated.single(), facts.keyAlias)
            assertTrue(facts.keyAlias.startsWith(DEVICE_KEY_ALIAS_PREFIX))
            assertEquals(instanceId(harness.daemon.gatewayKey.publicKey), facts.id)
        }

    @Test
    fun `the key the store replaced is deleted once the record is stored, never before, and the new one is kept`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD)
            val handle = harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            val approved = harness.state<PairingState.Approved>()
            harness.settle()
            val stored = mutableListOf<InstanceFacts>()

            assertTrue(harness.keys.exists(OLD))
            handle.commit { facts ->
                assertTrue(harness.keys.exists(OLD)) { "the old key went before the record was stored" }
                stored += facts
                OLD
            }
            assertEquals(listOf(approved.facts), stored)
            assertFalse(harness.keys.exists(OLD))
            assertTrue(harness.keys.exists(approved.facts.keyAlias))
            val log = listOf(SoftwareDeviceKeys.GENERATE + approved.facts.keyAlias, SoftwareDeviceKeys.DELETE + OLD)
            assertEquals(log, harness.keys.log)
            assertThrows<IllegalStateException> { handle.commit { null } }
            assertFalse(handle.cancel())
            approved.session.close()
        }

    @Test
    fun `the approved session runs in the session scope, so the pairing's scope ending after commit leaves it`() =
        runTest {
            val harness = PairingHarness(this)
            val scope = harness.childScope()
            val handle = harness.start(scope = scope, sessionScope = backgroundScope)
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            connection.next()
            connection.send(HELLO_ACK)
            val approved = harness.state<PairingState.Approved>()
            handle.commit { null }
            scope.cancel()
            harness.settle()

            assertInstanceOf<SessionState.Connected>(approved.session.state.value)
            assertEquals(1, harness.daemon.dials)
            approved.session.close()
        }

    @Test
    fun `an approval the pairing's scope abandons closes its session, which ran in the session scope`() =
        runTest {
            val harness = PairingHarness(this)
            val scope = harness.childScope()
            val handle = harness.start(scope = scope, sessionScope = backgroundScope)
            val approved = harness.approved()
            scope.cancel()
            harness.settle()

            assertEquals(PairingState.Cancelled, handle.state.value)
            assertEquals(SessionState.Closed, approved.session.state.value)
        }

    @Test
    fun `no cancel lands while commit stores the record, so the key the record names stays`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD)
            val handle = harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            val approved = harness.state<PairingState.Approved>()
            val storing = CompletableDeferred<Unit>()
            val written = CompletableDeferred<Unit>()
            val commit =
                async {
                    handle.commit {
                        storing.complete(Unit)
                        written.await()
                        OLD
                    }
                }
            storing.await()
            val cancelled = handle.cancel()
            // The store finishes before any assertion, so a cancel that landed fails the test, never hangs it.
            written.complete(Unit)
            commit.await()

            assertFalse(cancelled)
            assertTrue(harness.keys.exists(approved.facts.keyAlias))
            assertFalse(harness.keys.exists(OLD))
            assertEquals(approved, handle.state.value)
            approved.session.close()
        }

    @Test
    fun `a store that fails undoes the approval, and the old key stays`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD)
            val handle = harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            val approved = harness.state<PairingState.Approved>()

            assertThrows<IOException> { handle.commit { throw IOException("disk full") } }
            assertEquals(PairingState.Cancelled, handle.state.value)
            assertEquals(SessionState.Closed, approved.session.state.value)
            assertFalse(harness.keys.exists(approved.facts.keyAlias))
            assertTrue(harness.keys.exists(OLD))
            assertFalse(handle.cancel())
        }

    @Test
    fun `a store that names the pairing's own key, or no device key, as replaced deletes nothing and fails loud`() =
        runTest {
            val reports = listOf<suspend (InstanceFacts) -> String?>({ it.keyAlias }, { "app.lock" })
            for (report in reports) {
                val harness = PairingHarness(this)
                harness.keys.hold(OLD)
                val handle = harness.start()
                val approved = harness.approved()

                assertThrows<IllegalArgumentException> { handle.commit(report) }
                assertTrue(harness.keys.exists(approved.facts.keyAlias))
                assertTrue(harness.keys.exists(OLD))
                assertFalse(harness.keys.log.any { it.startsWith(SoftwareDeviceKeys.DELETE) })
                approved.session.close()
            }
        }

    @Test
    fun `a commit whose caller is cancelled as it takes the approval stores the record, or leaves it to undo`() =
        runTest {
            val stranded = (0..PairingHarness.RACE_YIELDS).mapNotNull { yields -> commitCancelledAfter(this, yields) }

            assertEquals(emptyList<String>(), stranded)
        }

    @Test
    fun `a commit in the pairing's own scope, which ends as it commits, stores the record or undoes the approval`() =
        runTest {
            val stranded = (0..PairingHarness.RACE_YIELDS).mapNotNull { yields -> commitAsScopeEnds(this, yields) }

            assertEquals(emptyList<String>(), stranded)
        }

    @Test
    fun `a commit that comes as the pairing's scope ends, its caller ended with it, takes nothing and fails nothing`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD)
            val scope = harness.faultScope()
            harness.start(scope = scope)
            val approved = harness.approved()
            harness.settle()
            scope.cancel()
            var stored = false
            // Started before the end reaches it, as a commit on another thread is, so the holder abandons first.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                harness.handle.commit {
                    stored = true
                    OLD
                }
            }
            harness.settle()

            assertEquals(emptyList<Throwable>(), harness.faults)
            assertNull(storedOrUndone(harness, approved, stored))
            assertFalse(stored)
        }

    @Test
    fun `instance facts hold the shapes the ceremony builds them in`() {
        val facts =
            InstanceFacts(
                gatewayPk = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
                tlsFp = "4f".repeat(32),
                host = HOST_NAME,
                profile = LINK_PROFILE,
                label = HOST_NAME,
                candidates = listOf(APPROVED_ROUTE),
                port = LINK_PORT,
                deviceId = DEVICE_ID,
                keyAlias = "${DEVICE_KEY_ALIAS_PREFIX}a.b",
                pushSalt = checkNotNull(APPROVAL.pushSalt),
                pushPlatforms = listOf(PushPlatform.FCM),
            )
        val refused =
            listOf<() -> Unit>(
                { facts.copy(gatewayPk = "AAAA") },
                { facts.copy(tlsFp = "4F".repeat(32)) },
                { facts.copy(host = " ") },
                { facts.copy(profile = "") },
                { facts.copy(label = " ") },
                { facts.copy(candidates = emptyList()) },
                { facts.copy(port = 0) },
                { facts.copy(deviceId = "") },
                { facts.copy(keyAlias = "app.lock") },
                { facts.copy(pushSalt = "c2FsdA==") },
            )

        refused.forEach { assertThrows<IllegalArgumentException>(it) }
    }

    @Test
    fun `an approval cancelled before commit closes its session and deletes its key, never the old one`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD)
            val handle = harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            val approved = harness.state<PairingState.Approved>()

            assertTrue(handle.cancel())
            assertEquals(PairingState.Cancelled, handle.state.value)
            assertEquals(SessionState.Closed, approved.session.state.value)
            assertFalse(harness.keys.exists(approved.facts.keyAlias))
            assertTrue(harness.keys.exists(OLD))
            assertThrows<IllegalStateException> { handle.commit { OLD } }
        }

    @Test
    fun `a scope that ends after the approval, before commit, deletes the attempt's key and keeps the old one`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD)
            val scope = harness.childScope()
            val handle = harness.start(scope = scope)
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            val approved = harness.state<PairingState.Approved>()
            scope.cancel()
            harness.settle()

            assertEquals(PairingState.Cancelled, handle.state.value)
            assertEquals(SessionState.Closed, approved.session.state.value)
            assertFalse(harness.keys.exists(approved.facts.keyAlias))
            assertTrue(harness.keys.exists(OLD))
        }

    @Test
    fun `a rename lands only before pair_request goes out, which is before Verify shows`() =
        runTest {
            val harness = PairingHarness(this)
            val handle = harness.start()
            // While Connecting. Section 13.3 step 5 puts the rename on Verify, after pair_request went out;
            // which of the two gives is the owner's decision.
            assertEquals(Rename.RENAMED, handle.rename("Work phone"))
            val connection = harness.paired()
            val read = connection.pairRequest()
            val verify = harness.state<PairingState.Verify>()

            assertEquals("Work phone", read.request.deviceName)
            assertEquals("Work phone", verify.deviceName)
            assertEquals(Rename.ALREADY_SENT, handle.rename("Other"))
            assertEquals(verify, handle.state.value)
            assertThrows<ProtocolException.InvalidField> { handle.rename("bad\u0007name") }
        }

    @Test
    fun `the model is the manufacturer and the model as the daemon's prompt shows them`() {
        assertEquals("Google Pixel 9 Pro", deviceModel("Google", "Pixel 9 Pro"))
        assertThrows<IllegalArgumentException> { deviceModel(" ", "Pixel 9 Pro") }
        assertThrows<IllegalArgumentException> { deviceModel("Google", "") }
    }

    @Test
    fun `cancel while the owner decides closes the socket, deletes the attempt's key and zeroes the secret`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD)
            val handle = harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            harness.state<PairingState.Verify>()

            assertTrue(handle.cancel())
            assertEquals(PairingState.Cancelled, handle.state.value)
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertFalse(harness.keys.exists(harness.keys.generated.single()))
            assertTrue(harness.keys.exists(OLD))
            assertTrue(harness.link.secret.all { it == 0.toByte() })
            assertFalse(handle.cancel())
            assertEquals(Retry.REFUSED, handle.retry())
        }

    @Test
    fun `cancel while the handshake runs closes the socket, deletes the attempt's key and zeroes the secret`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD)
            val handle = harness.start()
            val connection = harness.daemon.accept()
            harness.state<PairingState.Checking>()

            assertTrue(handle.cancel())
            assertEquals(PairingState.Cancelled, handle.state.value)
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertFalse(harness.keys.exists(harness.keys.generated.single()))
            assertTrue(harness.keys.exists(OLD))
            assertTrue(harness.link.secret.all { it == 0.toByte() })
        }

    @Test
    fun `a scope that ends while the owner decides closes the socket and releases the attempt`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD)
            val scope = harness.childScope()
            val handle = harness.start(scope = scope)
            val connection = harness.paired()
            connection.pairRequest()
            harness.state<PairingState.Verify>()
            scope.cancel()
            harness.settle()

            assertEquals(PairingState.Cancelled, handle.state.value)
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertFalse(harness.keys.exists(harness.keys.generated.single()))
            assertTrue(harness.keys.exists(OLD))
            assertTrue(harness.link.secret.all { it == 0.toByte() })
        }
}

/** A commit from [test]'s own scope, cancelled [yields] yields after it was called; what went wrong, if anything. */
private suspend fun commitCancelledAfter(
    test: TestScope,
    yields: Int,
): String? {
    val harness = PairingHarness(test)
    harness.keys.hold(OLD)
    val handle = harness.start()
    val approved = harness.approved()
    var stored = false
    val commit =
        test.launch(start = CoroutineStart.UNDISPATCHED) {
            handle.commit {
                stored = true
                OLD
            }
        }
    repeat(yields) { yield() }
    commit.cancel()
    harness.settle()
    // An approval the commit never took is still the caller's, to undo.
    if (!stored) handle.cancel()
    approved.session.close()
    return storedOrUndone(harness, approved, stored)?.let { "a commit cancelled after $yields yields: $it" }
}

/** A commit in the pairing's own scope, which ends [yields] yields after the commit was called. */
private suspend fun commitAsScopeEnds(
    test: TestScope,
    yields: Int,
): String? {
    val harness = PairingHarness(test)
    harness.keys.hold(OLD)
    val scope = harness.faultScope()
    harness.start(scope = scope)
    val approved = harness.approved()
    var stored = false
    scope.launch {
        harness.handle.commit {
            stored = true
            OLD
        }
    }
    repeat(yields) { yield() }
    scope.cancel()
    harness.settle()
    val wrong = storedOrUndone(harness, approved, stored) ?: harness.faults.firstOrNull()?.let { "it threw $it" }
    return wrong?.let { "the scope ended $yields yields into a commit: $it" }
}

/**
 * Null when [approved] was stored, the old key deleted and the new one kept, or undone, Cancelled with its
 * key deleted and the old one kept; else what is wrong.
 */
private fun storedOrUndone(
    harness: PairingHarness,
    approved: PairingState.Approved,
    stored: Boolean,
): String? {
    val newKey = harness.keys.exists(approved.facts.keyAlias)
    val oldKey = harness.keys.exists(OLD)
    val state = harness.handle.state.value
    val clean = if (stored) newKey && !oldKey else !newKey && oldKey && state == PairingState.Cancelled
    return if (clean) null else "stored $stored, $state, new key kept $newKey, old key kept $oldKey"
}
