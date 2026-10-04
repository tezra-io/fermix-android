package io.tezra.fermix.push

import io.tezra.fermix.protocol.Caps
import io.tezra.fermix.protocol.PushPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val NOW = 1_790_000_000_000L

/**
 * Design section 10's registration decisions, as each connection makes them: the first `hello` after the
 * permission is granted registers, a registration 7 days old is sent again, and a phone whose notifications
 * cannot show unregisters.
 */
class RegistrationTest {
    private val fcm =
        pairedRecord(ByteArray(32) { 1 }, "fermix.device.a").copy(pushPlatforms = listOf(PushPlatform.FCM))

    @Test
    fun `the first hello after the permission is granted registers, and the next one sends nothing`() {
        assertEquals(RegistrationStep.REGISTER, registrationStep(fcm, canShow = true, nowMs = NOW))
        assertEquals(RegistrationStep.NONE, registrationStep(fcm.copy(fcmRegisteredAt = NOW), true, NOW + 1))
    }

    @Test
    fun `a registration 7 days old is sent again, one a moment younger is not`() {
        val registered = fcm.copy(fcmRegisteredAt = NOW)
        assertEquals(RegistrationStep.NONE, registrationStep(registered, true, NOW + REGISTRATION_MAX_AGE_MS - 1))
        assertEquals(RegistrationStep.REGISTER, registrationStep(registered, true, NOW + REGISTRATION_MAX_AGE_MS))
        // A clock set back past the registration cannot tell its age: it is sent again.
        assertEquals(RegistrationStep.REGISTER, registrationStep(registered, true, NOW - 1))
    }

    @Test
    fun `notifications that cannot show unregister a registered phone, and send nothing for one that is not`() {
        val registered = fcm.copy(fcmRegisteredAt = NOW)
        assertEquals(RegistrationStep.UNREGISTER, registrationStep(registered, canShow = false, nowMs = NOW))
        assertEquals(RegistrationStep.NONE, registrationStep(fcm, canShow = false, nowMs = NOW))
    }

    @Test
    fun `the switch off unregisters, and a daemon with no FCM is never registered`() {
        val off = fcm.copy(notificationsEnabled = false, fcmRegisteredAt = NOW)
        assertEquals(RegistrationStep.UNREGISTER, registrationStep(off, true, NOW))
        val apnsOnly =
            fcm.copy(
                caps = Caps(commands = emptyList(), maxMediaBytes = 1, push = listOf(PushPlatform.APNS)),
            )
        assertEquals(RegistrationStep.NONE, registrationStep(apnsOnly, true, NOW))
    }
}
