package io.tezra.fermix.push

import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.data.Instance
import io.tezra.fermix.noise.NoiseException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.GeneralSecurityException
import java.security.ProviderException

/** A push [instance]'s key opened: the bucket its tag verified, handed out as a copy. */
class Opened(
    val instance: Instance,
    padded: ByteArray,
) {
    private val bytes = padded.copyOf()

    fun padded(): ByteArray = bytes.copyOf()
}

/**
 * The phone's side of a push with no routing id (design section 10): each paired instance's push key in
 * record order, one Keystore agreement each through [keys] with the daemon's static key, until a tag
 * verifies. The instance a notification names, its channel, its shortcut and its tap come from the record
 * whose key verified, never from the plaintext. An instance whose agreement fails, its key gone from the
 * Keystore for one, is [log]ged by its id and passed over, and the trial goes on. Each agreement and each
 * key is zeroed once tried. Every call is a blocking Keystore operation, run off the main thread.
 *
 * The trial is bounded twice: by the records it is handed, one agreement each at most, and by its caller's
 * budget, as cancelling its coroutine starts no further agreement. The one under way finishes, since a
 * Keystore call cannot be interrupted, and the trial then ends with the coroutine's cancellation.
 */
class TrialDecrypt(
    private val keys: DeviceKeyFacade,
    private val log: (String, Throwable?) -> Unit,
) {
    /** The first of [instances] whose push key opens [envelope], none when no key does. */
    suspend fun open(
        envelope: PushEnvelope,
        instances: List<Instance>,
    ): Opened? {
        val nonce = envelope.nonce()
        val sealed = envelope.sealed()
        for (instance in instances) {
            currentCoroutineContext().ensureActive()
            val padded = openWith(instance, nonce, sealed)
            if (padded != null) return Opened(instance, padded)
        }
        return null
    }

    /** [sealed] under [instance]'s push key, none when its tag does not verify or its agreement failed. */
    private fun openWith(
        instance: Instance,
        nonce: ByteArray,
        sealed: ByteArray,
    ): ByteArray? {
        val shared = agreement(instance) ?: return null
        val key =
            try {
                PushKeys.derive(shared, instance.pushSaltBytes())
            } finally {
                shared.fill(0)
            }
        try {
            return PushCipher.open(key, nonce, sealed)
        } finally {
            key.fill(0)
        }
    }

    /** X25519 of [instance]'s device key with its daemon's, in the Keystore; none, logged, when it fails. */
    private fun agreement(instance: Instance): ByteArray? =
        try {
            keys.staticKey(instance.keyAlias).agree(instance.gatewayPublicKey())
        } catch (fault: GeneralSecurityException) {
            noAgreement(instance, fault)
        } catch (fault: ProviderException) {
            noAgreement(instance, fault)
        } catch (fault: IllegalStateException) {
            noAgreement(instance, fault)
        } catch (fault: NoiseException) {
            noAgreement(instance, fault)
        }

    private fun noAgreement(
        instance: Instance,
        fault: Exception,
    ): ByteArray? {
        log("no push key for ${instance.id}: its agreement failed", fault)
        return null
    }
}
