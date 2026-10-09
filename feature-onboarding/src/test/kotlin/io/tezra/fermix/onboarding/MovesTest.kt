package io.tezra.fermix.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Which move each change of screen makes (the M51 update's 7.2), along the back stack's own steps (stackOf): the shared
 * axis between the flow's steps, forward and back, and fade through into and out of Scan, into and out of a failure,
 * and into and out of onboarding, the app's screen given as none.
 */
class MovesTest {
    private val failure = OnboardingKey.Failure(FailureCase.EXPIRED)

    @Test
    fun `the flow's steps move on the shared axis, forward as they go and back as back takes them`() {
        val forward =
            listOf(
                OnboardingKey.Welcome to OnboardingKey.Pair,
                OnboardingKey.Connecting to OnboardingKey.Verify,
                OnboardingKey.Verify to OnboardingKey.Paired,
                OnboardingKey.Paired to OnboardingKey.Name,
                OnboardingKey.Paired to OnboardingKey.Notifications,
                OnboardingKey.Name to OnboardingKey.Notifications,
                // A pasted link goes from Pair to Connecting, the next step, with no camera between.
                OnboardingKey.Pair to OnboardingKey.Connecting,
            )
        for ((from, to) in forward) {
            assertEquals(Move.FORWARD, moveBetween(from, to, pops = false), "$from to $to")
            assertEquals(Move.BACK, moveBetween(to, from, pops = true), "$to back to $from")
        }
    }

    @Test
    fun `into and out of Scan the screens fade through, Verify's Cancel back to Scan among them`() {
        val around = listOf(OnboardingKey.Pair, OnboardingKey.Connecting, OnboardingKey.Verify, failure)
        for (other in around) {
            assertEquals(Move.FADE_THROUGH, moveBetween(other, OnboardingKey.Scan, pops = false), "$other to Scan")
            assertEquals(Move.FADE_THROUGH, moveBetween(OnboardingKey.Scan, other, pops = false), "Scan to $other")
            assertEquals(Move.FADE_THROUGH, moveBetween(other, OnboardingKey.Scan, pops = true), "$other back")
            assertEquals(Move.FADE_THROUGH, moveBetween(OnboardingKey.Scan, other, pops = true), "Scan back")
        }
    }

    @Test
    fun `into a failure, and out of one, the screens fade through, never the forward slide`() {
        val steps = listOf(OnboardingKey.Welcome, OnboardingKey.Pair, OnboardingKey.Connecting, OnboardingKey.Verify)
        for (step in steps) {
            assertEquals(Move.FADE_THROUGH, moveBetween(step, failure, pops = false), "$step to the failure")
            assertEquals(Move.FADE_THROUGH, moveBetween(failure, step, pops = true), "the failure back to $step")
            assertEquals(Move.FADE_THROUGH, moveBetween(failure, step, pops = false), "the failure on to $step")
        }
    }

    @Test
    fun `out of onboarding into the Chats list, and in from it, the screens fade through`() {
        for (key in listOf(OnboardingKey.Paired, OnboardingKey.Name, OnboardingKey.Notifications)) {
            assertEquals(Move.FADE_THROUGH, moveBetween(key, null, pops = true), "$key to the list")
        }
        assertEquals(Move.FADE_THROUGH, moveBetween(OnboardingKey.Pair, null, pops = true))
        assertEquals(Move.FADE_THROUGH, moveBetween(null, OnboardingKey.Pair, pops = false))
        assertEquals(Move.FADE_THROUGH, moveBetween(null, OnboardingKey.Welcome, pops = false))
        assertThrows<IllegalArgumentException> { moveBetween(null, null, pops = false) }
    }
}
