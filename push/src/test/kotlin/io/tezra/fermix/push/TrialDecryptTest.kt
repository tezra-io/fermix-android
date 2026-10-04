package io.tezra.fermix.push

import io.tezra.fermix.attest.AttestedKey
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.data.Instance
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

private const val KEY_BYTES = 32

private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** A record of the daemon whose static public key is [gatewayPk], paired under [alias] with [salt]. */
internal fun pairedRecord(
    gatewayPk: ByteArray,
    alias: String,
    salt: ByteArray = ByteArray(KEY_BYTES) { 0x73 },
): Instance =
    Instance(
        gatewayPk = base64(gatewayPk),
        tlsFp = "ab".repeat(KEY_BYTES),
        host = "suj-mbp",
        profile = "fermix",
        label = "suj-mbp",
        tint = "Slate",
        candidates = listOf(Candidate("100.101.102.1", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
        port = 4031,
        deviceId = "device-$alias",
        keyAlias = alias,
        pushSalt = base64(salt),
        pushPlatforms = emptyList(),
        notificationsEnabled = true,
    )

/** The device keys as software keys by alias; an alias with none is the Keystore's missing key. */
internal class SoftwareKeys(
    private val byAlias: Map<String, StaticKey>,
) : DeviceKeyFacade {
    val agreed = mutableListOf<String>()

    /** What runs after each agreement: the test's own step, nothing by default. */
    var afterAgreement: () -> Unit = {}

    override fun generate(
        alias: String,
        challenge: ByteArray,
    ): AttestedKey = error("a push generates no key")

    override fun delete(alias: String): Unit = error("a push deletes no key")

    override fun exists(alias: String): Boolean = alias in byAlias

    override fun staticKey(alias: String): StaticKey {
        val key = checkNotNull(byAlias[alias]) { "no key under $alias" }
        return object : StaticKey {
            override val publicKey: ByteArray get() = key.publicKey

            override fun agree(peerPublicKey: ByteArray): ByteArray {
                agreed += alias
                return key.agree(peerPublicKey).also { afterAgreement() }
            }
        }
    }
}

/**
 * The trial of design section 10: no routing id, so each instance's push key in record order, one agreement
 * each, until a tag verifies; the first that does names the notification's instance.
 */
class TrialDecryptTest {
    private val vector = pushVector()
    private val logged = mutableListOf<String>()

    /** The vector's daemon and device as the second record, behind another daemon's that is tried first. */
    private val owner =
        pairedRecord(vector.hex("gateway_static_public"), "fermix.device.owner", vector.hex("apns_key_salt"))
    private val other = pairedRecord(SoftwareKey(ByteArray(KEY_BYTES) { 0x21 }).publicKey, "fermix.device.other")
    private val keys =
        SoftwareKeys(
            mapOf(
                owner.keyAlias to SoftwareKey(vector.hex("device_static_private")),
                other.keyAlias to SoftwareKey(ByteArray(KEY_BYTES) { 0x42 }),
            ),
        )
    private val trial = TrialDecrypt(keys) { message, _ -> logged += message }

    private fun open(
        envelope: PushEnvelope,
        instances: List<Instance>,
    ): Opened? = runBlocking { trial.open(envelope, instances) }

    private fun envelope(json: String): PushEnvelope {
        val sealed = seal(vector.hex("push_key"), vector.hex("nonce"), padded(json))
        return (PushEnvelope.read(fcmData(vector.hex("nonce"), sealed)) as EnvelopeRead.Read).envelope
    }

    private val message = """{"kind":"message","profile_id":"main","server_seq":3,"preview_text":"done"}"""

    @Test
    fun `with two instances where the second owns the push, both are tried in order and it is named`() {
        val opened = checkNotNull(open(envelope(message), listOf(other, owner)))
        assertSame(owner, opened.instance)
        assertArrayEquals(padded(message), opened.padded())
        assertEquals(listOf(other.keyAlias, owner.keyAlias), keys.agreed)
    }

    @Test
    fun `the first instance whose key verifies ends the trial, and the rest cost no agreement`() {
        open(envelope(message), listOf(owner, other))
        assertEquals(listOf(owner.keyAlias), keys.agreed)
    }

    @Test
    fun `no key that verifies opens nothing, after one agreement per instance`() {
        val forged = (PushEnvelope.read(fcmData(vector.hex("nonce"), ByteArray(SEALED_BYTES))) as EnvelopeRead.Read)
        assertNull(open(forged.envelope, listOf(other, owner)))
        assertEquals(listOf(other.keyAlias, owner.keyAlias), keys.agreed)
    }

    @Test
    fun `once its budget is spent, its coroutine cancelled, the trial starts no further agreement`() {
        lateinit var trialRun: Job
        keys.afterAgreement = { trialRun.cancel() }
        runBlocking {
            trialRun = launch(start = CoroutineStart.LAZY) { trial.open(envelope(message), listOf(other, owner)) }
            trialRun.join()
        }
        assertTrue(trialRun.isCancelled)
        assertEquals(listOf(other.keyAlias), keys.agreed)
    }

    @Test
    fun `an instance whose key the Keystore lost is logged by its id and passed over`() {
        val lost = pairedRecord(ByteArray(KEY_BYTES) { 9 }, "fermix.device.lost")
        val opened = open(envelope(message), listOf(lost, owner))
        assertSame(owner, opened?.instance)
        assertEquals(listOf("no push key for ${lost.id}: its agreement failed"), logged)
    }
}
