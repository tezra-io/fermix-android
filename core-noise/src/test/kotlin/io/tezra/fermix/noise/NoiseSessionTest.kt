package io.tezra.fermix.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.experimental.xor

/** The transport after Split: the wire's bounds, and a session's three ends: a failed tag, a spent nonce, close. */
class NoiseSessionTest {
    private val connected = connect(vector("ik"))
    private val session = connected.session
    private val spentKey = ByteArray(32) { 7 }
    private val penultimateNonce = ULong.MAX_VALUE - 1u

    @Test
    fun `a 65,519-byte plaintext seals to a 65,535-byte message and a 65,520-byte one is refused`() {
        assertEquals(65_535, session.encrypt(ByteArray(65_519)).size)
        val refusal = assertThrows<NoiseException.MessageTooLarge> { session.encrypt(ByteArray(65_520)) }
        assertEquals(65_520, refusal.size)
        assertEquals(65_519, refusal.max)
    }

    @Test
    fun `a 65,535-byte message opens and a 65,536-byte one is refused`() {
        val largest = connected.responderSend.encryptWithAd(ByteArray(0), ByteArray(65_519))
        assertEquals(65_535, largest.size)
        assertHex(ByteArray(65_519), session.decrypt(largest))
        val refusal = assertThrows<NoiseException.MessageTooLarge> { session.decrypt(ByteArray(65_536)) }
        assertEquals(65_536, refusal.size)
        assertEquals(65_535, refusal.max)
    }

    @Test
    fun `a message shorter than its tag is refused`() {
        val refusal = assertThrows<NoiseException.MessageTooShort> { session.decrypt(ByteArray(15)) }
        assertEquals(15, refusal.size)
        assertEquals(16, refusal.min)
    }

    @Test
    fun `a tampered message fails authentication, zeroes both keys, and the session refuses every later call`() {
        val sealed = connected.responderSend.encryptWithAd(ByteArray(0), "frame".encodeToByteArray())
        val tampered = sealed.copyOf().also { it[0] = it[0] xor 1 }
        assertThrows<NoiseException.AuthenticationFailed> { session.decrypt(tampered) }
        // The untampered frame would open at this nonce; the session refuses it all the same.
        assertEnded(session)
        assertThrows<NoiseException.UsedAfterFailure> { session.decrypt(sealed) }
    }

    @Test
    fun `a closed session zeroes both keys and refuses every later call`() {
        val sealed = connected.responderSend.encryptWithAd(ByteArray(0), "frame".encodeToByteArray())
        session.close()
        assertZero("the send key of a closed session", session.send.key)
        assertZero("the receive key of a closed session", session.receive.key)
        assertThrows<NoiseException.OutOfOrder> { session.decrypt(sealed) }
        assertThrows<NoiseException.OutOfOrder> { session.encrypt("frame".encodeToByteArray()) }
    }

    @Test
    fun `the send direction's last nonce is refused, never wrapped, and ends the session`() {
        val nearlySpent = nearlySpentSession()
        nearlySpent.encrypt("last".encodeToByteArray())
        assertThrows<NoiseException.NonceExhausted> { nearlySpent.encrypt("past".encodeToByteArray()) }
        assertEquals(ULong.MAX_VALUE, nearlySpent.send.nonce)
        assertEnded(nearlySpent)
    }

    @Test
    fun `the receive direction's last nonce is refused, never wrapped, and ends the session`() {
        val nearlySpent = nearlySpentSession()
        val sealed = CipherState(spentKey, penultimateNonce).encryptWithAd(ByteArray(0), "last".encodeToByteArray())
        assertEquals("last", nearlySpent.decrypt(sealed).decodeToString())
        assertThrows<NoiseException.NonceExhausted> { nearlySpent.decrypt(sealed) }
        assertEquals(ULong.MAX_VALUE, nearlySpent.receive.nonce)
        assertEnded(nearlySpent)
    }

    /** Both directions one frame from their last nonce, under one key. */
    private fun nearlySpentSession() =
        NoiseSession(CipherState(spentKey, penultimateNonce), CipherState(spentKey, penultimateNonce), ByteArray(32))

    /** A session a refusal ended: both keys zeroed, and every later call refused in both directions. */
    private fun assertEnded(ended: NoiseSession) {
        assertZero("the send key of an ended session", ended.send.key)
        assertZero("the receive key of an ended session", ended.receive.key)
        assertThrows<NoiseException.UsedAfterFailure> { ended.encrypt("frame".encodeToByteArray()) }
        assertThrows<NoiseException.UsedAfterFailure> { ended.decrypt(ByteArray(TAG_BYTES)) }
    }
}
