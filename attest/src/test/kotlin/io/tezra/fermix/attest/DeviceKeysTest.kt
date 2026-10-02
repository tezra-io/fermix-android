package io.tezra.fermix.attest

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What of DeviceKeys runs on the JVM: its refusals, which come before any Keystore call. AndroidKeyStore
 * itself is the device gate's (README).
 */
class DeviceKeysTest {
    @Test
    fun `every call refuses an alias that is no device key's, before it reaches the Keystore`() {
        val keys = DeviceKeys()
        // The app's other Keystore entries, such as an app-lock key, are never a device key's to touch.
        val other = "app.lock"

        assertThrows<IllegalArgumentException> { keys.generate(other, ByteArray(32)) }
        assertThrows<IllegalArgumentException> { keys.delete(other) }
        assertThrows<IllegalArgumentException> { keys.exists(other) }
        assertThrows<IllegalArgumentException> { keys.staticKey(other) }
    }
}
