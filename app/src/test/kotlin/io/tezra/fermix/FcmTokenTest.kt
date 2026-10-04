package io.tezra.fermix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FCM's token in memory (FermixMessagingService.kt): asked of FCM once while none is held and no ask is out,
 * never waited for, handed back by the service; and a changed or unasked one makes every daemon register
 * again.
 */
class FcmTokenTest {
    private var asks = 0
    private val fcm = FcmToken { asks += 1 }

    @Test
    fun `with none held FCM is asked once, and the token it hands back is held and no news`() {
        assertNull(fcm.current())
        assertNull(fcm.current())
        assertEquals(1, asks)
        assertFalse(fcm.registered("token-1"))
        assertEquals("token-1", fcm.current())
        assertEquals(1, asks)
    }

    @Test
    fun `a token that changes, or comes unasked, registers every daemon again`() {
        assertTrue(fcm.registered("token-1"))
        assertFalse(fcm.registered("token-1"))
        assertTrue(fcm.registered("token-2"))
        assertEquals(0, asks)
    }

    @Test
    fun `an ask that failed is asked again, and a token after it counts as unasked`() {
        assertNull(fcm.current())
        fcm.failed()
        assertNull(fcm.current())
        assertEquals(2, asks)
        fcm.failed()
        assertTrue(fcm.registered("token-1"))
    }
}
