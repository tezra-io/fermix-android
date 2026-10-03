package io.tezra.fermix.session

import io.tezra.fermix.attest.DEVICE_KEY_ALIAS_PREFIX
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.protocol.requirePairRequestText
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.TimeSource

/** 32 bytes in standard base64 with its padding, as the wire writes `gateway_pk` and `push_salt`. */
private val BASE64_32_BYTES = Regex("[A-Za-z0-9+/]{43}=")

/** A SHA-256 in lowercase hex, as the pin `tls_fp` is kept. */
private val SHA256_HEX = Regex("[0-9a-f]{64}")

private const val MAX_PORT = 65_535

/**
 * Who this phone is to the daemon it pairs with (design section 6.3): [deviceName], the owner's name for
 * the phone, which [PairingHandle.rename] changes until `pair_request` goes out, as the handshake
 * completes and before Verify shows; [model], [deviceModel] of the phone's Build; and [appVersion]. The
 * three are `pair_request`'s texts, held to the codec's own rule for them.
 */
data class PhoneIdentity(
    val deviceName: String,
    val model: String,
    val appVersion: String,
) {
    init {
        requirePairRequestText("device_name", deviceName)
        requirePairRequestText("model", model)
        requirePairRequestText("app_version", appVersion)
    }
}

/**
 * Design section 6.3's `model`, `Build.MANUFACTURER + Build.MODEL`, joined by one space as the daemon's
 * approval prompt shows it: "Google Pixel 9 Pro". The feature layer passes the two Build fields.
 */
fun deviceModel(
    manufacturer: String,
    model: String,
): String {
    require(manufacturer.isNotBlank()) { "Build names the phone's manufacturer" }
    require(model.isNotBlank()) { "Build names the phone's model" }
    return "$manufacturer $model"
}

/**
 * What a pairing runs on, all of it the app's, and what the session it hands over then runs on:
 * [dialerFor], which makes the dialer of a port and a `tls_fp` pin, and is given the link's own; the
 * profile the first chat opens on, `main`, with that (instance, profile)'s store and the announcer; the
 * network facts, the clock and the randomness, which picks the attempt's key alias; [keystore], the
 * dispatcher every blocking DeviceKeyFacade call runs on, off the main thread; and [sessionScope], the
 * scope the approved session runs in, the one the app keeps its sessions in, so that the session outlives
 * the screens that paired it and the app takes it over as it is. SessionParts without the static key,
 * which the pairing makes, and the app's version, which [PhoneIdentity] carries. A test passes fakes, the
 * virtual clock and its own dispatcher.
 */
data class PairingParts(
    val dialerFor: (port: Int, tlsFingerprint: ByteArray) -> Dialer,
    val profileId: String,
    val store: SessionStore,
    val announcer: Announcer,
    val network: StateFlow<NetworkFacts>,
    val keystore: CoroutineDispatcher,
    val sessionScope: CoroutineScope,
    val clock: TimeSource = TimeSource.Monotonic,
    val random: Random = Random.Default,
)

/**
 * What a pairing tells the instance record of design section 9.1, the data layer's to write: the keys in
 * the wire's text ([gatewayPk] and [pushSalt] in standard base64, [tlsFp] in lowercase hex), [host] and
 * [label] the link's name until the first `hello_ack` names them, [profile] the link's, the approval's
 * [candidates] (the link's when it names none), [keyAlias] the attempt's new key, and [pushPlatforms]. The
 * `caps` snapshot comes with the `hello_ack` the session announces. [id] is `sha256(gateway_pk)`. Each
 * field is held to the shape the link's parse and core-protocol already gave it, so only facts built
 * elsewhere, never a daemon's, are refused.
 */
data class InstanceFacts(
    val gatewayPk: String,
    val tlsFp: String,
    val host: String,
    val profile: String,
    val label: String,
    val candidates: List<Candidate>,
    val port: Int,
    val deviceId: String,
    val keyAlias: String,
    val pushSalt: String,
    val pushPlatforms: List<PushPlatform>,
) {
    init {
        require(BASE64_32_BYTES.matches(gatewayPk)) { "gateway_pk is not 32 bytes in standard base64" }
        require(SHA256_HEX.matches(tlsFp)) { "tls_fp is not a SHA-256 in lowercase hex" }
        require(host.isNotBlank() && label.isNotBlank()) { "a pairing names its host and label" }
        require(profile.isNotBlank()) { "a pairing names its profile" }
        require(candidates.isNotEmpty()) { "a pairing has a route to its daemon" }
        require(port in 1..MAX_PORT) { "port $port is outside 1 to $MAX_PORT" }
        require(deviceId.isNotEmpty()) { "a pairing has a device id" }
        require(keyAlias.startsWith(DEVICE_KEY_ALIAS_PREFIX)) { "$keyAlias is not a device key's alias" }
        require(BASE64_32_BYTES.matches(pushSalt)) { "push_salt is not 32 bytes in standard base64" }
    }

    val id: String = instanceId(Base64.getDecoder().decode(gatewayPk))
}

/**
 * The pairing ceremony of design section 6.3 and PROTOCOL.md "Noise modes and pairing", as one driver:
 * the link checked on the phone, a new attested device key, the race with IKpsk2, `pair_request` with the
 * chain, the SAS, the owner's decision, and on approval the same connection handed on as a paired
 * session. Its states are the onboarding screens' (design section 13.3).
 */
object Pairing {
    /**
     * Starts pairing over [link], a version-2 link the owner scanned or pasted, in [scope], whose
     * dispatcher it runs on one coroutine at a time. The handshake's agreements with the Keystore key run
     * there, as a session's do, so that dispatcher is not the main thread's; the Keystore calls themselves
     * run on [PairingParts.keystore]. The handle owns the link's secret from here on and zeroes it, at the
     * latest when [scope] ends; a link pairs once. A link the parse refused, a newer one among them
     * (ProtocolException.NewerLinkVersion), never gets here, and nor does a scope that ended already. The
     * session an approval hands over runs in [PairingParts.sessionScope]; one no caller commits is closed when
     * [scope] ends, as [PairingHandle.cancel] closes it.
     */
    fun start(
        link: PairingLink,
        keys: DeviceKeyFacade,
        identity: PhoneIdentity,
        parts: PairingParts,
        scope: CoroutineScope,
    ): PairingHandle {
        require(link.secret.any { it != 0.toByte() }) { "the link's secret is zeroed: a link pairs once" }
        require(parts.profileId.isNotEmpty()) { "a pairing names the profile its first chat opens on" }
        val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
        requireNotNull(dispatcher) { "a pairing's scope names the dispatcher it runs on" }
        val job = requireNotNull(scope.coroutineContext[Job]) { "a pairing's scope has a job, whose end cancels it" }
        require(job.isActive) { "a pairing's scope is active; one that ended would never run it" }
        val sessionJob = parts.sessionScope.coroutineContext[Job]
        requireNotNull(sessionJob) { "the session scope has a job, whose end ends the session" }
        val handle = PairingHandle(link, keys, identity, parts, scope, dispatcher.limitedParallelism(1))
        handle.begin()
        return handle
    }
}

/** `sha256(gateway_pk)` in lowercase hex, the key of everything the phone keeps for a daemon. */
internal fun instanceId(gatewayPk: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(gatewayPk).toHexString()
