package io.tezra.fermix.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// Design section 13.1, "Motion", with the mark's orbit and the indicator's cross-fade of section 13.5.
class FermixMotionTest {
    @Test
    fun `the moments are the design's`() {
        assertEquals(12.dp, FermixMotion.bubbleInsertRise)
        assertEquals(800, FermixMotion.CURSOR_BLINK_MILLIS)
        assertEquals(1_600, FermixMotion.THINKING_SHIMMER_MILLIS)
        assertEquals(1_200, FermixMotion.MARK_ORBIT_MILLIS)
        assertEquals(200, FermixMotion.INDICATOR_CROSS_FADE_MILLIS)
        assertEquals(300, FermixMotion.THINKING_TO_ANSWER_MILLIS)
        assertEquals(0.92f, FermixMotion.TOOL_CHIP_SCALE_FROM)
        assertEquals(1_200, FermixMotion.TOOL_CHIP_ARC_MILLIS)
        assertEquals(200, FermixMotion.SEND_STOP_MILLIS)
        assertEquals(2_000, FermixMotion.CONNECTION_BANNER_DELAY_MILLIS)
        assertEquals(500, FermixMotion.DATE_PILL_FADE_DELAY_MILLIS)
        assertEquals(1_500, FermixMotion.JUMP_HIGHLIGHT_MILLIS)
        assertEquals(0.12f, FermixMotion.JUMP_HIGHLIGHT_ALPHA)
        assertEquals(350, FermixMotion.APPROVAL_RESOLVE_MILLIS)
        assertEquals(40, FermixMotion.SAS_DIGIT_STAGGER_MILLIS)
    }

    @Test
    fun `emphasized is Material's emphasized easing`() {
        assertEquals(CubicBezierEasing(0.2f, 0f, 0f, 1f), FermixMotion.emphasized)
    }

    @Test
    fun `the standard scheme is Material's standard springs`() {
        val standard = FermixMotionScheme.Standard
        assertEquals(MotionSpring(0.9f, 700f), standard.defaultSpatial)
        assertEquals(MotionSpring(0.9f, 1_400f), standard.fastSpatial)
        assertEquals(MotionSpring(0.9f, 300f), standard.slowSpatial)
        assertEquals(MotionSpring(1f, 1_600f), standard.defaultEffects)
        assertEquals(MotionSpring(1f, 3_800f), standard.fastEffects)
        assertEquals(MotionSpring(1f, 800f), standard.slowEffects)
    }

    @Test
    fun `the expressive scheme is Material's expressive springs`() {
        val expressive = FermixMotionScheme.Expressive
        assertEquals(MotionSpring(0.8f, 380f), expressive.defaultSpatial)
        assertEquals(MotionSpring(0.6f, 800f), expressive.fastSpatial)
        assertEquals(MotionSpring(0.8f, 200f), expressive.slowSpatial)
        assertEquals(MotionSpring(1f, 1_600f), expressive.defaultEffects)
        assertEquals(MotionSpring(1f, 3_800f), expressive.fastEffects)
        assertEquals(MotionSpring(1f, 800f), expressive.slowEffects)
    }

    @Test
    fun `an animator duration scale of zero, and only zero, is reduce-motion`() {
        assertTrue(isReducedMotion(0f))
        assertFalse(isReducedMotion(0.5f))
        assertFalse(isReducedMotion(1f))
        assertFalse(isReducedMotion(10f))
    }

    @Test
    fun `a scale the platform cannot hold is refused`() {
        assertThrows<IllegalArgumentException> { isReducedMotion(-1f) }
        assertThrows<IllegalArgumentException> { isReducedMotion(Float.NaN) }
    }

    @Test
    fun `under reduce-motion a spring snaps`() {
        val spring = FermixMotionScheme.Standard.defaultSpatial
        assertEquals(spring<Float>(dampingRatio = 0.9f, stiffness = 700f), spring.spec<Float>(reducedMotion = false))
        assertInstanceOf(SnapSpec::class.java, spring.spec<Float>(reducedMotion = true))
    }
}
