package io.tezra.fermix.session

import io.tezra.fermix.attest.AliasNames
import io.tezra.fermix.attest.AttestedKey
import io.tezra.fermix.attest.ChainShapeException
import io.tezra.fermix.attest.DEVICE_KEY_ALIAS_PREFIX
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.protocol.PairDeniedReason
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.VersionDirection
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.random.Random

/** The key this phone holds for the daemon from an earlier pairing, which no failure touches. */
private const val OLD_ALIAS = "${DEVICE_KEY_ALIAS_PREFIX}old"

/** A minute and a half: past the keepalive's two missed pongs, inside the window. */
private const val TWO_PINGS_UNANSWERED_MS = 90_000L

/** The vendored links, on the test classpath through core-session/build.gradle.kts, never copied. */
private const val LINKS_RESOURCE = "/fixtures/pairing_links.jsonl"

/** Message 1's prelude, `FXM1` and the mode byte, which is 2 for a pairing. */
private const val MODE_BYTE_AT = 4
private const val PAIRING_MODE = 2

/** One past the six certificates a chain holds at most. */
private const val SEVEN_CERTIFICATES = 7

/** An issuer's content that takes the chain past its 16 KiB. */
private const val OVERSIZED_ISSUER_BYTES = 16_400

/** Design section 13.3's failure table, a test for each row the wire can produce, and the protocol's own. */
class PairingFailureTest {
    @ParameterizedTest
    @EnumSource(PairDeniedReason::class)
    fun `each pair_denied reason ends as its row, and the attempt's key goes while the old one stays`(
        reason: PairDeniedReason,
    ) = runTest {
        val harness = PairingHarness(this)
        harness.keys.hold(OLD_ALIAS)
        harness.start()
        val connection = harness.paired()
        connection.pairRequest()
        connection.send(ServerEvent.PairDenied(reason))
        connection.close(4003, "pairing ${reason.name.lowercase()}")
        val ended = harness.state<PairingState.Ended>()

        val expected =
            mapOf(
                PairDeniedReason.DENIED to PairingState.Denied,
                PairDeniedReason.CANCELLED to PairingState.Denied,
                PairDeniedReason.TIMEOUT to PairingState.Expired,
                PairDeniedReason.DEVICE_DISCONNECTED to PairingState.LostMidWait,
                PairDeniedReason.ATTESTATION to PairingState.AttestationRefused,
                PairDeniedReason.PLATFORM_UNSUPPORTED to PairingState.AttestationRefused,
                PairDeniedReason.ATTESTATION_UNAVAILABLE to PairingState.AttestationUnavailable,
            )
        assertEquals(expected.getValue(reason), ended)
        assertAttemptReleased(harness, connection)
    }

    @Test
    fun `a 4003 pairing close whose pair_denied did not come still ends as its reason`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.close(4003, "pairing denied")

            assertEquals(PairingState.Denied, harness.state<PairingState.Ended>())
            assertAttemptReleased(harness, connection)
        }

    @Test
    fun `a daemon that refuses the pairing handshake, a closed window or a fifth failure, is Expired`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD_ALIAS)
            harness.start()
            harness.daemon.accept().refuseHandshake(1002, "mobile protocol error")

            assertEquals(PairingState.Expired, harness.state<PairingState.Ended>())
            assertAttemptReleased(harness)
        }

    @Test
    fun `a message 2 that does not authenticate, another window's secret, is Expired with nothing past message 1`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.daemon.accept()
            connection.answerAsAnotherDaemon()

            assertEquals(PairingState.Expired, harness.state<PairingState.Ended>())
            assertAttemptReleased(harness)
            val sent = connection.link.toDaemon
            assertNull(sent.receiveCatching().getOrNull()) { "a frame past message 1 went out" }
        }

    @Test
    fun `a daemon silent past the window and its grace is Expired`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            val started = testScheduler.timeSource.markNow()
            backgroundScope.launch { connection.answerPings(this, 0, mutableListOf()) }

            assertEquals(PairingState.Expired, harness.state<PairingState.Ended>())
            assertTrue(started.elapsedNow().inWholeMilliseconds >= PAIRING_WINDOW_MS)
            assertAttemptReleased(harness, connection)
        }

    @Test
    fun `a 1002 after pair_request is read as another phone's request waiting`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.close(1002, "mobile protocol error")

            assertEquals(PairingState.AnotherPairingInProgress, harness.state<PairingState.Ended>())
            assertAttemptReleased(harness, connection)
        }

    @Test
    fun `a 4001 while the owner decides, the request replaced from another socket, is Lost mid-wait`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD_ALIAS)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.close(4001, "connection replaced")

            assertEquals(PairingState.LostMidWait, harness.state<PairingState.Ended>())
            assertAttemptReleased(harness, connection)
        }

    @Test
    fun `every candidate out of reach is Can't reach, which keeps the link for a retry with a new key`() =
        runTest {
            val harness = PairingHarness(this)
            harness.daemon.refusal = { TransportException.Unreachable(IOException("no route")) }
            val handle = harness.start(harness.linkText(candidates = listOf(TAILNET.host, LAN.host)))
            val ended = assertInstanceOf<PairingState.CannotReach>(harness.state<PairingState.Ended>())
            harness.settle()

            assertEquals(setOf(TAILNET, LAN), ended.failures.keys)
            assertTrue(harness.link.secret.any { it != 0.toByte() })
            val first = harness.keys.generated.single()
            assertFalse(harness.keys.exists(first))

            harness.daemon.refusal = { null }
            assertEquals(Retry.RETRYING, handle.retry())
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL)
            val approved = harness.state<PairingState.Approved>()
            assertEquals(2, harness.keys.generated.size)
            assertEquals(harness.keys.generated.last(), approved.facts.keyAlias)
            assertTrue(first != approved.facts.keyAlias)
            approved.session.close()
        }

    @Test
    fun `a scope that ends on Can't reach zeroes the secret it kept for a retry`() =
        runTest {
            val harness = PairingHarness(this)
            harness.daemon.refusal = { TransportException.Unreachable(IOException("no route")) }
            val scope = harness.childScope()
            val handle = harness.start(scope = scope)
            assertInstanceOf<PairingState.CannotReach>(harness.state<PairingState.Ended>())
            scope.cancel()
            harness.settle()

            assertEquals(PairingState.Cancelled, handle.state.value)
            assertTrue(harness.link.secret.all { it == 0.toByte() }) { "the secret outlived its handle's scope" }
            assertEquals(Retry.REFUSED, handle.retry())
        }

    @Test
    fun `a scope that ends while Can't reach deletes its key still zeroes the secret`() =
        runTest {
            val harness = PairingHarness(this)
            harness.daemon.refusal = { TransportException.Unreachable(IOException("no route")) }
            val scope = harness.childScope()
            val handle = harness.start(keys = EndsScopeOnDelete(harness.keys, scope), scope = scope)
            harness.settle()

            assertEquals(PairingState.Cancelled, handle.state.value)
            assertAttemptReleased(harness)
            assertEquals(Retry.REFUSED, handle.retry())
        }

    @Test
    fun `a scope that ends while a denied pairing deletes its key leaves the daemon's ending showing`() =
        runTest {
            val harness = PairingHarness(this)
            val scope = harness.childScope()
            val handle = harness.start(keys = EndsScopeOnDelete(harness.keys, scope), scope = scope)
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(ServerEvent.PairDenied(PairDeniedReason.DENIED))
            connection.close(4003, "pairing denied")
            harness.settle()

            assertEquals(PairingState.Denied, handle.state.value)
            assertAttemptReleased(harness, connection)
        }

    @Test
    fun `a Keystore that cannot delete a Can't reach's key zeroes the secret, refuses a retry, and fails loud`() =
        runTest {
            val harness = PairingHarness(this)
            harness.daemon.refusal = { TransportException.Unreachable(IOException("no route")) }
            val handle = harness.start(keys = Undeletable(harness.keys), scope = harness.faultScope())
            assertInstanceOf<PairingState.CannotReach>(harness.state<PairingState.Ended>())
            harness.settle()

            assertInstanceOf<KeyStoreException>(harness.faults.single())
            assertTrue(harness.link.secret.all { it == 0.toByte() }) { "a ceremony that failed kept the secret" }
            assertEquals(Retry.REFUSED, handle.retry())
        }

    @Test
    fun `a retry from outside the pairing's scope, as that scope ends, still ends Cancelled with the secret zeroed`() =
        runTest {
            val left = (0..PairingHarness.RACE_YIELDS).mapNotNull { yields -> retryAsScopeEnds(this, yields) }

            assertEquals(emptyList<String>(), left)
        }

    @Test
    fun `a retry and a cancel called together end Cancelled, with no ceremony run after it, in either order`() =
        runTest {
            val left = listOf(true, false).mapNotNull { retryFirst -> retryWithCancel(this, retryFirst) }

            assertEquals(emptyList<String>(), left)
        }

    @Test
    fun `a scope that ends while the Keystore makes the attempt's key deletes that key`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD_ALIAS)
            val scope = harness.childScope()
            val handle = harness.start(keys = EndsScopeOnGenerate(harness.keys, scope), scope = scope)
            harness.settle()

            assertEquals(PairingState.Cancelled, handle.state.value)
            assertEquals(1, harness.keys.generated.size)
            assertEquals(0, harness.daemon.dials)
            assertAttemptReleased(harness)
        }

    @Test
    fun `a scope that ends before the ceremony first runs still ends it Cancelled, with the secret zeroed`() =
        runTest {
            val harness = PairingHarness(this)
            val scope = harness.childScope()
            val handle = harness.start(scope = scope)
            scope.cancel()
            harness.settle()

            assertEquals(PairingState.Cancelled, handle.state.value)
            assertEquals(emptyList<String>(), harness.keys.log)
            assertEquals(0, harness.daemon.dials)
            assertTrue(harness.link.secret.all { it == 0.toByte() })
        }

    @Test
    fun `a pairing is refused on a scope that ended, and nothing is generated or dialed`() =
        runTest {
            val harness = PairingHarness(this)
            val scope = harness.childScope()
            scope.cancel()

            assertThrows<IllegalArgumentException> { harness.start(scope = scope) }
            harness.settle()
            assertEquals(emptyList<String>(), harness.keys.log)
            assertEquals(0, harness.daemon.dials)
        }

    @Test
    fun `a certificate that is not pinned is Wrong machine, with nothing sent and no retry`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD_ALIAS)
            harness.daemon.refusal = { TransportException.PinMismatch(SSLPeerUnverifiedException("not pinned")) }
            val handle = harness.start()

            assertEquals(PairingState.WrongMachine, harness.state<PairingState.Ended>())
            assertEquals(Retry.REFUSED, handle.retry())
            harness.settle()
            assertEquals(1, harness.daemon.dials)
            assertAttemptReleased(harness)
        }

    @Test
    fun `a pin mismatch while another candidate is mid-handshake is Wrong machine, and only message 1 went out`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.hold(OLD_ALIAS)
            harness.daemon.refusal = {
                if (it == LAN) TransportException.PinMismatch(SSLPeerUnverifiedException("not pinned")) else null
            }
            val handle = harness.start(harness.linkText(candidates = listOf(TAILNET.host, LAN.host)))
            val tailnet = harness.daemon.accept()

            assertEquals(PairingState.WrongMachine, harness.state<PairingState.Ended>())
            assertEquals(Retry.REFUSED, handle.retry())
            assertEquals(listOf(TAILNET, LAN), harness.daemon.dialed)
            val sent = tailnet.link.toDaemon
            val first = checkNotNull(sent.receiveCatching().getOrNull())
            assertEquals(PAIRING_MODE, first[MODE_BYTE_AT].toInt())
            assertNull(sent.receiveCatching().getOrNull()) { "a frame past message 1 went out" }
            assertAttemptReleased(harness, tailnet)
        }

    @Test
    fun `a version-1 link, the vendored one, is Older Fermix, refused before any key is made or socket opened`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start(vendoredVersionOneLink())

            assertEquals(PairingState.OlderFermix, harness.state<PairingState.Ended>())
            assertEquals(emptyList<String>(), harness.keys.log)
            assertEquals(0, harness.daemon.dials)
            assertTrue(harness.link.secret.all { it == 0.toByte() })
        }

    @Test
    fun `a link whose candidate is outside the LAN and tailnet ranges is invalid, before any key`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start(harness.linkText(candidates = listOf(TAILNET.host, "8.8.8.8")))

            assertInstanceOf<PairingState.InvalidLink>(harness.state<PairingState.Ended>())
            assertEquals(emptyList<String>(), harness.keys.log)
            assertEquals(0, harness.daemon.dials)
        }

    @Test
    fun `a version refusal says which side to update`() =
        runTest {
            val endings = mutableListOf<PairingState>()
            // An older daemon answers at its own version 1; a newer one's window starts past 2.
            val refusals =
                listOf(
                    versionRefusal(VersionDirection.CLIENT_TOO_NEW, 1) to 1,
                    versionRefusal(VersionDirection.CLIENT_TOO_OLD, 3) to 2,
                )
            for ((refusal, v) in refusals) {
                val harness = PairingHarness(this)
                harness.start()
                val connection = harness.paired()
                connection.pairRequest()
                connection.send(refusal, v)
                connection.close(1002, "unsupported mobile protocol version")
                endings += harness.state<PairingState.Ended>()
                assertAttemptReleased(harness, connection)
            }

            assertEquals(listOf(PairingState.OlderFermix, PairingState.NewerFermix), endings)
        }

    @Test
    fun `a Keystore that cannot make the key is No secure hardware, and nothing is dialed`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.fault = ProviderException("no StrongBox, no TEE curve25519")
            harness.start()
            val ended = assertInstanceOf<PairingState.NoSecureHardware>(harness.state<PairingState.Ended>())

            assertInstanceOf<ProviderException>(ended.cause)
            assertEquals(0, harness.daemon.dials)
            assertTrue(harness.link.secret.all { it == 0.toByte() })
        }

    @Test
    fun `a Keystore that cannot even look an alias up is No secure hardware, with the secret zeroed`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start(keys = Unreadable(harness.keys))
            val ended = assertInstanceOf<PairingState.NoSecureHardware>(harness.state<PairingState.Ended>())

            assertInstanceOf<KeyStoreException>(ended.cause)
            assertEquals(emptyList<String>(), harness.keys.log)
            assertEquals(0, harness.daemon.dials)
            assertTrue(harness.link.secret.all { it == 0.toByte() })
        }

    @Test
    fun `a chain whose leaf carries another key is No secure hardware, and its key is deleted`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.foreignLeaf = true
            harness.start()
            val ended = assertInstanceOf<PairingState.NoSecureHardware>(harness.state<PairingState.Ended>())

            assertInstanceOf<ChainShapeException.LeafKeyMismatch>(ended.cause)
            assertEquals(0, harness.daemon.dials)
            assertAttemptReleased(harness)
        }

    @Test
    fun `a chain of seven certificates is No secure hardware before any dial`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.extraCertificates = List(SEVEN_CERTIFICATES - 2) { issuerCertificate() }
            harness.start()
            val ended = assertInstanceOf<PairingState.NoSecureHardware>(harness.state<PairingState.Ended>())

            assertEquals(SEVEN_CERTIFICATES, assertInstanceOf<ChainShapeException.CertificateCount>(ended.cause).count)
            assertEquals(0, harness.daemon.dials)
            assertAttemptReleased(harness)
        }

    @Test
    fun `a chain past 16 KiB is No secure hardware before any dial`() =
        runTest {
            val harness = PairingHarness(this)
            harness.keys.extraCertificates = listOf(issuerCertificate(OVERSIZED_ISSUER_BYTES))
            harness.start()
            val ended = assertInstanceOf<PairingState.NoSecureHardware>(harness.state<PairingState.Ended>())

            assertInstanceOf<ChainShapeException.TooLarge>(ended.cause)
            assertEquals(0, harness.daemon.dials)
            assertAttemptReleased(harness)
        }

    @Test
    fun `an attempt alias the Keystore holds already fails loud, and the key under it is never touched`() =
        runTest {
            val harness = PairingHarness(this)
            val held = AliasNames.next(harness.daemon.gatewayKey.publicKey, Random(PairingHarness.SEED))
            harness.keys.hold(held)
            harness.start(scope = harness.faultScope())
            harness.settle()

            assertInstanceOf<IllegalStateException>(harness.faults.single())
            assertTrue(harness.keys.exists(held))
            assertEquals(emptyList<String>(), harness.keys.log)
            assertEquals(0, harness.daemon.dials)
            assertTrue(harness.link.secret.all { it == 0.toByte() })
        }

    @Test
    fun `a Keystore that cannot delete the attempt's key still ends the pairing, then fails loud`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start(keys = Undeletable(harness.keys), scope = harness.faultScope())
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(ServerEvent.PairDenied(PairDeniedReason.DENIED))
            connection.close(4003, "pairing denied")

            assertEquals(PairingState.Denied, harness.state<PairingState.Ended>())
            harness.settle()
            assertInstanceOf<KeyStoreException>(harness.faults.single())
            assertTrue(harness.link.secret.all { it == 0.toByte() })
            assertTrue(connection.link.phoneClose.isCompleted)
        }

    @Test
    fun `two pings unanswered while the owner decides is Lost mid-wait`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            harness.state<PairingState.Verify>()
            val started = testScheduler.timeSource.markNow()

            assertEquals(PairingState.LostMidWait, harness.state<PairingState.Ended>())
            assertTrue(started.elapsedNow().inWholeMilliseconds <= TWO_PINGS_UNANSWERED_MS)
            assertAttemptReleased(harness, connection)
        }

    @Test
    fun `the connection dropping while the owner decides is Lost mid-wait`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.link.fail(IOException("connection reset"))

            assertEquals(PairingState.LostMidWait, harness.state<PairingState.Ended>())
            assertAttemptReleased(harness, connection)
        }

    @Test
    fun `an event out of place while the owner decides is a protocol error, closed with 1002`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(HELLO_ACK)

            assertInstanceOf<PairingState.ProtocolError>(harness.state<PairingState.Ended>())
            assertEquals(PROTOCOL_ERROR, connection.phoneClosed().code)
            assertAttemptReleased(harness, connection)
        }

    @Test
    fun `an approval without the session's profile is a protocol error, and pairs nothing`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.send(APPROVAL.copy(profiles = emptyList()))

            assertInstanceOf<PairingState.ProtocolError>(harness.state<PairingState.Ended>())
            assertEquals(PROTOCOL_ERROR, connection.phoneClosed().code)
            assertAttemptReleased(harness, connection)
        }

    @Test
    fun `an event a newer daemon adds is passed over while the owner decides`() =
        runTest {
            val harness = PairingHarness(this)
            harness.start()
            val connection = harness.paired()
            connection.pairRequest()
            connection.sendUnknown("pair_progress")
            connection.send(APPROVAL)

            assertInstanceOf<PairingState.Approved>(harness.state<PairingState.Approved>()).session.close()
        }
}

/** A Keystore whose every lookup fails, as one that is down does. */
private class Unreadable(
    private val keys: SoftwareDeviceKeys,
) : DeviceKeyFacade by keys {
    override fun exists(alias: String): Boolean = throw KeyStoreException("the Keystore is down")
}

/** A Keystore that makes keys and cannot delete them. */
private class Undeletable(
    private val keys: SoftwareDeviceKeys,
) : DeviceKeyFacade by keys {
    override fun delete(alias: String): Unit = throw KeyStoreException("the Keystore is busy")
}

/** A Keystore whose generate returns as [scope] ends: the owner left while the TEE made the key. */
private class EndsScopeOnGenerate(
    private val keys: SoftwareDeviceKeys,
    private val scope: CoroutineScope,
) : DeviceKeyFacade by keys {
    override fun generate(
        alias: String,
        challenge: ByteArray,
    ): AttestedKey = keys.generate(alias, challenge).also { scope.cancel() }
}

/** A Keystore whose delete returns as [scope] ends. */
private class EndsScopeOnDelete(
    private val keys: SoftwareDeviceKeys,
    private val scope: CoroutineScope,
) : DeviceKeyFacade by keys {
    override fun delete(alias: String) {
        keys.delete(alias)
        scope.cancel()
    }
}

/** A pairing in a scope of its own that ended as Can't reach, every candidate out of reach. */
private suspend fun cannotReach(test: TestScope): Pair<PairingHarness, CoroutineScope> {
    val harness = PairingHarness(test)
    harness.daemon.refusal = { TransportException.Unreachable(IOException("no route")) }
    val scope = harness.childScope()
    harness.start(scope = scope)
    harness.state<PairingState.CannotReach>()
    harness.settle()
    return harness to scope
}

/** A retry from [test]'s own scope, the pairing's scope ended [yields] yields later; what it left, if anything. */
private suspend fun retryAsScopeEnds(
    test: TestScope,
    yields: Int,
): String? {
    val (harness, scope) = cannotReach(test)
    val retry = test.async(start = CoroutineStart.UNDISPATCHED) { harness.handle.retry() }
    repeat(yields) { yield() }
    scope.cancel()
    retry.await()
    harness.settle()
    return leftOver(harness, "a retry $yields yields before its scope ended")
}

/**
 * A retry and a cancel called together, the retry first when [retryFirst]; what they left, if anything. The
 * cancel ends the pairing whichever goes first, so nothing may follow its Cancelled.
 */
private suspend fun retryWithCancel(
    test: TestScope,
    retryFirst: Boolean,
): String? {
    val (harness, _) = cannotReach(test)
    val handle = harness.handle
    val cancelFirst = if (retryFirst) null else test.async(start = CoroutineStart.UNDISPATCHED) { handle.cancel() }
    val retry = test.async(start = CoroutineStart.UNDISPATCHED) { handle.retry() }
    val cancel = cancelFirst ?: test.async(start = CoroutineStart.UNDISPATCHED) { handle.cancel() }
    retry.await()
    val cancelled = cancel.await()
    harness.settle()
    val after = harness.states.dropWhile { it != PairingState.Cancelled }.drop(1)
    val order = if (retryFirst) "before" else "after"
    val what = "a retry $order a cancel, the cancel $cancelled, states after it $after"
    return leftOver(harness, what) ?: what.takeIf { !cancelled || after.isNotEmpty() }
}

/** Null when the pairing ended Cancelled with its secret zeroed and no key of its own left; else what [what] left. */
private fun leftOver(
    harness: PairingHarness,
    what: String,
): String? {
    val zeroed = harness.link.secret.all { it == 0.toByte() }
    val keysLeft = harness.keys.generated.filter(harness.keys::exists)
    val state = harness.handle.state.value
    val clean = zeroed && keysLeft.isEmpty() && state == PairingState.Cancelled
    return if (clean) null else "$what: $state, secret zeroed $zeroed, keys left $keysLeft"
}

private fun versionRefusal(
    direction: VersionDirection,
    daemonVersion: Int,
): ServerEvent.Error =
    ServerEvent.Error(
        code = ServerEvent.Error.UNSUPPORTED_PROTOCOL_VERSION,
        message = "client protocol 2 is outside $daemonVersion..$daemonVersion",
        direction = direction,
        clientVersion = 2,
        minVersion = daemonVersion,
        maxVersion = daemonVersion,
    )

/** The vendored link an older Fermix writes, version 1, from fixtures/pairing_links.jsonl. */
private fun vendoredVersionOneLink(): String {
    val stream =
        checkNotNull(PairingFailureTest::class.java.getResourceAsStream(LINKS_RESOURCE)) {
            "$LINKS_RESOURCE is not on the test classpath; core-session/build.gradle.kts puts contracts/mobile there"
        }
    val line = stream.use { it.readBytes().decodeToString() }.lineSequence().first { it.isNotBlank() }
    return Json
        .parseToJsonElement(line)
        .jsonObject
        .getValue("uri")
        .jsonPrimitive.content
        .also { check("v=1&" in it) { "the vendored link is version 1" } }
}

/**
 * The attempt's key is gone, a key the phone held before, when it did, is still held, the secret is zeroed,
 * and the socket the race won, [connection]'s, is closed from the phone's side.
 */
private suspend fun assertAttemptReleased(
    harness: PairingHarness,
    connection: DaemonConnection? = null,
) {
    harness.settle()
    harness.keys.generated.forEach { assertFalse(harness.keys.exists(it)) { "$it outlived its attempt" } }
    assertFalse(harness.keys.log.any { it.endsWith(OLD_ALIAS) }) { "$OLD_ALIAS was touched" }
    assertTrue(harness.link.secret.all { it == 0.toByte() }) { "the secret outlived the attempt" }
    connection?.let { assertTrue(it.link.phoneClose.isCompleted) { "the phone left the socket open" } }
}
