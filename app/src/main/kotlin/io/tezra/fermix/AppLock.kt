package io.tezra.fermix

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "FermixAppLock"

/** What the lock asks for (design section 13.7): a strong biometric, or the phone's own screen lock. */
private const val UNLOCK_WITH = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

/**
 * The app lock's gate (design section 13.7), app-wide: with the lock on, the app is locked when it comes
 * into sight after the process started or after [graceMillis] out of sight, and stays so until an unlock
 * succeeds. Turning the lock off unlocks; turning it on locks only at the next return. The activity tells
 * it when the app comes into and goes out of sight, on the elapsed-time clock, and the settings tell it
 * whether the lock is on; until they have once ([known]), nothing of the app shows. A phone that cannot
 * hold the lock ([canLock]: no strong biometric and no screen lock, as after its owner removed the screen
 * lock) is never locked, and a lock it holds opens at the next sight, so the owner is never locked out.
 */
class LockGate(
    private val canLock: () -> Boolean,
    private val graceMillis: Long = BACKGROUND_GRACE_MILLIS,
) {
    private val lockedState = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = lockedState.asStateFlow()

    private val knownState = MutableStateFlow(false)

    /** Whether the settings have said once whether the lock is on. */
    val known: StateFlow<Boolean> = knownState.asStateFlow()

    private var enabled = false
    private var inSight = false

    /** Whether the next sight locks: the process just started, or the app was away past the grace. */
    private var due = true
    private var leftAt: Long? = null

    init {
        require(graceMillis > 0) { "a grace of $graceMillis ms" }
    }

    /** The settings say whether the lock is on: on locks now only when a lock is due and the app is in sight. */
    fun lockSetting(on: Boolean) {
        enabled = on
        knownState.value = true
        if (!on) {
            due = false
            lockedState.value = false
        }
        lockIfDue()
    }

    /**
     * The app came into sight at [now], elapsed milliseconds. An absence is weighed once: a rotation or a fold
     * brings the recreated activity into sight again without the app having left, and is no return.
     */
    fun cameIntoSight(now: Long) {
        inSight = true
        val away = leftAt
        leftAt = null
        if (away != null && now - away > graceMillis) due = true
        lockIfDue()
    }

    /** The app went out of sight at [now], elapsed milliseconds. */
    fun wentOutOfSight(now: Long) {
        inSight = false
        leftAt = now
    }

    /** The owner proved who they are. */
    fun unlocked() {
        due = false
        lockedState.value = false
    }

    /** A due lock holds while the phone can hold it, and a lock it can no longer hold opens. */
    private fun lockIfDue() {
        if (enabled && due && inSight) lockedState.value = canLock()
    }
}

/** Whether this phone can hold the lock: a strong biometric or a screen lock is set up. */
fun canLock(context: Context): Boolean {
    val biometrics = context.getSystemService(BiometricManager::class.java)
    return biometrics.canAuthenticate(UNLOCK_WITH) == BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Asks the owner to unlock with the system's prompt, titled [title]; [onUnlocked] runs on success. A
 * cancelled or failed prompt leaves the app locked, with "Unlock" to try again, and is logged.
 */
fun promptUnlock(
    activity: Activity,
    title: String,
    onUnlocked: () -> Unit,
) {
    val prompt =
        BiometricPrompt
            .Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(UNLOCK_WITH)
            .build()
    val callback =
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence,
            ) {
                Log.i(TAG, "the unlock ended without success: $errorCode $errString")
            }
        }
    prompt.authenticate(CancellationSignal(), activity.mainExecutor, callback)
}
