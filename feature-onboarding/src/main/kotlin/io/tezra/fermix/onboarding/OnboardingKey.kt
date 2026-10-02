package io.tezra.fermix.onboarding

import androidx.navigation3.runtime.NavKey

/**
 * The onboarding screens of design section 13.3 as keys of the app's back stack, in section 13.2's
 * order. [Welcome] is a root the app shows when no Fermix is paired; the others stand above a root,
 * in the stacks [stackOf] gives them.
 */
sealed interface OnboardingKey : NavKey {
    data object Welcome : OnboardingKey

    data object Pair : OnboardingKey

    data object Scan : OnboardingKey

    data object Connecting : OnboardingKey

    data object Verify : OnboardingKey

    data object Paired : OnboardingKey

    /** Section 9.2's "Name this Fermix", after Paired when a second Fermix would carry the same name. */
    data object Name : OnboardingKey

    data object Notifications : OnboardingKey

    /** One row of section 13.3's failure table, each its own screen. */
    data class Failure(
        val case: FailureCase,
    ) : OnboardingKey
}
