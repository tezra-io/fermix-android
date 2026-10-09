package io.tezra.fermix.session

import io.tezra.fermix.demo.SoftwareKey
import io.tezra.fermix.noise.NoiseException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows

/**
 * One candidate's attempt, the socket and Noise IK: what it opens closes with it on every path, the
 * winner's link and Noise keys together, and a failed or timed-out attempt's socket at once.
 */
class HandshakeTest {
    @Test
    fun `a won handshake closes its link and wipes its Noise session together`() =
        runTest {
            val daemon = FakeDaemon()
            val attempt = async { handshake(daemon, TAILNET, SoftwareKey.generate(), daemon.gatewayKey.publicKey) }
            val connection = daemon.accept()
            connection.handshake()
            val won = attempt.await()
            won.close()
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertThrows<NoiseException.OutOfOrder> { won.noise.encrypt(ByteArray(1)) }
        }

    @Test
    fun `a message 2 that does not authenticate closes the socket`() =
        runTest {
            val daemon = FakeDaemon()
            val attempt =
                async {
                    runCatching { handshake(daemon, TAILNET, SoftwareKey.generate(), daemon.gatewayKey.publicKey) }
                }
            val connection = daemon.accept()
            connection.answerAsAnotherDaemon()
            assertInstanceOf<NoiseException.AuthenticationFailed>(attempt.await().exceptionOrNull())
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
        }

    @Test
    fun `a daemon that never answers message 1 times out, and the socket closes`() =
        runTest {
            val started = testScheduler.timeSource.markNow()
            val daemon = FakeDaemon()
            val attempt =
                async {
                    runCatching { handshake(daemon, TAILNET, SoftwareKey.generate(), daemon.gatewayKey.publicKey) }
                }
            val connection = daemon.accept()
            assertInstanceOf<TimeoutCancellationException>(attempt.await().exceptionOrNull())
            assertEquals(NORMAL_CLOSURE, connection.phoneClosed().code)
            assertEquals(HANDSHAKE_TIMEOUT_MS, started.elapsedNow().inWholeMilliseconds)
        }
}
