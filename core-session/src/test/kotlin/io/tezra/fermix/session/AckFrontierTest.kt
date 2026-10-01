package io.tezra.fermix.session

import io.tezra.fermix.session.Announcement.ALREADY_KNOWN
import io.tezra.fermix.session.Announcement.NOTIFIED
import io.tezra.fermix.session.Announcement.NOT_ANNOUNCED
import io.tezra.fermix.session.Announcement.ON_SCREEN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The ack follows the announcement (design section 7, the `ack` row; tla/specs/mobile_push PUSH-2), a
 * session resumes it as stored, and the read frontier is monotonic and clamped to the head.
 */
class AckFrontierTest {
    @Test
    fun `the ack advances over rows announced in order, from the base`() {
        val frontier =
            AckFrontier(base = 10uL)
                .applied(11uL, ON_SCREEN)
                .applied(12uL, NOTIFIED)
                .applied(13uL, ALREADY_KNOWN)
        assertEquals(13uL, frontier.ackable)
    }

    @Test
    fun `a row not announced holds the ack below it, whatever is announced after it`() {
        val frontier =
            AckFrontier(base = 10uL)
                .applied(11uL, NOTIFIED)
                .applied(12uL, NOT_ANNOUNCED)
                .applied(13uL, NOTIFIED)
                .applied(14uL, ON_SCREEN)
        assertEquals(11uL, frontier.ackable)
    }

    @Test
    fun `a read that reaches the row not announced releases the ack, and it follows every announcement after`() {
        val held =
            AckFrontier(base = 10uL)
                .applied(11uL, NOT_ANNOUNCED)
                .applied(12uL, NOTIFIED)
                .applied(13uL, NOTIFIED)
        assertEquals(10uL, held.ackable)
        assertEquals(10uL, held.read(10uL).ackable)
        assertEquals(13uL, held.read(11uL).ackable)
        assertEquals(14uL, held.read(12uL).applied(14uL, NOTIFIED).ackable)
        assertEquals(
            15uL,
            held
                .read(13uL)
                .applied(14uL, NOTIFIED)
                .applied(15uL, ON_SCREEN)
                .ackable,
        )
        assertEquals(13uL, held.read(99uL).ackable)
    }

    @Test
    fun `with two rows not announced, a read between them releases the ack only up to the read`() {
        val held =
            AckFrontier(base = 10uL)
                .applied(11uL, NOT_ANNOUNCED)
                .applied(12uL, NOTIFIED)
                .applied(13uL, NOT_ANNOUNCED)
                .applied(14uL, NOTIFIED)
        assertEquals(13uL, held.held)
        assertEquals(11uL, held.read(11uL).ackable)
        val past = held.read(11uL).applied(15uL, NOTIFIED)
        assertEquals(11uL, past.ackable)
        assertEquals(15uL, past.read(13uL).ackable)
    }

    @Test
    fun `the read frontier never moves the ack back`() {
        val frontier = AckFrontier(base = 10uL).applied(11uL, NOTIFIED)
        assertEquals(11uL, frontier.read(5uL).ackable)
    }

    @Test
    fun `rows are applied in order, each above the last`() {
        assertThrows<IllegalArgumentException> { AckFrontier(base = 10uL).applied(10uL, NOTIFIED) }
    }

    @Test
    fun `a frontier resumed below the cursor stays held, whatever is announced after, until the row is read`() {
        val resumed = AckFrontier.resumed(announcedUpTo = 4uL, cursor = 6uL, lastUnannounced = 5uL)
        assertEquals(4uL, resumed.ackable)
        assertEquals(4uL, resumed.applied(7uL, NOTIFIED).applied(8uL, ON_SCREEN).ackable)
        assertEquals(6uL, resumed.read(5uL).ackable)
        assertEquals(
            8uL,
            resumed
                .read(5uL)
                .applied(7uL, NOTIFIED)
                .applied(8uL, ON_SCREEN)
                .ackable,
        )
    }

    @Test
    fun `stored cursors whose held row is not past the frontier and at most the cursor are refused`() {
        listOf(0uL, 4uL, 7uL).forEach { held ->
            assertThrows<IllegalArgumentException> {
                AckFrontier.resumed(announcedUpTo = 4uL, cursor = 6uL, lastUnannounced = held)
            }
        }
    }

    @Test
    fun `a frontier resumed above the cursor, after a rebuild, judges again the rows past it`() {
        val rebuilt = AckFrontier.resumed(announcedUpTo = 4uL, cursor = 0uL, lastUnannounced = 6uL)
        assertEquals(4uL, rebuilt.lastApplied)
        assertEquals(0uL, rebuilt.held)
        assertEquals(4uL, rebuilt.applied(5uL, NOT_ANNOUNCED).applied(6uL, NOTIFIED).ackable)
        assertEquals(6uL, rebuilt.applied(5uL, NOTIFIED).applied(6uL, NOTIFIED).ackable)
    }

    @Test
    fun `the read frontier only moves forward, and never past the head`() {
        assertEquals(12uL, advanceReadFrontier(current = 10uL, reported = 12uL, head = 20uL))
        assertEquals(10uL, advanceReadFrontier(current = 10uL, reported = 7uL, head = 20uL))
        assertEquals(20uL, advanceReadFrontier(current = 10uL, reported = 25uL, head = 20uL))
        assertEquals(10uL, advanceReadFrontier(current = 10uL, reported = 25uL, head = 8uL))
    }
}
