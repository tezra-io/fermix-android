package io.tezra.fermix.design

import android.view.HapticFeedbackConstants
import android.view.View

/** The four meanings a haptic may carry, the design's grammar (sections 13.1 and 13.10, item 10). */
enum class Haptic { Act, Refuse, Arrive, Threshold }

/**
 * Every place the app plays a haptic (design section 13.1, "Haptics"; Copy is section 13.5's code card),
 * with its meaning and the platform constant that plays it. A meaning may sound through more than one
 * constant (an arrival ticks a clock for the final bubble and a segment for each SAS digit), so a screen
 * names the use, never a constant.
 */
enum class HapticUse(
    val meaning: Haptic,
    val feedbackConstant: Int,
) {
    Send(Haptic.Act, HapticFeedbackConstants.CONFIRM),
    Stop(Haptic.Refuse, HapticFeedbackConstants.REJECT),
    FinalBubble(Haptic.Arrive, HapticFeedbackConstants.CLOCK_TICK),

    /** Played for each digit, [FermixMotion.SAS_DIGIT_STAGGER_MILLIS] apart. */
    SasDigit(Haptic.Arrive, HapticFeedbackConstants.SEGMENT_TICK),
    PairApproved(Haptic.Act, HapticFeedbackConstants.CONFIRM),
    Refusal(Haptic.Refuse, HapticFeedbackConstants.REJECT),
    VoiceThreshold(Haptic.Threshold, HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE),
    LongPress(Haptic.Threshold, HapticFeedbackConstants.LONG_PRESS),
    QrDecoded(Haptic.Act, HapticFeedbackConstants.CONFIRM),
    Copy(Haptic.Act, HapticFeedbackConstants.CONFIRM),
}

/** The one place a haptic is played. */
object HapticFeedback {
    /**
     * Plays [use] through [view]. Returns the platform's answer: false when it played nothing, as when
     * the owner turned touch feedback off, which the platform honours on its own.
     */
    fun perform(
        view: View,
        use: HapticUse,
    ): Boolean = view.performHapticFeedback(use.feedbackConstant)
}
