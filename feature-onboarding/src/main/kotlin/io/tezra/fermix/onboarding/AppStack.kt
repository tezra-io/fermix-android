package io.tezra.fermix.onboarding

import androidx.navigation3.runtime.NavKey

/**
 * The app's back stack (design section 13.2): its root, Welcome until a Fermix is [paired] and [chats]
 * from then on, with onboarding's screens above it. The root turns to the Chats list under Paired, as the
 * record is stored before Paired shows, so the steps after it lead back to the list.
 */
fun appBackStack(
    paired: Boolean,
    chats: NavKey,
    onboarding: List<OnboardingKey>,
): List<NavKey> {
    require(chats !is OnboardingKey) { "$chats is onboarding's, not the Chats list" }
    return listOf(if (paired) chats else OnboardingKey.Welcome) + onboarding
}

/** Whether the window showing [top] is kept out of screenshots and the recents: every onboarding screen. */
fun securesWindow(top: NavKey): Boolean = top is OnboardingKey

/**
 * Whether [top] lies dark under the system bars in both modes, so that their icons are white over it: the
 * scan's camera, full bleed, as the visual canon draws its `.phone.bleed`. Every other screen is on the
 * theme's canvas, and its bars follow the theme.
 */
fun darkUnderBars(top: NavKey): Boolean = top == OnboardingKey.Scan
