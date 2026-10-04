package io.tezra.fermix.push

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.attest.AliasNames
import io.tezra.fermix.attest.DeviceKeys
import io.tezra.fermix.data.Instance
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.time.measureTimedValue

private const val TAG = "KeystorePushTest"
private const val KEY_BYTES = 32
private const val CHALLENGE_BYTES = 32
private const val XDH = "XDH"

/** The DER prefix of an X25519 SubjectPublicKeyInfo, before the raw 32 bytes. */
private const val X25519_SPKI_PREFIX = "302a300506032b656e032100"

/** The PIN the locked test puts on the screen lock, and clears again. */
private const val PIN = "1357"

/** How long the screen may take to lock once asked, or to come back unlocked, and how often the test looks. */
private const val LOCK_WAIT_MILLIS = 10_000L
private const val POLL_MILLIS = 100L

/** [command] run by the device's shell, as the instrumentation may run it, and what it printed. */
private fun shell(command: String): String {
    val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
}

/**
 * [block] with a PIN on the device's screen lock, as an owner's phone has one, then the device as it was,
 * however [block] ends: awake, the PIN cleared and the keyguard gone (AGENTS.md: a test that changes the device
 * puts it back). A device that does not come back fails the test, after [block]'s own failure if it had one.
 */
private fun <T> withPin(
    keyguard: KeyguardManager,
    block: () -> T,
): T {
    shell("locksettings set-pin $PIN")
    val ran = runCatching(block)
    val restored = runCatching { unlock(keyguard) }
    restored.exceptionOrNull()?.let { fault -> ran.exceptionOrNull()?.addSuppressed(fault) ?: throw fault }
    return ran.getOrThrow()
}

/**
 * The PIN cleared and the keyguard dismissed, asked again until the device is neither secure nor locked; past
 * [LOCK_WAIT_MILLIS] it fails, naming what is left.
 */
private fun unlock(keyguard: KeyguardManager) {
    shell("input keyevent KEYCODE_WAKEUP")
    shell("locksettings clear --old $PIN")
    repeat((LOCK_WAIT_MILLIS / POLL_MILLIS).toInt()) {
        if (!keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) return
        shell("wm dismiss-keyguard")
        Thread.sleep(POLL_MILLIS)
    }
    val left = "secure ${keyguard.isDeviceSecure}, locked ${keyguard.isKeyguardLocked}"
    throw AssertionError("the device did not come back unlocked in ${LOCK_WAIT_MILLIS}ms: $left")
}

/** The screen locked behind the PIN, as its owner's power button locks it; past [LOCK_WAIT_MILLIS] it fails. */
private fun lockScreen(keyguard: KeyguardManager) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    assertTrue("the screen lock holds a PIN", keyguard.isDeviceSecure)
    assertTrue("the screen locks", automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
    repeat((LOCK_WAIT_MILLIS / POLL_MILLIS).toInt()) {
        if (keyguard.isDeviceLocked) return
        Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("the device was not locked ${LOCK_WAIT_MILLIS}ms after it was asked to lock")
}

private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** A software X25519 pair, the daemon's side: the platform's XDH makes X25519 with no parameters given. */
private fun newX25519Pair(): KeyPair = KeyPairGenerator.getInstance(XDH).generateKeyPair()

private fun raw(pair: KeyPair): ByteArray =
    pair.public.encoded
        .takeLast(KEY_BYTES)
        .toByteArray()

/** The daemon's side of X25519, in software, with this phone's raw public key [devicePublic]. */
private fun agree(
    gateway: KeyPair,
    devicePublic: ByteArray,
): ByteArray {
    val spki = X509EncodedKeySpec(X25519_SPKI_PREFIX.hexToByteArray() + devicePublic)
    val agreement = KeyAgreement.getInstance(XDH)
    agreement.init(gateway.private)
    agreement.doPhase(KeyFactory.getInstance(XDH).generatePublic(spki), true)
    return agreement.generateSecret()
}

/**
 * Design section 10's trial on the phone itself: an X25519 agree key generated in this device's
 * AndroidKeyStore, as a pairing generates it, opens a push the daemon's side seals at test time in software,
 * behind a record whose key the Keystore does not hold, which is passed over; and, for section 15.3's "FCM
 * decrypt while locked" as far as an emulator goes, it opens one with the screen locked behind a PIN after
 * the key was made unlocked, as a pairing makes it. What an emulator cannot show is the owner's device gate
 * (onboarding section 6): a real phone's TEE or StrongBox doing the agreement, locked or not, its timing, a
 * push FCM delivers to a phone asleep and locked, and attestation to Google's root.
 */
@RunWith(AndroidJUnit4::class)
class KeystorePushTest {
    private val keys = DeviceKeys()
    private val gateway: KeyPair = newX25519Pair()
    private val alias = AliasNames.next(raw(gateway), Random.Default)
    private val lostAlias = AliasNames.next(raw(gateway), Random.Default)
    private val salt = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    @After
    fun deleteTheKey() {
        if (keys.exists(alias)) keys.delete(alias)
    }

    private fun record(keyAlias: String): Instance =
        Instance(
            gatewayPk = base64(raw(gateway)),
            tlsFp = "ab".repeat(KEY_BYTES),
            host = "suj-mbp",
            profile = "fermix",
            label = "suj-mbp",
            tint = "Slate",
            candidates = listOf(Candidate("100.101.102.1", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
            port = 4031,
            deviceId = "device-1",
            keyAlias = keyAlias,
            pushSalt = base64(salt),
            pushPlatforms = emptyList(),
            notificationsEnabled = true,
        )

    /** The daemon's push of [json] to the device key whose raw public key is [devicePublic]. */
    private fun push(
        devicePublic: ByteArray,
        json: String,
    ): PushEnvelope {
        val key = PushKeys.derive(agree(gateway, devicePublic), salt)
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val padded = (json.encodeToByteArray() + 0x80.toByte()).copyOf(PADDED_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        val data = mapOf("v" to "2", "n" to base64(nonce), "c" to base64(cipher.doFinal(padded)))
        return (PushEnvelope.read(data) as EnvelopeRead.Read).envelope
    }

    @Test
    fun aKeystoreAgreeKeyOpensAPushSealedForItAndALostKeyIsPassedOver() {
        keys.generate(alias, ByteArray(CHALLENGE_BYTES) { 7 })
        val devicePublic = keys.staticKey(alias).publicKey
        val json = """{"kind":"message","profile_id":"main","server_seq":3,"preview_text":"Build finished"}"""
        val envelope = push(devicePublic, json)
        val owner = record(alias)
        val logged = mutableListOf<String>()
        val trial = TrialDecrypt(keys) { message, _ -> logged += message }

        val (opened, took) =
            measureTimedValue {
                runBlocking {
                    trial.open(
                        envelope,
                        listOf(record(lostAlias), owner),
                    )
                }
            }
        Log.i(TAG, "trial over a lost key and a Keystore key took ${took.inWholeMilliseconds} ms")
        assertSame(owner, opened?.instance)
        val read = readPushPlaintext(checkNotNull(opened).padded())
        assertEquals(PlaintextRead.Read(PushPlaintext.Message("main", 3uL, "Build finished")), read)
        assertEquals(1, logged.size)
    }

    @Test
    fun aPushSealedForAnotherKeyIsOpenedByNoneOnThePhone() {
        keys.generate(alias, ByteArray(CHALLENGE_BYTES) { 7 })
        val stranger = newX25519Pair()
        val envelope = push(raw(stranger), """{"kind":"message","profile_id":"main","server_seq":3}""")
        assertNull(runBlocking { TrialDecrypt(keys) { _, _ -> }.open(envelope, listOf(record(alias))) })
    }

    @Test
    fun aKeystoreAgreeKeyMadeUnlockedOpensAPushWhileTheScreenIsLockedBehindAPin() {
        val keyguard =
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(KeyguardManager::class.java)
        val json = """{"kind":"approval","profile_id":"main","approval_id":"a1","expires_at":1790000060}"""
        val read =
            withPin(keyguard) {
                keys.generate(alias, ByteArray(CHALLENGE_BYTES) { 7 })
                val envelope = push(keys.staticKey(alias).publicKey, json)
                lockScreen(keyguard)
                val trial = TrialDecrypt(keys) { _, _ -> }
                val (opened, took) = measureTimedValue { runBlocking { trial.open(envelope, listOf(record(alias))) } }
                Log.i(TAG, "trial over a Keystore key with the screen locked took ${took.inWholeMilliseconds} ms")
                assertTrue("the screen stayed locked through the trial", keyguard.isDeviceLocked)
                readPushPlaintext(checkNotNull(opened).padded())
            }
        assertEquals(PlaintextRead.Read(PushPlaintext.Approval("main", "a1", 1_790_000_060L)), read)
    }
}
