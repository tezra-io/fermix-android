package io.tezra.fermix.design

import android.view.HapticFeedbackConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Design section 13.1, "Haptics": four meanings (act, refuse, arrive, threshold), and the table of
// uses and constants; Copy is section 13.5's code card ("Copy (toast + CONFIRM)"), and MarkLands the M51 update's 3.5
// ("CLOCK_TICK at 380 ms as the dot lands, the 'arrive' meaning").
class HapticsTest {
    @Test
    fun `each use has the design's meaning and constant`() {
        val expected =
            mapOf(
                HapticUse.Send to (Haptic.Act to HapticFeedbackConstants.CONFIRM),
                HapticUse.Stop to (Haptic.Refuse to HapticFeedbackConstants.REJECT),
                HapticUse.FinalBubble to (Haptic.Arrive to HapticFeedbackConstants.CLOCK_TICK),
                HapticUse.SasDigit to (Haptic.Arrive to HapticFeedbackConstants.SEGMENT_TICK),
                HapticUse.PairApproved to (Haptic.Act to HapticFeedbackConstants.CONFIRM),
                HapticUse.Refusal to (Haptic.Refuse to HapticFeedbackConstants.REJECT),
                HapticUse.VoiceThreshold to (Haptic.Threshold to HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE),
                HapticUse.LongPress to (Haptic.Threshold to HapticFeedbackConstants.LONG_PRESS),
                HapticUse.QrDecoded to (Haptic.Act to HapticFeedbackConstants.CONFIRM),
                HapticUse.Copy to (Haptic.Act to HapticFeedbackConstants.CONFIRM),
                HapticUse.MarkLands to (Haptic.Arrive to HapticFeedbackConstants.CLOCK_TICK),
            )
        assertEquals(expected, HapticUse.entries.associateWith { it.meaning to it.feedbackConstant })
    }

    @Test
    fun `a constant carries one meaning wherever it is used`() {
        val meanings = HapticUse.entries.groupBy({ it.feedbackConstant }, { it.meaning })
        for ((constant, meaningsOfConstant) in meanings) {
            assertEquals(1, meaningsOfConstant.toSet().size, "constant $constant")
        }
    }

    @Test
    fun `every meaning is used`() {
        assertEquals(Haptic.entries.toSet(), HapticUse.entries.map { it.meaning }.toSet())
    }
}
